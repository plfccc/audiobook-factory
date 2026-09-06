from __future__ import annotations

import asyncio
from dataclasses import asdict
from enum import StrEnum
import hashlib
import inspect
import platform
from pathlib import Path
import sys
from typing import Any, Awaitable, Callable, Mapping

import httpx

from .ai_studio_provider import GoogleAiStudioBrowserProvider
from .artifacts import ArtifactStore
from .audio import AudioValidator
from .browser_session import BrowserSession
from .config import WorkerSettings
from .contracts import GenerationRequest, GenerationResult
from .diagnostics import Diagnostics
from .errors import ErrorCode, WorkerError
from .pipeline import P0Pipeline
from .server_client import ControlPlaneClient, ControlPlaneError, validate_job_id


class BrowserWorkerState(StrEnum):
    CREATED = "CREATED"
    REGISTERING = "REGISTERING"
    IDLE = "IDLE"
    LEASED = "LEASED"
    GENERATING = "GENERATING"
    UPLOADING = "UPLOADING"
    AUTH_REQUIRED = "AUTH_REQUIRED"
    QUOTA_PAUSED = "QUOTA_PAUSED"
    HUMAN_REQUIRED = "HUMAN_REQUIRED"
    BACKOFF = "BACKOFF"
    FAILED = "FAILED"
    STOPPED = "STOPPED"


_WAITING_CODES = frozenset(
    {"AUTH_REQUIRED", "QUOTA_PAUSED", "HUMAN_REQUIRED"}
)
_STOP_CODES = frozenset(
    {"LEASE_LOST", "WORKER_STOPPED", "WORKER_STOPPING", "STOPPED", "REVOKED"}
)
_MANUAL_PAUSE_CODES = frozenset(
    {"AUTH_REQUIRED", "QUOTA_PAUSED", "HUMAN_REQUIRED"}
)

Sleep = Callable[[float], Awaitable[None]]
SessionFactory = Callable[[str], Awaitable[Any]]
ProviderFactory = Callable[[Any, WorkerSettings, Diagnostics], Any]
PipelineFactory = Callable[[Any, WorkerSettings, Diagnostics], Any]


class BrowserWorker:
    """Consume control-plane jobs through the persistent server-side Chrome."""

    def __init__(
        self,
        client: Any,
        *,
        settings: WorkerSettings | None = None,
        session: Any | None = None,
        provider: Any | None = None,
        pipeline: Any | None = None,
        worker_name: str = "server-playwright-worker",
        runtime: Mapping[str, Any] | None = None,
        capabilities: Mapping[str, Any] | None = None,
        output_dir: Path | str | None = None,
        diagnostics: Diagnostics | None = None,
        session_factory: SessionFactory | None = None,
        provider_factory: ProviderFactory | None = None,
        pipeline_factory: PipelineFactory | None = None,
        heartbeat_interval_seconds: float = 30.0,
        claim_wait_seconds: float = 15.0,
        network_backoff_seconds: tuple[float, ...] = (15.0, 30.0, 60.0),
        sleep: Sleep | None = None,
    ) -> None:
        self.client = client
        self.settings = settings or WorkerSettings()
        self.session = session
        self.provider = provider
        self.pipeline = pipeline
        self.worker_name = worker_name
        self.runtime = dict(runtime or _server_runtime())
        self.capabilities = dict(capabilities or _browser_capabilities())
        self.output_dir = Path(output_dir or self.settings.output_dir)
        self.diagnostics = diagnostics or Diagnostics(self.settings.diagnostics_dir)
        self._session_factory = session_factory or BrowserSession.connect
        self._provider_factory = provider_factory or _default_provider_factory
        self._pipeline_factory = pipeline_factory or _default_pipeline_factory
        self.heartbeat_interval_seconds = _positive(
            heartbeat_interval_seconds, "heartbeat_interval_seconds"
        )
        self.claim_wait_seconds = _positive(claim_wait_seconds, "claim_wait_seconds")
        self.network_backoff_seconds = tuple(network_backoff_seconds) or (15.0,)
        self._sleep = sleep or asyncio.sleep
        self.state = BrowserWorkerState.CREATED
        self.registration: Any | None = None
        self._started = False
        self._halted = False
        self._manual_paused = False
        self._last_error: Exception | None = None

    @classmethod
    def from_environment(
        cls,
        settings: WorkerSettings | None = None,
        **kwargs: Any,
    ) -> "BrowserWorker":
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
            allow_insecure_http=settings.allow_insecure_http,
            download_timeout=settings.download_timeout_seconds,
        )
        return cls(client, settings=settings, **kwargs)

    async def run_once(self) -> bool:
        if self._halted or self._manual_paused:
            return False
        try:
            if not await self._ensure_started():
                return False
            job = await _call_async(self.client.claim_job)
        except ControlPlaneError as error:
            if error.code in _STOP_CODES or error.code in _WAITING_CODES:
                self._enter_protocol_wait(error)
                return False
            raise
        if job is None:
            self.state = BrowserWorkerState.IDLE
            return False
        return await self._process_job(job)

    async def run_forever(self, stop_event: asyncio.Event | None = None) -> None:
        event = stop_event or asyncio.Event()
        network_failures = 0
        try:
            while not event.is_set() and not self._halted:
                try:
                    processed = await self.run_once()
                    network_failures = 0
                except (httpx.RequestError, TimeoutError) as error:
                    self._last_error = error
                    self.state = BrowserWorkerState.BACKOFF
                    delay = self.network_backoff_seconds[
                        min(network_failures, len(self.network_backoff_seconds) - 1)
                    ]
                    network_failures += 1
                    await self._wait(event, delay)
                    continue
                except WorkerError as error:
                    self._last_error = error
                    if error.code.value in _WAITING_CODES:
                        self._enter_protocol_wait(
                            ControlPlaneError(
                                str(error),
                                code=error.code.value,
                                retryable=error.retryable,
                            )
                        )
                        break
                    self.state = BrowserWorkerState.BACKOFF
                    delay = self.network_backoff_seconds[
                        min(network_failures, len(self.network_backoff_seconds) - 1)
                    ]
                    network_failures += 1
                    await self._wait(event, delay)
                    continue
                except ControlPlaneError as error:
                    if error.code in _STOP_CODES or error.code in _WAITING_CODES:
                        self._enter_protocol_wait(error)
                        if self._halted:
                            break
                        await self._wait(event, self.claim_wait_seconds)
                        continue
                    self._last_error = error
                    if error.retryable:
                        self.state = BrowserWorkerState.BACKOFF
                        delay = self.network_backoff_seconds[
                            min(network_failures, len(self.network_backoff_seconds) - 1)
                        ]
                        network_failures += 1
                        await self._wait(event, delay)
                        continue
                    self.state = BrowserWorkerState.FAILED
                    await self._wait(event, self.claim_wait_seconds)
                    continue
                if self._halted or event.is_set():
                    break
                if processed:
                    continue
                if self._manual_paused:
                    await self._maybe_resume_manual_pause()
                await self._wait(event, self.claim_wait_seconds)
        finally:
            if not self._is_manual_pause_state():
                self.state = BrowserWorkerState.STOPPED
            await self.close()

    async def close(self) -> None:
        close_session = getattr(self.session, "close", None)
        if callable(close_session):
            try:
                await _call_async(close_session)
            except Exception:
                pass
        close_client = getattr(self.client, "aclose", None)
        if callable(close_client):
            try:
                await _call_async(close_client)
            except Exception:
                pass

    async def _ensure_started(self) -> bool:
        if self._halted:
            return False
        if self._started:
            return True

        if self.session is None:
            self.session = await _call_async(
                self._session_factory, str(self.settings.cdp_url)
            )
        if self.provider is None:
            self.provider = self._provider_factory(
                self.session, self.settings, self.diagnostics
            )
            if inspect.isawaitable(self.provider):
                self.provider = await self.provider
        if self.pipeline is None:
            self.pipeline = self._pipeline_factory(
                self.provider, self.settings, self.diagnostics
            )
            if inspect.isawaitable(self.pipeline):
                self.pipeline = await self.pipeline

        self.state = BrowserWorkerState.REGISTERING
        self.registration = await _call_async(
            self.client.register,
            self.worker_name,
            self.runtime,
            self.capabilities,
        )
        self._started = True
        self.state = BrowserWorkerState.IDLE
        return True

    async def _process_job(self, job: Any) -> bool:
        try:
            validate_job_id(job.job_id)
        except (AttributeError, ValueError) as error:
            self._last_error = error
            self.state = BrowserWorkerState.FAILED
            return False

        self.state = BrowserWorkerState.LEASED
        heartbeat_stop = asyncio.Event()
        operation_abort = asyncio.Event()
        heartbeat_task = asyncio.create_task(
            self._heartbeat_loop(job.job_id, heartbeat_stop, operation_abort)
        )
        try:
            request = GenerationRequest(
                request_id=job.job_id,
                text=job.text,
                preset=job.preset,
                output_dir=self.output_dir,
            )
            self.state = BrowserWorkerState.GENERATING
            result = await self.pipeline.run(request)
            self._ensure_operation_allowed(operation_abort)
            metadata, output_path = _result_metadata(result, self.output_dir)
            self.state = BrowserWorkerState.UPLOADING
            await _call_async(
                self.client.upload_result,
                job.job_id,
                output_path,
                metadata,
            )
            self._ensure_operation_allowed(operation_abort)
            self.state = BrowserWorkerState.IDLE
            return True
        except asyncio.CancelledError:
            raise
        except WorkerError as error:
            self._last_error = error
            code = error.code.value
            if code in _WAITING_CODES:
                self._enter_protocol_wait(
                    ControlPlaneError(str(error), code=code, retryable=error.retryable)
                )
            else:
                self.state = BrowserWorkerState.FAILED
            await self._report_failure(job.job_id, code, error)
            return False
        except ControlPlaneError as error:
            self._last_error = error
            if error.code in _STOP_CODES or error.code in _WAITING_CODES:
                self._enter_protocol_wait(error)
                return False
            if error.retryable:
                self.state = BrowserWorkerState.BACKOFF
                raise
            self.state = BrowserWorkerState.FAILED
            await self._report_failure(job.job_id, error.code, error)
            return False
        except httpx.RequestError:
            raise
        except Exception as error:
            self._last_error = error
            self.state = BrowserWorkerState.FAILED
            await self._report_failure(job.job_id, _failure_code(error), error)
            return False
        finally:
            heartbeat_stop.set()
            heartbeat_task.cancel()
            try:
                await heartbeat_task
            except asyncio.CancelledError:
                pass

    async def _heartbeat_loop(
        self,
        job_id: str,
        stop_event: asyncio.Event,
        operation_abort: asyncio.Event,
    ) -> None:
        await asyncio.sleep(min(0.01, max(0.001, self.heartbeat_interval_seconds)))
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
                if error.code in _STOP_CODES or error.code in _WAITING_CODES:
                    self._enter_protocol_wait(error)
                    operation_abort.set()
                    return
            except (httpx.RequestError, TimeoutError) as error:
                self._last_error = error
            await self._wait(stop_event, self.heartbeat_interval_seconds)

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
        code = str(error.code)
        try:
            self.state = BrowserWorkerState(code)
        except ValueError:
            self.state = BrowserWorkerState.BACKOFF
        if code in _MANUAL_PAUSE_CODES:
            self._manual_paused = True
        if code in _STOP_CODES:
            self._halted = True
            if code in _STOP_CODES:
                self.state = BrowserWorkerState.STOPPED

    async def _maybe_resume_manual_pause(self) -> bool:
        if not self._manual_paused or self.provider is None:
            return False
        health_check = getattr(self.provider, "health_check", None)
        if not callable(health_check):
            return False
        try:
            status = await _call_async(health_check)
        except Exception as error:
            self._last_error = error
            return False
        if not bool(getattr(status, "ok", False)):
            return False
        self._manual_paused = False
        self._last_error = None
        self.state = BrowserWorkerState.IDLE
        return True

    def _ensure_operation_allowed(self, operation_abort: asyncio.Event) -> None:
        if operation_abort.is_set():
            code = (
                self._last_error.code
                if isinstance(self._last_error, ControlPlaneError)
                else "WORKER_STOPPING"
            )
            raise WorkerError(ErrorCode.HUMAN_REQUIRED, str(code), retryable=False)

    def _is_manual_pause_state(self) -> bool:
        return self._manual_paused and self.state in {
            BrowserWorkerState.AUTH_REQUIRED,
            BrowserWorkerState.QUOTA_PAUSED,
            BrowserWorkerState.HUMAN_REQUIRED,
        }

    async def _wait(
        self,
        stop_event: asyncio.Event,
        seconds: float,
    ) -> None:
        if stop_event.is_set():
            return
        await self._sleep(seconds)


async def _call_async(callable_object: Callable[..., Any], *args: Any) -> Any:
    result = callable_object(*args)
    if inspect.isawaitable(result):
        return await result
    return result


def _default_provider_factory(
    session: Any, settings: WorkerSettings, diagnostics: Diagnostics
) -> GoogleAiStudioBrowserProvider:
    return GoogleAiStudioBrowserProvider(session, settings, diagnostics)


def _default_pipeline_factory(
    provider: Any, settings: WorkerSettings, diagnostics: Diagnostics
) -> P0Pipeline:
    return P0Pipeline(
        provider,
        AudioValidator(),
        ArtifactStore(),
        diagnostics,
        settings,
    )


def _server_runtime() -> dict[str, Any]:
    return {
        "cudaAvailable": False,
        "gpuName": "server-browser",
        "gpuMemoryBytes": 0,
        "cudaVersion": None,
        "torchVersion": None,
        "pythonVersion": platform.python_version(),
        "platform": platform.platform(),
    }


def _browser_capabilities() -> dict[str, Any]:
    return {
        "engineId": "google-ai-studio-browser",
        "provider": "google-ai-studio-browser",
        "model": "Google AI Studio TTS",
        "browserAutomation": True,
        "persistentChrome": True,
        "voiceDesign": True,
        "voiceClone": False,
        "languages": ["zh-CN"],
    }


def _result_metadata(result: GenerationResult, output_root: Path) -> tuple[dict[str, Any], Path]:
    if not isinstance(result, GenerationResult):
        raise WorkerError(
            ErrorCode.PERMANENT_FAILED,
            "browser pipeline returned an invalid generation result",
            retryable=False,
        )
    output_root = output_root.resolve()
    output_path = Path(result.output_path).resolve()
    try:
        output_path.relative_to(output_root)
    except ValueError as error:
        raise WorkerError(
            ErrorCode.PERMANENT_FAILED,
            "generated audio path escaped the worker output directory",
            retryable=False,
        ) from error
    if not output_path.is_file():
        raise WorkerError(
            ErrorCode.AUDIO_INVALID,
            "generated audio file is missing",
            retryable=True,
        )
    actual_size = output_path.stat().st_size
    actual_sha256 = _sha256(output_path)
    if actual_size != result.size_bytes or actual_sha256 != result.sha256:
        raise WorkerError(
            ErrorCode.AUDIO_INVALID,
            "generated audio metadata does not match the file",
            retryable=True,
        )
    return (
        {
            "codec": "wav",
            "sampleRate": result.sample_rate,
            "channels": result.channels,
            "durationSeconds": result.duration_seconds,
            "sizeBytes": result.size_bytes,
            "sha256": result.sha256,
        },
        output_path,
    )


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _failure_code(error: Exception) -> str:
    if isinstance(error, WorkerError):
        return error.code.value
    if isinstance(error, (TimeoutError, asyncio.TimeoutError)):
        return "GENERATION_TIMEOUT"
    return "PERMANENT_FAILED"


def _positive(value: float, name: str) -> float:
    if value <= 0:
        raise ValueError(f"{name} must be positive")
    return float(value)


async def run_from_environment() -> None:
    worker = BrowserWorker.from_environment()
    await worker.run_forever()


if __name__ == "__main__":
    asyncio.run(run_from_environment())
