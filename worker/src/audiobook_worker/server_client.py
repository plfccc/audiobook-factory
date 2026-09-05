from __future__ import annotations

from dataclasses import asdict, dataclass, field, is_dataclass
import hashlib
import json
import os
from pathlib import Path
import re
from typing import Any, Mapping

import httpx

from .contracts import EngineCapabilities, TtsJob, TtsPreset, VoiceProfile
from .runtime_probe import RuntimeProbe


_BASE_MODEL_ID = "Qwen/Qwen3-TTS-12Hz-1.7B-Base"
_HEX_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")


@dataclass(frozen=True)
class WorkerRegistration:
    worker_id: str
    worker_token: str | None = field(default=None, repr=False)
    lease_seconds: int = 300
    worker_name: str | None = None
    status: str = "ACTIVE"

    @property
    def token(self) -> str | None:
        return self.worker_token


class ControlPlaneError(RuntimeError):
    """A non-success response from the control-plane protocol."""

    def __init__(
        self,
        message: str,
        *,
        code: str,
        status_code: int | None = None,
        retryable: bool = False,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.status_code = status_code
        self.retryable = retryable


class ControlPlaneClient:
    """Small async HTTPS client for the Colab Worker control protocol."""

    def __init__(
        self,
        base_url: str,
        token: str,
        *,
        timeout: httpx.Timeout | float | None = None,
        connect_timeout: float = 10.0,
        read_timeout: float = 60.0,
        write_timeout: float = 120.0,
        pool_timeout: float = 10.0,
        transport: httpx.AsyncBaseTransport | None = None,
        http_client: httpx.AsyncClient | None = None,
    ) -> None:
        if not isinstance(base_url, str) or not base_url.strip():
            raise ValueError("base_url must not be blank")
        if not isinstance(token, str) or not token.strip():
            raise ValueError("token must not be blank")
        self.base_url = base_url.rstrip("/")
        self._token = token
        self._transport = transport
        self._client = http_client
        self._owns_client = http_client is None
        self._timeout = timeout or httpx.Timeout(
            connect=connect_timeout,
            read=read_timeout,
            write=write_timeout,
            pool=pool_timeout,
        )
        self._reference_assets: dict[str, dict[str, str | None]] = {}

    def __repr__(self) -> str:
        return f"ControlPlaneClient(base_url={self.base_url!r})"

    async def __aenter__(self) -> "ControlPlaneClient":
        self._ensure_client()
        return self

    async def __aexit__(self, exc_type, exc_value, traceback) -> None:
        await self.aclose()

    async def aclose(self) -> None:
        if self._client is not None and self._owns_client:
            await self._client.aclose()
            self._client = None

    async def register(
        self,
        worker_name: str,
        runtime: RuntimeProbe | Mapping[str, Any],
        capabilities: Any,
    ) -> WorkerRegistration:
        if not isinstance(worker_name, str) or not worker_name.strip():
            raise ValueError("worker_name must not be blank")
        payload = {
            "workerName": worker_name,
            "runtime": _runtime_payload(runtime),
            "capabilities": _json_compatible(capabilities),
        }
        response = await self._request(
            "POST", "/api/v1/workers/register", json=payload
        )
        data = _json_body(response)
        worker_id = _value(data, "workerId", "worker_id")
        if not worker_id:
            raise ControlPlaneError(
                "worker registration response omitted workerId",
                code="INVALID_RESPONSE",
                status_code=response.status_code,
            )
        worker_token = _value(data, "workerToken", "worker_token", "token")
        if worker_token:
            self._token = str(worker_token)
        return WorkerRegistration(
            worker_id=str(worker_id),
            worker_token=str(worker_token) if worker_token else None,
            lease_seconds=int(_value(data, "leaseSeconds", "lease_seconds") or 300),
            worker_name=_string_or_none(_value(data, "workerName", "worker_name"))
            or worker_name,
            status=str(_value(data, "status") or "ACTIVE"),
        )

    async def claim_job(self) -> TtsJob | None:
        response = await self._request(
            "POST", "/api/v1/workers/claim", allow_statuses={204}
        )
        if response.status_code == 204:
            return None
        data = _json_body(response)
        job_payload = data.get("job") if isinstance(data.get("job"), Mapping) else data
        job = _job_from_payload(job_payload)
        reference_asset = _reference_asset_from_payload(job_payload)
        if reference_asset is not None:
            self._reference_assets[job.job_id] = reference_asset
        return job

    async def heartbeat(self, job_id: str, progress: Any) -> None:
        await self._request(
            "POST",
            f"/api/v1/workers/jobs/{_path_segment(job_id)}/heartbeat",
            json=_json_compatible(progress),
            allow_statuses={204},
        )

    async def download_asset(self, asset_id: str, target: Path | str) -> Path:
        if not isinstance(asset_id, str) or not asset_id.strip():
            raise ValueError("asset_id must not be blank")
        destination = Path(target)
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_name(
            f".{destination.name}.{os.getpid()}.download"
        )
        temporary.unlink(missing_ok=True)
        try:
            client = self._ensure_client()
            async with client.stream(
                "GET",
                self._url(f"/api/v1/assets/{_path_segment(asset_id)}/download"),
                headers=self._headers(),
            ) as response:
                await self._raise_for_response(response)
                with temporary.open("wb") as output:
                    async for chunk in response.aiter_bytes():
                        output.write(chunk)
            os.replace(temporary, destination)
            return destination
        finally:
            temporary.unlink(missing_ok=True)

    async def upload_result(
        self,
        job_id: str,
        audio_path: Path | str,
        metadata: Mapping[str, Any],
    ) -> None:
        audio = Path(audio_path)
        if not audio.is_file():
            raise FileNotFoundError(audio)
        with audio.open("rb") as source:
            response = await self._request(
                "POST",
                f"/api/v1/workers/jobs/{_path_segment(job_id)}/result",
                files={"audio": (audio.name, source, "audio/wav")},
                data={
                    "metadata": json.dumps(
                        _json_compatible(metadata),
                        ensure_ascii=False,
                        sort_keys=True,
                    )
                },
                allow_statuses={204},
            )
        del response

    async def report_failure(self, job_id: str, code: str, message: str) -> None:
        await self._request(
            "POST",
            f"/api/v1/workers/jobs/{_path_segment(job_id)}/failure",
            json={"code": str(code), "message": str(message)},
            allow_statuses={204},
        )

    def reference_asset(self, job_id: str) -> Mapping[str, str | None] | None:
        """Return claim metadata needed to materialize a remote voice asset."""

        return self._reference_assets.get(job_id)

    async def _request(
        self,
        method: str,
        path: str,
        *,
        allow_statuses: set[int] | None = None,
        **kwargs: Any,
    ) -> httpx.Response:
        client = self._ensure_client()
        try:
            response = await client.request(
                method,
                self._url(path),
                headers=self._headers(),
                **kwargs,
            )
        except httpx.RequestError:
            raise
        await self._raise_for_response(response, allow_statuses=allow_statuses)
        return response

    async def _raise_for_response(
        self,
        response: httpx.Response,
        *,
        allow_statuses: set[int] | None = None,
    ) -> None:
        allowed = allow_statuses or set()
        if 200 <= response.status_code < 300 or response.status_code in allowed:
            return
        data = _json_body(response)
        code = _value(data, "code", "errorCode", "error_code")
        if not code:
            code = {
                401: "AUTH_REQUIRED",
                403: "AUTH_REQUIRED",
                429: "QUOTA_PAUSED",
                503: "WAITING_FOR_GPU",
            }.get(response.status_code, "HTTP_ERROR")
        message = _value(data, "message", "error", "detail")
        if not message:
            message = f"control-plane request failed with HTTP {response.status_code}"
        raise ControlPlaneError(
            _redact(str(message), self._token),
            code=str(code),
            status_code=response.status_code,
            retryable=response.status_code >= 500 or response.status_code == 429,
        )

    def _ensure_client(self) -> httpx.AsyncClient:
        if self._client is None:
            self._client = httpx.AsyncClient(
                timeout=self._timeout,
                transport=self._transport,
            )
        return self._client

    def _headers(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._token}"}

    def _url(self, path: str) -> str:
        return f"{self.base_url}/{path.lstrip('/')}"


def _runtime_payload(runtime: RuntimeProbe | Mapping[str, Any]) -> dict[str, Any]:
    if isinstance(runtime, RuntimeProbe):
        return {
            "cudaAvailable": runtime.cuda_available,
            "gpuName": runtime.gpu_name,
            "gpuMemoryBytes": runtime.gpu_memory_bytes,
            "cudaVersion": runtime.cuda_version,
            "torchVersion": runtime.torch_version,
            "pythonVersion": runtime.python_version,
        }
    return _json_compatible(runtime)


def _job_from_payload(payload: Mapping[str, Any]) -> TtsJob:
    job_id = str(_value(payload, "jobId", "job_id") or "")
    if not job_id:
        raise ControlPlaneError(
            "claim response omitted jobId", code="INVALID_RESPONSE"
        )
    profile_payload = _value(payload, "voiceProfile", "voice_profile")
    if not isinstance(profile_payload, Mapping):
        profile_payload = None

    profile_id = _value(profile_payload or {}, "profileId", "profile_id")
    top_profile_id = _value(payload, "voiceProfileId", "voice_profile_id")
    profile_id = str(profile_id or top_profile_id or "") or None
    profile = None
    if profile_payload is not None or profile_id is not None:
        profile = VoiceProfile(
            profile_id=profile_id or f"voice-for-{job_id}",
            name=str(_value(profile_payload or {}, "name") or profile_id or "voice"),
            reference_audio_path=_reference_audio_path(profile_payload or payload),
            reference_text=_string_or_none(
                _value(profile_payload or payload, "referenceText", "reference_text")
            ),
            design_prompt=_string_or_none(
                _value(
                    profile_payload or payload,
                    "designPrompt",
                    "design_prompt",
                    "voiceDesignDescription",
                )
            ),
        )

    preset_payload = _value(payload, "preset", "ttsPreset")
    if not isinstance(preset_payload, Mapping):
        preset_payload = payload
    preset_profile_id = _value(
        preset_payload, "voiceProfileId", "voice_profile_id"
    ) or (profile.profile_id if profile else None)
    parameters = _value(preset_payload, "parameters", "modelParameters")
    parameters_json = _value(preset_payload, "parametersJson", "parameters_json")
    if parameters_json is None:
        parameters_json = json.dumps(
            parameters if isinstance(parameters, Mapping) else {},
            ensure_ascii=False,
            sort_keys=True,
        )
    elif not isinstance(parameters_json, str):
        parameters_json = json.dumps(parameters_json, ensure_ascii=False)
    preset = TtsPreset(
        provider=str(_value(preset_payload, "provider", "engine") or "qwen3-tts"),
        model=str(_value(preset_payload, "model", "modelId") or _BASE_MODEL_ID),
        voice=str(
            _value(preset_payload, "voice")
            or (profile.profile_id if profile else "default")
        ),
        style_prompt=str(
            _value(
                preset_payload,
                "stylePrompt",
                "style_prompt",
                "styleInstruction",
            )
            or ""
        ),
        language=str(_value(preset_payload, "language") or "zh-CN"),
        output_format=str(
            _value(preset_payload, "outputFormat", "output_format") or "wav"
        ),
        model_version=str(
            _value(preset_payload, "modelVersion", "model_version") or "unspecified"
        ),
        voice_profile_id=str(preset_profile_id) if preset_profile_id else None,
        parameters_json=parameters_json,
        segment_target_chars=int(
            _value(preset_payload, "segmentTargetChars", "segment_target_chars")
            or 220
        ),
        segment_max_chars=int(
            _value(preset_payload, "segmentMaxChars", "segment_max_chars") or 320
        ),
    )
    return TtsJob(
        job_id=job_id,
        book_id=str(_value(payload, "bookId", "book_id") or f"book-for-{job_id}"),
        book_version_id=str(
            _value(payload, "bookVersionId", "book_version_id")
            or f"version-for-{job_id}"
        ),
        chapter_id=str(
            _value(payload, "chapterId", "chapter_id") or f"chapter-for-{job_id}"
        ),
        chapter_index=int(
            _value(payload, "chapterIndex", "chapter_index") or 0
        ),
        segment_index=int(
            _value(payload, "segmentIndex", "segment_index") or 0
        ),
        text=str(_value(payload, "text") or ""),
        preset=preset,
        voice_profile=profile,
    )


def _reference_audio_path(payload: Mapping[str, Any]) -> Path | None:
    value = _value(
        payload,
        "referenceAudioPath",
        "reference_audio_path",
        "referenceAudioAssetId",
        "reference_audio_asset_id",
    )
    return Path(str(value)) if value else None


def _reference_asset_from_payload(
    payload: Mapping[str, Any],
) -> dict[str, str | None] | None:
    profile_payload = _value(payload, "voiceProfile", "voice_profile")
    if not isinstance(profile_payload, Mapping):
        profile_payload = payload
    asset_id = _value(
        profile_payload,
        "referenceAudioAssetId",
        "reference_audio_asset_id",
    )
    digest = _value(
        profile_payload,
        "referenceAudioSha256",
        "reference_audio_sha256",
    )
    if not asset_id:
        return None
    return {
        "asset_id": str(asset_id),
        "sha256": str(digest) if digest else None,
    }


def _json_body(response: httpx.Response) -> dict[str, Any]:
    if not response.content:
        return {}
    try:
        data = response.json()
    except (ValueError, json.JSONDecodeError):
        return {}
    return data if isinstance(data, dict) else {}


def _value(payload: Mapping[str, Any], *keys: str) -> Any:
    for key in keys:
        if key in payload and payload[key] is not None:
            return payload[key]
    return None


def _string_or_none(value: Any) -> str | None:
    return str(value) if value is not None else None


def _path_segment(value: str) -> str:
    from urllib.parse import quote

    return quote(str(value), safe="")


def _json_compatible(value: Any) -> Any:
    if is_dataclass(value):
        return _json_compatible(asdict(value))
    if isinstance(value, Mapping):
        return {str(key): _json_compatible(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set, frozenset)):
        return [_json_compatible(item) for item in value]
    if isinstance(value, Path):
        return str(value)
    if isinstance(value, (str, int, float, bool)) or value is None:
        return value
    if hasattr(value, "value"):
        return _json_compatible(value.value)
    return str(value)


def _redact(message: str, secret: str) -> str:
    return message.replace(secret, "[REDACTED]") if secret else message


__all__ = ["ControlPlaneClient", "ControlPlaneError", "WorkerRegistration"]
