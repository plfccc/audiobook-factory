from __future__ import annotations

import asyncio
from dataclasses import asdict, is_dataclass, replace
from enum import StrEnum
import hashlib
import inspect
from pathlib import Path
import re
from typing import Any, Awaitable, Callable, Mapping

import httpx

from .config import WorkerSettings
from .contracts import GenerationResult, TtsJob, VoiceProfile
from .errors import WorkerError
from .gpu_selector import GpuSelector
from .model_registry import ModelProfile, ModelRegistry
from .qwen_engine import Qwen3TtsEngine
from .runtime_probe import RuntimeProbe
from .server_client import ControlPlaneClient, ControlPlaneError


class WorkerState(StrEnum):
    CREATED = "CREATED"
    REGISTERING = "REGISTERING"
    IDLE = "IDLE"
    LEASED = "LEASED"
    GENERATING = "GENERATING"
    UPLOADING = "UPLOADING"
    WAITING_FOR_GPU = "WAITING_FOR_GPU"
    AUTH_REQUIRED = "AUTH_REQUIRED"
    QUOTA_PAUSED = "QUOTA_PAUSED"
    BACKOFF = "BACKOFF"
    FAILED = "FAILED"
    STOPPED = "STOPPED"


_WAITING_CODES = frozenset(
    {"AUTH_REQUIRED", "QUOTA_PAUSED", "WAITING_FOR_GPU"}
)
_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
EngineFactory = Callable[[ModelProfile, RuntimeProbe, Path], Any]
Sleep = Callable[[float], Awaitable[None]]


class ColabWorker:
    """在一次人工启动的 Colab 运行时中持续消费服务器任务。"""

    def __init__(
        self,
        client: ControlPlaneClient | Any,
        engine: Any | None = None,
        *,
        worker_name: str = "colab-worker",
        registry: ModelRegistry | None = None,
        runtime: RuntimeProbe | None = None,
        probe: RuntimeProbe | None = None,
        selected_model: ModelProfile | None = None,
        engine_factory: EngineFactory | None = None,
        cache_dir: Path | str = Path("/content/audiobook-cache"),
        heartbeat_interval_seconds: float = 30.0,
        claim_wait_seconds: float = 15.0,
        network_backoff_seconds: tuple[float, ...] = (15.0, 30.0, 60.0),
        sleep: Sleep | None = None,
    ) -> None:
        self.client = client
        self.engine = engine
        self.worker_name = worker_name
        self.registry = registry or ModelRegistry.default()
        self.runtime = runtime or probe
        self.selected_model = selected_model
        self.engine_factory = engine_factory or _default_engine_factory
        self.cache_dir = Path(cache_dir)
        self.heartbeat_interval_seconds = float(heartbeat_interval_seconds)
        self.claim_wait_seconds = float(claim_wait_seconds)
        self.network_backoff_seconds = tuple(network_backoff_seconds) or (15.0,)
        self._sleep = sleep or asyncio.sleep
        self.state = WorkerState.CREATED
        self.registration: Any | None = None
        self._started = False
        self._last_error: Exception | None = None

    @classmethod
    def from_environment(
        cls,
        settings: WorkerSettings | None = None,
        **kwargs: Any,
    ) -> "ColabWorker":
        settings = settings or WorkerSettings()
        if not settings.control_plane_url:
            raise ValueError("AUDIOBOOK_CONTROL_URL is required")
        if not settings.worker_token_value:
            raise ValueError("AUDIOBOOK_WORKER_TOKEN is required")
        client = ControlPlaneClient(
            str(settings.control_plane_url),
            settings.worker_token_value,
            connect_timeout=settings.control_connect_timeout_seconds,
            read_timeout=settings.control_read_timeout_seconds,
            write_timeout=settings.control_write_timeout_seconds,
        )
        return cls(
            client,
            worker_name=settings.worker_name,
            cache_dir=settings.cache_dir,
            heartbeat_interval_seconds=settings.heartbeat_interval_seconds,
            claim_wait_seconds=settings.claim_wait_seconds,
            network_backoff_seconds=(
                settings.network_backoff_base_seconds,
                settings.network_backoff_base_seconds * 2,
                settings.network_backoff_base_seconds * 4,
            ),
            **kwargs,
        )

    async def run_once(self) -> bool:
        if not await self._ensure_started():
            return False
        try:
            job = await _call_async(self.client.claim_job)
        except ControlPlaneError as error:
            if error.code in _WAITING_CODES:
                self._enter_protocol_wait(error)
                return False
            raise
        if job is None:
            self.state = WorkerState.IDLE
            return False
        return await self._process_job(job)

    async def run_forever(self, stop_event: asyncio.Event | None = None) -> None:
        event = stop_event or asyncio.Event()
        network_failures = 0
        try:
            while not event.is_set():
                try:
                    processed = await self.run_once()
                    network_failures = 0
                except (httpx.RequestError, TimeoutError) as error:
                    self._last_error = error
                    self.state = WorkerState.BACKOFF
                    delay = self.network_backoff_seconds[
                        min(network_failures, len(self.network_backoff_seconds) - 1)
                    ]
                    network_failures += 1
                    await self._wait(event, delay)
                    continue
                except ControlPlaneError as error:
                    if error.code in _WAITING_CODES:
                        self._enter_protocol_wait(error)
                        await self._wait(event, self.claim_wait_seconds)
                        continue
                    self._last_error = error
                    if error.retryable:
                        self.state = WorkerState.BACKOFF
                        delay = self.network_backoff_seconds[
                            min(
                                network_failures,
                                len(self.network_backoff_seconds) - 1,
                            )
                        ]
                        network_failures += 1
                        await self._wait(event, delay)
                        continue
                    self.state = WorkerState.FAILED
                    await self._wait(event, self.claim_wait_seconds)
                    continue
                if event.is_set():
                    break
                if processed:
                    continue
                await self._wait(event, self.claim_wait_seconds)
        finally:
            self.state = WorkerState.STOPPED
            close = getattr(self.client, "aclose", None)
            if callable(close):
                await _call_async(close)

    async def _ensure_started(self) -> bool:
        if self._started:
            return self.engine is not None
        self.runtime = self.runtime or RuntimeProbe.detect()
        if self.runtime is None or not self.runtime.cuda_available:
            self.state = WorkerState.WAITING_FOR_GPU
            return False
        if (
            self.selected_model is not None
            and self.selected_model.minimum_vram_bytes > self.runtime.gpu_memory_bytes
        ):
            self.selected_model = None
        if self.selected_model is None:
            self.selected_model = GpuSelector.select(self.registry, self.runtime)
        if self.selected_model is None:
            self.state = WorkerState.WAITING_FOR_GPU
            return False
        if self.engine is None:
            self.engine = self.engine_factory(
                self.selected_model, self.runtime, self.cache_dir
            )
            if inspect.isawaitable(self.engine):
                self.engine = await self.engine
        capabilities = _registration_capabilities(self.selected_model, self.engine)
        self.state = WorkerState.REGISTERING
        self.registration = await _call_async(
            self.client.register,
            self.worker_name,
            self.runtime,
            capabilities,
        )
        self._started = True
        self.state = WorkerState.IDLE
        return True

    async def _process_job(self, job: TtsJob) -> bool:
        if not isinstance(job, TtsJob):
            raise TypeError("claim_job must return TtsJob or None")
        self.state = WorkerState.LEASED
        heartbeat_stop = asyncio.Event()
        heartbeat_task = asyncio.create_task(
            self._heartbeat_loop(job.job_id, heartbeat_stop)
        )
        try:
            job = await self._materialize_job_reference(job)
            self.state = WorkerState.GENERATING
            await self._prepare_voice(job)
            destination = self.cache_dir / "outputs" / f"{job.job_id}.wav"
            destination.parent.mkdir(parents=True, exist_ok=True)
            result = await _call_async(
                self.engine.synthesize,
                job,
                destination,
            )
            metadata, output_path = _result_metadata(result, destination)
            self.state = WorkerState.UPLOADING
            await _call_async(
                self.client.upload_result,
                job.job_id,
                output_path,
                metadata,
            )
            self.state = WorkerState.IDLE
            return True
        except asyncio.CancelledError:
            raise
        except httpx.RequestError as error:
            self._last_error = error
            self.state = WorkerState.BACKOFF
            raise
        except ControlPlaneError as error:
            self._last_error = error
            if error.code in _WAITING_CODES:
                self._enter_protocol_wait(error)
                return False
            if error.retryable:
                self.state = WorkerState.BACKOFF
                raise
            self.state = WorkerState.FAILED
            await self._report_failure(job.job_id, _failure_code(error), error)
            return False
        except Exception as error:
            self._last_error = error
            self.state = WorkerState.FAILED
            await self._report_failure(job.job_id, _failure_code(error), error)
            return False
        finally:
            heartbeat_stop.set()
            heartbeat_task.cancel()
            try:
                await heartbeat_task
            except asyncio.CancelledError:
                pass

    async def _prepare_voice(self, job: TtsJob) -> None:
        prepare = getattr(self.engine, "prepare_voice", None)
        if not callable(prepare) or job.voice_profile is None:
            return
        if inspect.iscoroutinefunction(prepare):
            await prepare(job.voice_profile)
            return
        # 第三方适配器若仍提供同步入口，也不能占用心跳事件循环。
        result = await asyncio.to_thread(prepare, job.voice_profile)
        if inspect.isawaitable(result):
            await result

    async def _heartbeat_loop(
        self, job_id: str, stop_event: asyncio.Event
    ) -> None:
        while not stop_event.is_set():
            try:
                await _call_async(
                    self.client.heartbeat,
                    job_id,
                    {"phase": "generating", "status": "GENERATING"},
                )
            except asyncio.CancelledError:
                raise
            except ControlPlaneError as error:
                self._last_error = error
                if error.code in _WAITING_CODES:
                    self._enter_protocol_wait(error)
            except (httpx.RequestError, TimeoutError) as error:
                self._last_error = error
            await self._wait(stop_event, self.heartbeat_interval_seconds)

    async def _materialize_job_reference(self, job: TtsJob) -> TtsJob:
        profile = job.voice_profile
        if profile is None or profile.reference_audio_path is None:
            return job
        reference = Path(profile.reference_audio_path)
        if reference.exists():
            return job
        asset_info = None
        get_asset = getattr(self.client, "reference_asset", None)
        if callable(get_asset):
            asset_info = get_asset(job.job_id)
        asset_id = (
            str(asset_info.get("asset_id"))
            if isinstance(asset_info, Mapping) and asset_info.get("asset_id")
            else str(reference)
        )
        supplied_digest = (
            str(asset_info.get("sha256"))
            if isinstance(asset_info, Mapping) and asset_info.get("sha256")
            else None
        )
        digest = (
            supplied_digest.lower()
            if supplied_digest and _SHA256.fullmatch(supplied_digest)
            else hashlib.sha256(asset_id.encode("utf-8")).hexdigest()
        )
        target = self.cache_dir / "voices" / f"{digest}.wav"
        if not target.exists():
            downloaded = await _call_async(
                self.client.download_asset,
                asset_id,
                target,
            )
            target = Path(downloaded)
        if supplied_digest and _SHA256.fullmatch(supplied_digest):
            actual = _sha256_file(target)
            if actual != supplied_digest.lower():
                raise ValueError("downloaded reference audio SHA256 mismatch")
        return replace(
            job,
            voice_profile=replace(profile, reference_audio_path=target),
        )

    async def _report_failure(self, job_id: str, code: str, error: Exception) -> None:
        report = getattr(self.client, "report_failure", None)
        if not callable(report):
            return
        try:
            await _call_async(report, job_id, code, str(error))
        except Exception as report_error:
            self._last_error = report_error

    def _enter_protocol_wait(self, error: ControlPlaneError) -> None:
        self._last_error = error
        try:
            self.state = WorkerState(str(error.code))
        except ValueError:
            self.state = WorkerState.BACKOFF
        if error.code in _WAITING_CODES:
            self._started = False
            self.registration = None

    async def _wait(self, stop_event: asyncio.Event, seconds: float) -> None:
        if seconds <= 0 or stop_event.is_set():
            return
        sleep_task = asyncio.create_task(self._sleep(seconds))
        stop_task = asyncio.create_task(stop_event.wait())
        done, pending = await asyncio.wait(
            {sleep_task, stop_task},
            return_when=asyncio.FIRST_COMPLETED,
        )
        for task in pending:
            task.cancel()
        for task in done:
            if task is not stop_task:
                await task


def _default_engine_factory(
    model_profile: ModelProfile,
    runtime: RuntimeProbe,
    cache_dir: Path,
) -> Qwen3TtsEngine:
    if model_profile.engine_id != "qwen3-tts":
        raise ValueError(
            f"no Colab engine is registered for {model_profile.engine_id}"
        )
    return Qwen3TtsEngine(
        model_id=model_profile.model_id,
        device="cuda:0",
        cache_dir=cache_dir,
    )


def _registration_capabilities(
    model_profile: ModelProfile, engine: Any
) -> dict[str, Any]:
    capabilities: Any = getattr(model_profile, "capabilities", {})
    if is_dataclass(capabilities):
        capabilities = asdict(capabilities)
    return {
        "engineId": model_profile.engine_id,
        "modelId": model_profile.model_id,
        "modelVersion": model_profile.model_version,
        "minimumVramBytes": model_profile.minimum_vram_bytes,
        "priority": model_profile.priority,
        "capabilities": capabilities,
        "runtimeEngineId": getattr(engine, "engine_id", model_profile.engine_id),
    }


def _result_metadata(result: Any, destination: Path) -> tuple[dict[str, Any], Path]:
    if isinstance(result, GenerationResult):
        output_path = Path(result.output_path)
        metadata = {
            "sha256": result.sha256,
            "sizeBytes": result.size_bytes,
            "durationSeconds": result.duration_seconds,
            "sampleRate": result.sample_rate,
            "channels": result.channels,
            "format": "wav",
        }
        return metadata, output_path
    output_path = Path(result) if isinstance(result, (Path, str)) else destination
    if not output_path.is_file():
        raise ValueError("TTS engine did not return a readable output path")
    return {"sizeBytes": output_path.stat().st_size, "format": "wav"}, output_path


def _failure_code(error: Exception) -> str:
    if isinstance(error, WorkerError):
        return error.code.value
    if isinstance(error, ControlPlaneError):
        return error.code
    if isinstance(error, (asyncio.TimeoutError, TimeoutError, httpx.TimeoutException)):
        return "GENERATION_TIMEOUT"
    return "PERMANENT_FAILED"


async def _call_async(function: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
    if inspect.iscoroutinefunction(function):
        return await function(*args, **kwargs)
    result = await asyncio.to_thread(function, *args, **kwargs)
    if inspect.isawaitable(result):
        return await result
    return result


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


__all__ = ["ColabWorker", "WorkerState"]
