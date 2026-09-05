from __future__ import annotations

import asyncio
from dataclasses import replace
import hashlib
from pathlib import Path

import pytest
import httpx

from audiobook_worker.colab_worker import ColabWorker, WorkerState
from audiobook_worker.contracts import (
    GenerationResult,
    TtsJob,
    TtsPreset,
    VoiceProfile,
)
from audiobook_worker.model_registry import ModelProfile
from audiobook_worker.runtime_probe import RuntimeProbe
from audiobook_worker.contracts import EngineCapabilities


def run_async(awaitable):
    return asyncio.run(awaitable)


def make_job(tmp_path: Path) -> TtsJob:
    reference = tmp_path / "reference.wav"
    reference.write_bytes(b"reference")
    profile = VoiceProfile("voice-1", "旁白", reference, "参考文本")
    preset = TtsPreset(
        "qwen3-tts",
        "Qwen/Qwen3-TTS-12Hz-1.7B-Base",
        "voice-1",
        "自然朗读",
        "zh-CN",
        "wav",
        voice_profile_id="voice-1",
    )
    return TtsJob(
        "job-1",
        "book-1",
        "version-1",
        "chapter-1",
        1,
        1,
        "这是一个测试片段。",
        preset,
        profile,
    )


class FakeClient:
    def __init__(self, job):
        self.job = job
        self.register_calls = []
        self.claim_calls = 0
        self.heartbeat_calls = []
        self.upload_calls = []
        self.failure_calls = []

    async def register(self, worker_name, runtime, capabilities):
        self.register_calls.append((worker_name, runtime, capabilities))
        return type("Registration", (), {"worker_id": "worker-1"})()

    async def claim_job(self):
        self.claim_calls += 1
        job, self.job = self.job, None
        return job

    async def heartbeat(self, job_id, progress):
        self.heartbeat_calls.append((job_id, progress))

    async def download_asset(self, asset_id, target):
        return Path(target)

    async def upload_result(self, job_id, audio_path, metadata):
        self.upload_calls.append((job_id, Path(audio_path), metadata))

    async def report_failure(self, job_id, code, message):
        self.failure_calls.append((job_id, code, message))


class FakeEngine:
    engine_id = "qwen3-tts"
    capabilities = EngineCapabilities(
        languages=("Chinese",),
        voice_design=False,
        voice_clone=True,
        emotion_control=False,
        duration_control=False,
    )

    def __init__(self, heartbeat_seen: asyncio.Event | None = None):
        self.prepare_calls = []
        self.synthesize_calls = []
        self.heartbeat_seen = heartbeat_seen

    async def prepare_voice(self, profile):
        self.prepare_calls.append(profile)
        if self.heartbeat_seen is not None:
            await asyncio.sleep(0)

    async def synthesize(self, job, destination):
        self.synthesize_calls.append((job, Path(destination)))
        Path(destination).write_bytes(b"wav")
        return GenerationResult(
            request_id=job.job_id,
            output_path=Path(destination),
            sha256="abc",
            size_bytes=3,
            duration_seconds=1.0,
            sample_rate=24_000,
            channels=1,
        )


def make_probe():
    return RuntimeProbe(True, "Test GPU", 16 * 1024**3, "12.4", "2.7.0", "3.11")


def make_profile():
    return ModelProfile(
        engine_id="qwen3-tts",
        model_id="Qwen/Qwen3-TTS-12Hz-1.7B-Base",
        model_version="1.0",
        minimum_vram_bytes=8 * 1024**3,
        priority=500,
        capabilities=FakeEngine.capabilities,
    )


def test_run_once_registers_prepares_generates_and_uploads(tmp_path):
    client = FakeClient(make_job(tmp_path))
    engine = FakeEngine()
    worker = ColabWorker(
        client=client,
        engine=engine,
        runtime=make_probe(),
        selected_model=make_profile(),
        cache_dir=tmp_path / "cache",
        heartbeat_interval_seconds=0.001,
    )

    assert run_async(worker.run_once()) is True

    assert len(client.register_calls) == 1
    assert client.register_calls[0][0] == worker.worker_name
    assert client.register_calls[0][1] == make_probe()
    assert engine.prepare_calls[0].profile_id == "voice-1"
    assert engine.synthesize_calls[0][0].job_id == "job-1"
    assert client.upload_calls[0][0] == "job-1"
    assert client.upload_calls[0][2]["sha256"] == "abc"
    assert client.failure_calls == []


def test_prepare_voice_is_awaited_so_heartbeat_can_run(tmp_path):
    client = FakeClient(make_job(tmp_path))
    heartbeat_seen = asyncio.Event()

    class HeartbeatClient(FakeClient):
        async def heartbeat(self, job_id, progress):
            await super().heartbeat(job_id, progress)
            heartbeat_seen.set()

    client = HeartbeatClient(client.job)

    class SlowEngine(FakeEngine):
        async def prepare_voice(self, profile):
            self.prepare_calls.append(profile)
            await asyncio.sleep(0.02)

        async def synthesize(self, job, destination):
            await asyncio.sleep(0)
            return await super().synthesize(job, destination)

    worker = ColabWorker(
        client=client,
        engine=SlowEngine(),
        runtime=make_probe(),
        selected_model=make_profile(),
        cache_dir=tmp_path / "cache",
        heartbeat_interval_seconds=0.001,
    )

    async def exercise():
        assert await worker.run_once() is True
        assert heartbeat_seen.is_set()

    run_async(exercise())


def test_run_once_enters_waiting_for_gpu_without_registering_or_claiming(tmp_path):
    client = FakeClient(None)
    worker = ColabWorker(
        client=client,
        engine=None,
        runtime=RuntimeProbe(False, None, 0, None, "2.7.0", "3.11"),
        selected_model=None,
        cache_dir=tmp_path / "cache",
    )

    assert run_async(worker.run_once()) is False
    assert worker.state is WorkerState.WAITING_FOR_GPU
    assert client.register_calls == []
    assert client.claim_calls == 0


def test_run_once_reselects_a_model_that_fits_gpu_memory(tmp_path):
    client = FakeClient(None)
    worker = ColabWorker(
        client=client,
        engine=FakeEngine(),
        runtime=RuntimeProbe(
            True, "Small GPU", 4 * 1024**3, "12.4", "2.7.0", "3.11"
        ),
        selected_model=make_profile(),
        cache_dir=tmp_path / "cache",
    )

    assert run_async(worker.run_once()) is False
    assert worker.selected_model is not None
    assert worker.selected_model.model_id == "Qwen/Qwen3-TTS-12Hz-0.6B-Base"
    assert len(client.register_calls) == 1
    assert client.claim_calls == 1


def test_run_once_downloads_remote_reference_into_sha256_voice_cache(tmp_path):
    source_job = make_job(tmp_path)
    remote_profile = replace(
        source_job.voice_profile,
        reference_audio_path=Path("asset-1"),
    )
    remote_job = replace(source_job, voice_profile=remote_profile)

    class RemoteClient(FakeClient):
        def __init__(self, job):
            super().__init__(job)
            self.download_calls = []

        def reference_asset(self, job_id):
            return {
                "asset_id": "asset-1",
                "sha256": hashlib.sha256(b"downloaded reference").hexdigest(),
            }

        async def download_asset(self, asset_id, target):
            self.download_calls.append((asset_id, Path(target)))
            Path(target).parent.mkdir(parents=True, exist_ok=True)
            Path(target).write_bytes(b"downloaded reference")
            return Path(target)

    client = RemoteClient(remote_job)
    engine = FakeEngine()
    worker = ColabWorker(
        client=client,
        engine=engine,
        runtime=make_probe(),
        selected_model=make_profile(),
        cache_dir=tmp_path / "cache",
    )

    assert run_async(worker.run_once()) is True

    expected_hash = hashlib.sha256(b"downloaded reference").hexdigest()
    assert client.download_calls == [
        ("asset-1", tmp_path / "cache" / "voices" / f"{expected_hash}.wav")
    ]
    assert engine.prepare_calls[0].reference_audio_path == (
        tmp_path / "cache" / "voices" / f"{expected_hash}.wav"
    )


def test_worker_factory_receives_qwen_model_selected_for_gpu(tmp_path):
    client = FakeClient(None)
    selected = make_profile()
    factory_calls = []

    def engine_factory(model_profile, runtime, cache_dir):
        factory_calls.append((model_profile, runtime, cache_dir))
        return FakeEngine()

    worker = ColabWorker(
        client=client,
        runtime=make_probe(),
        selected_model=selected,
        engine_factory=engine_factory,
        cache_dir=tmp_path / "cache",
    )

    run_async(worker.run_once())

    assert factory_calls[0][0] == selected
    assert factory_calls[0][1] == make_probe()
    assert factory_calls[0][2] == tmp_path / "cache"


def test_run_forever_backs_off_network_failures_at_fifteen_thirty_sixty(tmp_path):
    stop_event = asyncio.Event()
    delays = []
    client = FakeClient(None)
    attempts = 0

    async def failing_claim():
        nonlocal attempts
        attempts += 1
        raise httpx.ConnectError("offline")

    client.claim_job = failing_claim

    async def record_sleep(seconds):
        delays.append(seconds)
        if len(delays) == 3:
            stop_event.set()

    worker = ColabWorker(
        client=client,
        engine=FakeEngine(),
        runtime=make_probe(),
        selected_model=make_profile(),
        cache_dir=tmp_path / "cache",
        sleep=record_sleep,
    )

    run_async(worker.run_forever(stop_event))

    assert delays == [15.0, 30.0, 60.0]
    assert attempts == 3


def test_run_forever_backs_off_network_failure_during_result_upload(tmp_path):
    stop_event = asyncio.Event()
    delays = []
    job = make_job(tmp_path)

    class UploadFailClient(FakeClient):
        def __init__(self):
            super().__init__(None)
            self.upload_attempts = 0

        async def claim_job(self):
            self.claim_calls += 1
            return job

        async def upload_result(self, job_id, audio_path, metadata):
            self.upload_attempts += 1
            raise httpx.ConnectError("offline")

    client = UploadFailClient()

    async def record_sleep(seconds):
        delays.append(seconds)
        if len(delays) == 3:
            stop_event.set()

    worker = ColabWorker(
        client=client,
        engine=FakeEngine(),
        runtime=make_probe(),
        selected_model=make_profile(),
        cache_dir=tmp_path / "cache",
        sleep=record_sleep,
    )

    run_async(worker.run_forever(stop_event))

    assert delays == [15.0, 30.0, 60.0]
    assert client.upload_attempts == 3
    assert client.failure_calls == []


def test_run_forever_can_stop_after_no_work(tmp_path):
    stop_event = asyncio.Event()
    client = FakeClient(None)

    async def stop_after_wait(_seconds):
        stop_event.set()

    worker = ColabWorker(
        client=client,
        engine=FakeEngine(),
        runtime=make_probe(),
        selected_model=make_profile(),
        cache_dir=tmp_path / "cache",
        sleep=stop_after_wait,
    )

    run_async(worker.run_forever(stop_event))

    assert client.claim_calls == 1
