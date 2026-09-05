from pathlib import Path
import os
import tempfile
from typing import Any

from .artifacts import ArtifactStore
from .audio import AudioMetadata, AudioValidator
from .config import WorkerSettings
from .contracts import GenerationRequest, GenerationResult, RuntimeStatus
from .diagnostics import Diagnostics
from .errors import ErrorCode, WorkerError
from .logging import emit_event
from .provider import TtsProvider


_HUMAN_ACTION_CODES = frozenset(
    {ErrorCode.AUTH_REQUIRED, ErrorCode.QUOTA_PAUSED, ErrorCode.HUMAN_REQUIRED}
)


class P0Pipeline:
    def __init__(
        self,
        provider: TtsProvider,
        validator: AudioValidator | Any | None = None,
        artifacts: ArtifactStore | Any | None = None,
        diagnostics: Diagnostics | Any | None = None,
        settings: WorkerSettings | Any | None = None,
    ):
        self.provider = provider
        self.validator = validator or AudioValidator()
        self.artifacts = artifacts or ArtifactStore()
        self.diagnostics = diagnostics
        self.settings = settings or WorkerSettings()

    async def run(self, request: GenerationRequest) -> GenerationResult:
        auth_status = await self.provider.check_auth()
        if not auth_status.ok:
            error = _status_error(auth_status)
            await self._capture_failure(request, error, "auth")
            emit_event(
                "failed",
                request_id=request.request_id,
                phase="auth",
                error_code=error.code,
            )
            raise error

        max_attempts = int(self.settings.max_attempts)
        if max_attempts <= 0:
            raise ValueError("max_attempts must be positive")

        for attempt in range(1, max_attempts + 1):
            temporary_path = _temporary_download_path(request.output_dir)
            try:
                emit_event(
                    "started",
                    request_id=request.request_id,
                    phase="generate",
                    attempt=attempt,
                    max_attempts=max_attempts,
                )
                generated_path = Path(
                    await self.provider.generate(request, temporary_path)
                )
                metadata = self.validator.validate(generated_path)
                published_path = self.artifacts.publish(
                    generated_path, request, metadata
                )
                result = _result_from_metadata(
                    request.request_id, published_path, metadata
                )
                emit_event(
                    "succeeded",
                    request_id=request.request_id,
                    phase="publish",
                    output_path=published_path,
                    attempt=attempt,
                    max_attempts=max_attempts,
                )
                return result
            except WorkerError as error:
                final_failure = not error.retryable or attempt >= max_attempts
                if final_failure:
                    await self._capture_failure(request, error, "generate")
                    emit_event(
                        "failed",
                        request_id=request.request_id,
                        phase="generate",
                        error_code=error.code,
                        attempt=attempt,
                        max_attempts=max_attempts,
                    )
                    raise
                emit_event(
                    "retrying",
                    request_id=request.request_id,
                    phase="generate",
                    error_code=error.code,
                    attempt=attempt,
                    max_attempts=max_attempts,
                )
            except (OSError, TypeError, ValueError) as exc:
                error = WorkerError(
                    ErrorCode.PERMANENT_FAILED,
                    "P0 pipeline failed while processing the generated artifact",
                    retryable=False,
                )
                await self._capture_failure(request, error, "publish")
                emit_event(
                    "failed",
                    request_id=request.request_id,
                    phase="publish",
                    error_code=error.code,
                    attempt=attempt,
                    max_attempts=max_attempts,
                )
                raise error from exc
            finally:
                try:
                    temporary_path.unlink(missing_ok=True)
                except OSError:
                    pass

        raise AssertionError("P0 pipeline exhausted without a result or error")

    async def _capture_failure(
        self, request: GenerationRequest, error: WorkerError, phase: str
    ) -> None:
        should_capture = error.code in _HUMAN_ACTION_CODES or error.retryable
        if not should_capture:
            return

        capture = getattr(self.provider, "capture_diagnostics", None)
        if callable(capture):
            try:
                await capture(
                    request.request_id,
                    f"{phase}:{error.code.value}",
                )
                return
            except Exception:
                return

        page = getattr(self.provider, "page", None)
        if page is not None and self.diagnostics is not None:
            try:
                await self.diagnostics.capture(
                    page,
                    request.request_id,
                    f"{phase}:{error.code.value}",
                )
            except Exception:
                pass


def _status_error(status: RuntimeStatus) -> WorkerError:
    code = status.code or ErrorCode.HUMAN_REQUIRED
    retryable = code not in _HUMAN_ACTION_CODES
    return WorkerError(code, status.message, retryable=retryable)


def _temporary_download_path(output_dir: Path) -> Path:
    output_dir = Path(output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    os.chmod(output_dir, 0o700)
    descriptor, name = tempfile.mkstemp(
        prefix=".p0-download-",
        suffix=".wav",
        dir=output_dir,
    )
    os.close(descriptor)
    return Path(name)


def _result_from_metadata(
    request_id: str, output_path: Path, metadata: AudioMetadata
) -> GenerationResult:
    return GenerationResult(
        request_id=request_id,
        output_path=Path(output_path),
        sha256=metadata.sha256,
        size_bytes=metadata.size_bytes,
        duration_seconds=metadata.duration_seconds,
        sample_rate=metadata.sample_rate,
        channels=metadata.channels,
    )
