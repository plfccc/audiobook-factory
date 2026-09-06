from __future__ import annotations

import asyncio
import contextlib
from dataclasses import asdict, is_dataclass, replace
from enum import StrEnum
import hashlib
import inspect
from pathlib import Path
import re
from typing import Any, Awaitable, Callable, Mapping

import httpx

from .config import WorkerSettings
from .contracts import GenerationResult, TtsJob
from .errors import WorkerError
from .gpu_selector import GpuSelector
from .model_registry import ModelProfile, ModelRegistry
from .qwen_engine import Qwen3TtsEngine
from .runtime_probe import RuntimeProbe
from .server_client import ControlPlaneClient, ControlPlaneError, validate_job_id


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
    HUMAN_REQUIRED = "HUMAN_REQUIRED"
    BACKOFF = "BACKOFF"
    FAILED = "FAILED"
    STOPPED = "STOPPED"


_WAITING_CODES = frozenset(
    {"AUTH_REQUIRED", "QUOTA_PAUSED", "HUMAN_REQUIRED", "WAITING_FOR_GPU"}
)
_MANUAL_PAUSE_CODES = frozenset(
    {"AUTH_REQUIRED", "QUOTA_PAUSED", "HUMAN_REQUIRED"}
)
_STOP_CODES = frozenset(
    {
        "LEASE_LOST",
        "WORKER_STOPPED",
        "WORKER_STOPPING",
        "STOPPED",
        "WORKER_DISABLED",
        "REVOKED",
    }
)
_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")
EngineFactory = Callable[[ModelProfile, RuntimeProbe, Path], Any]
Sleep = Callable[[float], Awaitable[None]]


class _TaskFailure(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


class _OperationAborted(_TaskFailure):
    pass


class _OperationTimedOut(_TaskFailure):
    pass


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
        generation_timeout_seconds: float = 180.0,
        download_timeout_seconds: float = 60.0,
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
        self.generation_timeout_seconds = _positive_timeout(
            generation_timeout_seconds, "generation_timeout_seconds"
        )
        self.download_timeout_seconds = _positive_timeout(
            download_timeout_seconds, "download_timeout_seconds"
        )
        self._sleep = sleep or asyncio.sleep
        self.state = WorkerState.CREATED
        self.registration: Any | None = None
        self._started = False
        self._halted = False
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
            download_timeout=settings.download_timeout_seconds,
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
            generation_timeout_seconds=settings.generation_timeout_seconds,
            download_timeout_seconds=settings.download_timeout_seconds,
            **kwargs,
        )

    async def run_once(self) -> bool:
        if self._halted:
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
            self.state = WorkerState.IDLE
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
                    self.state = WorkerState.BACKOFF
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
                if self._halted:
                    break
                if event.is_set():
                    break
                if processed:
                    continue
                await self._wait(event, self.claim_wait_seconds)
        finally:
            if not self._is_manual_pause_state():
                self.state = WorkerState.STOPPED
            close = getattr(self.client, "aclose", None)
            if callable(close):
                await _call_async(close)

    async def _ensure_started(self) -> bool:
        if self._halted:
            return False
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

        try:
            self._validate_job_for_worker(job)
        except _TaskFailure as error:
            self._last_error = error
            self.state = WorkerState.FAILED
            await self._report_failure(job.job_id, error.code, error)
            return False

        self.state = WorkerState.LEASED
        heartbeat_stop = asyncio.Event()
        operation_abort = asyncio.Event()
        heartbeat_task = asyncio.create_task(
            self._heartbeat_loop(job.job_id, heartbeat_stop, operation_abort)
        )
        try:
            job = await self._materialize_job_reference(job, operation_abort)
            self._ensure_operation_allowed(operation_abort)
            self.state = WorkerState.GENERATING
            await self._run_guarded(
                lambda: self._prepare_voice(job),
                operation_abort,
                timeout_seconds=self.generation_timeout_seconds,
                timeout_code="GENERATION_TIMEOUT",
            )
            destination = self._output_path(job.job_id)
            result = await self._run_guarded(
                lambda: _call_async(
                    self.engine.synthesize,
                    job,
                    destination,
                ),
                operation_abort,
                timeout_seconds=self.generation_timeout_seconds,
                timeout_code="GENERATION_TIMEOUT",
            )
            self._ensure_operation_allowed(operation_abort)
            metadata, output_path = _result_metadata(result, destination)
            output_path = _validate_output_path(output_path, self._output_root())
            _validate_result_metadata(metadata, output_path)
            self.state = WorkerState.UPLOADING
            await self._run_guarded(
                lambda: _call_async(
                    self.client.upload_result,
                    job.job_id,
                    output_path,
                    metadata,
                ),
                operation_abort,
            )
            self._ensure_operation_allowed(operation_abort)
            self.state = WorkerState.IDLE
            return True
        except asyncio.CancelledError:
            raise
        except _OperationAborted:
            return False
        except _TaskFailure as error:
            self._last_error = error
            self.state = WorkerState.FAILED
            if not self._halted:
                await self._report_failure(job.job_id, error.code, error)
            return False
        except httpx.RequestError as error:
            self._last_error = error
            self.state = WorkerState.BACKOFF
            raise
        except WorkerError as error:
            self._last_error = error
            code = error.code.value
            if code in _WAITING_CODES:
                self._enter_protocol_wait(
                    ControlPlaneError(
                        str(error), code=code, retryable=error.retryable
                    )
                )
                await self._report_failure(job.job_id, code, error)
                return False
            self.state = WorkerState.FAILED
            await self._report_failure(job.job_id, code, error)
            return False
        except ControlPlaneError as error:
            self._last_error = error
            if error.code in _WAITING_CODES:
                self._enter_protocol_wait(error)
                return False
            if error.code in _STOP_CODES:
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
        # 同步适配器必须卸载到线程，不能阻塞心跳事件循环。
        result = await asyncio.to_thread(prepare, job.voice_profile)
        if inspect.isawaitable(result):
            await result

    async def _heartbeat_loop(
        self,
        job_id: str,
        stop_event: asyncio.Event,
        operation_abort: asyncio.Event | None = None,
    ) -> None:
        operation_abort = operation_abort or asyncio.Event()
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
                if error.code in _STOP_CODES:
                    self._enter_protocol_wait(error)
                    operation_abort.set()
                    return
                if error.code in _WAITING_CODES:
                    self._enter_protocol_wait(error)
                    operation_abort.set()
                    return
            except (httpx.RequestError, TimeoutError) as error:
                self._last_error = error
            if operation_abort.is_set():
                return
            await self._wait(
                stop_event,
                self.heartbeat_interval_seconds,
                sleep=asyncio.sleep,
            )

    async def _materialize_job_reference(
        self,
        job: TtsJob,
        operation_abort: asyncio.Event | None = None,
    ) -> TtsJob:
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
            if inspect.isawaitable(asset_info):
                asset_info = await asset_info
        if not isinstance(asset_info, Mapping):
            raise _TaskFailure(
                "INVALID_ASSET_SHA256",
                "remote reference asset metadata is missing",
            )
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
        if (
            not isinstance(supplied_digest, str)
            or not _SHA256.fullmatch(supplied_digest)
        ):
            raise _TaskFailure(
                "INVALID_ASSET_SHA256",
                "remote reference asset must include a valid SHA256",
            )
        if not asset_id.strip():
            raise _TaskFailure(
                "INVALID_ASSET_SHA256",
                "remote reference asset id must not be blank",
            )
        digest = supplied_digest.lower()
        voice_root = self.cache_dir.resolve() / "voices"
        voice_root.mkdir(parents=True, exist_ok=True)
        target = voice_root / f"{digest}.wav"
        _assert_path_inside(target, voice_root, "reference cache path")
        if target.exists():
            if not target.is_file():
                raise _TaskFailure(
                    "INVALID_ASSET_SHA256",
                    "reference cache entry is not a file",
                )
            if _sha256_file(target) != digest:
                target.unlink(missing_ok=True)
        if not target.exists():
            download = getattr(self.client, "download_asset", None)
            if not callable(download):
                raise _TaskFailure(
                    "INVALID_ASSET_SHA256",
                    "client cannot download the remote reference asset",
                )
            abort = operation_abort or asyncio.Event()
            try:
                downloaded = await self._run_guarded(
                    lambda: _call_async(download, asset_id, target),
                    abort,
                    timeout_seconds=self.download_timeout_seconds,
                    timeout_code="DOWNLOAD_TIMEOUT",
                )
            except _OperationTimedOut:
                raise
            except (asyncio.TimeoutError, TimeoutError, httpx.TimeoutException) as error:
                raise _OperationTimedOut(
                    "DOWNLOAD_TIMEOUT",
                    "reference audio download timed out",
                ) from error
            try:
                downloaded_path = Path(downloaded)
            except (TypeError, ValueError) as error:
                raise _TaskFailure(
                    "INVALID_ASSET_SHA256",
                    "client returned an invalid reference path",
                ) from error
            if downloaded_path.resolve() != target.resolve():
                raise _TaskFailure(
                    "INVALID_ASSET_SHA256",
                    "downloaded reference path does not match its cache key",
                )
        if not target.is_file() or _sha256_file(target) != digest:
            raise _TaskFailure(
                "INVALID_ASSET_SHA256",
                "downloaded reference audio SHA256 mismatch",
            )
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
        except ControlPlaneError as report_error:
            self._last_error = report_error
            if report_error.code in _WAITING_CODES or report_error.code in _STOP_CODES:
                self._enter_protocol_wait(report_error)
        except Exception as report_error:
            self._last_error = report_error

    def _enter_protocol_wait(self, error: ControlPlaneError) -> None:
        self._last_error = error
        code = str(error.code)
        if code in _STOP_CODES:
            self._halted = True
            self.state = WorkerState.STOPPED
            return
        try:
            self.state = WorkerState(code)
        except ValueError:
            self.state = WorkerState.BACKOFF
        if code in _MANUAL_PAUSE_CODES:
            self._halted = True
            return
        if code == "WAITING_FOR_GPU":
            self._started = False
            self.registration = None

    def _validate_job_for_worker(self, job: TtsJob) -> None:
        try:
            validate_job_id(job.job_id)
        except ValueError as error:
            raise _TaskFailure(
                "INVALID_JOB_ID", "job_id is not a safe path component"
            ) from error
        if self.selected_model is None:
            raise _TaskFailure(
                "MODEL_NOT_COMPATIBLE",
                "worker has no compatible model selected",
            )
        if job.preset.model != self.selected_model.model_id:
            raise _TaskFailure(
                "MODEL_NOT_COMPATIBLE",
                "job preset model does not match the selected GPU model",
            )

    def _output_root(self) -> Path:
        root = self.cache_dir.resolve() / "outputs"
        root.mkdir(parents=True, exist_ok=True)
        return root

    def _output_path(self, job_id: str) -> Path:
        validate_job_id(job_id)
        root = self._output_root()
        destination = root / f"{job_id}.wav"
        _assert_path_inside(destination, root, "output path")
        return destination

    def _ensure_operation_allowed(self, operation_abort: asyncio.Event) -> None:
        if operation_abort.is_set():
            raise _OperationAborted(
                self._abort_code(),
                "worker operation was stopped by the control protocol",
            )

    def _abort_code(self) -> str:
        if isinstance(self._last_error, ControlPlaneError):
            return str(self._last_error.code)
        return "WORKER_STOPPED" if self._halted else "WORKER_STOPPING"

    def _is_manual_pause_state(self) -> bool:
        return self._halted and self.state in {
            WorkerState.AUTH_REQUIRED,
            WorkerState.QUOTA_PAUSED,
            WorkerState.HUMAN_REQUIRED,
        }

    async def _run_guarded(
        self,
        operation_factory: Callable[[], Awaitable[Any]],
        operation_abort: asyncio.Event,
        *,
        timeout_seconds: float | None = None,
        timeout_code: str = "GENERATION_TIMEOUT",
    ) -> Any:
        operation_task = asyncio.create_task(operation_factory())
        abort_task = asyncio.create_task(operation_abort.wait())
        timer_task = (
            asyncio.create_task(asyncio.sleep(timeout_seconds))
            if timeout_seconds is not None
            else None
        )
        watched = {operation_task, abort_task}
        if timer_task is not None:
            watched.add(timer_task)
        try:
            done, _ = await asyncio.wait(
                watched,
                return_when=asyncio.FIRST_COMPLETED,
            )
            if abort_task in done and operation_abort.is_set():
                await _cancel_task(operation_task)
                raise _OperationAborted(
                    self._abort_code(),
                    "worker operation was stopped by the control protocol",
                )
            if operation_task in done:
                return await operation_task
            if timer_task is not None and timer_task in done:
                await _cancel_task(operation_task)
                raise _OperationTimedOut(
                    timeout_code,
                    f"worker operation exceeded {timeout_code.lower()}",
                )
            return await operation_task
        except asyncio.CancelledError:
            await _cancel_task(operation_task)
            raise
        finally:
            for task in (abort_task, timer_task):
                if task is not None and not task.done():
                    task.cancel()
            pending = [
                task
                for task in (abort_task, timer_task)
                if task is not None and not task.done()
            ]
            if pending:
                await asyncio.gather(*pending, return_exceptions=True)

    async def _wait(
        self,
        stop_event: asyncio.Event,
        seconds: float,
        *,
        sleep: Sleep | None = None,
    ) -> None:
        if seconds <= 0 or stop_event.is_set():
            return
        sleep_task = asyncio.create_task((sleep or self._sleep)(seconds))
        stop_task = asyncio.create_task(stop_event.wait())
        done, pending = await asyncio.wait(
            {sleep_task, stop_task},
            return_when=asyncio.FIRST_COMPLETED,
        )
        for task in pending:
            task.cancel()
        if pending:
            await asyncio.gather(*pending, return_exceptions=True)
        for task in done:
            if task is not stop_task:
                await task


def _default_engine_factory(
    model_profile: ModelProfile,
    runtime: RuntimeProbe,
    cache_dir: Path,
) -> Any:
    if model_profile.engine_id == "qwen3-tts":
        return Qwen3TtsEngine(model_id=model_profile.model_id, device="cuda:0", cache_dir=cache_dir)
    if model_profile.engine_id == "cosyvoice3":
        from .cosyvoice_engine import CosyVoice3Engine
        engine = CosyVoice3Engine(device="cuda:0", model_path=cache_dir / model_profile.engine_id)
        return _validate_registered_engine(engine, model_profile)
    if model_profile.engine_id == "indextts-2.5":
        from .indextts_engine import IndexTts25Engine
        return _validate_registered_engine(IndexTts25Engine(device="cuda:0", model_path=cache_dir / model_profile.engine_id), model_profile)
    if model_profile.engine_id == "f5-tts":
        from .f5_engine import F5TtsEngine
        return _validate_registered_engine(F5TtsEngine(device="cuda:0", model_path=cache_dir / model_profile.engine_id), model_profile)
    raise ValueError(f"no Colab engine is registered for {model_profile.engine_id}")


def _validate_registered_engine(engine: Any, profile: ModelProfile) -> Any:
    if (getattr(engine, "engine_id", None), getattr(engine, "model_id", None),
            getattr(engine, "model_version", None)) != (
                profile.engine_id, profile.model_id, profile.model_version
            ):
        raise ValueError(
            f"registered engine identity does not match model profile: {profile.model_id}"
        )
    return engine


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


def _validate_result_metadata(metadata: Mapping[str, Any], output_path: Path) -> None:
    declared_sha256 = metadata.get("sha256")
    if declared_sha256 is None:
        return
    if not isinstance(declared_sha256, str) or not _SHA256.fullmatch(declared_sha256):
        raise _TaskFailure(
            "INVALID_RESULT_SHA256",
            "generated result must include a valid SHA256",
        )
    if _sha256_file(output_path) != declared_sha256.lower():
        raise _TaskFailure(
            "INVALID_RESULT_SHA256",
            "generated result SHA256 does not match audio",
        )


def _failure_code(error: Exception) -> str:
    if isinstance(error, _TaskFailure):
        return error.code
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


def _positive_timeout(value: float, name: str) -> float:
    result = float(value)
    if result <= 0:
        raise ValueError(f"{name} must be positive")
    return result


async def _cancel_task(task: asyncio.Task[Any]) -> None:
    if not task.done():
        task.cancel()
    with contextlib.suppress(asyncio.CancelledError, Exception):
        await task


def _assert_path_inside(path: Path, root: Path, label: str) -> None:
    candidate = path.resolve()
    root_path = root.resolve()
    try:
        candidate.relative_to(root_path)
    except ValueError as error:
        raise _TaskFailure(
            "OUTPUT_PATH_INVALID" if label == "output path" else "INVALID_ASSET_SHA256",
            f"{label} must stay inside the worker directory",
        ) from error


def _validate_output_path(path: Path, root: Path) -> Path:
    output_path = Path(path)
    _assert_path_inside(output_path, root, "output path")
    if not output_path.is_file():
        raise _TaskFailure(
            "OUTPUT_PATH_INVALID",
            "TTS engine did not return a readable output path",
        )
    return output_path


__all__ = ["ColabWorker", "WorkerState"]
