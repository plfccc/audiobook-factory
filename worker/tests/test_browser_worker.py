from __future__ import annotations

import asyncio
import hashlib
from pathlib import Path

from audiobook_worker.browser_worker import BrowserWorker, BrowserWorkerState
from audiobook_worker.contracts import GenerationResult, TtsJob, TtsPreset
from audiobook_worker.errors import ErrorCode, WorkerError


def run_async(awaitable):
    return asyncio.run(awaitable)


def make_job() -> TtsJob:
    return TtsJob(
        job_id="job-1",
        book_id="book-1",
        book_version_id="version-1",
        chapter_id="chapter-1",
        chapter_index=1,
        segment_index=1,
        text="这是一段用于浏览器 Worker 测试的文本。",
        preset=TtsPreset(
            provider="google-ai-studio-browser",
            model="Gemini TTS",
            voice="Kore",
            style_prompt="自然、克制地朗读。",
            language="zh-CN",
            output_format="wav",
        ),
    )


class FakeClient:
    def __init__(self, job: TtsJob | None):
        self.job = job
        self.register_calls = []
        self.claim_calls = 0
        self.heartbeat_calls = []
        self.upload_calls = []
        self.failure_calls = []

    async def register(self, worker_name, runtime, capabilities):
        self.register_calls.append((worker_name, runtime, capabilities))
        return object()

    async def claim_job(self):
        self.claim_calls += 1
        job, self.job = self.job, None
        return job

    async def heartbeat(self, job_id, progress):
        self.heartbeat_calls.append((job_id, progress))

    async def upload_result(self, job_id, audio_path, metadata):
        self.upload_calls.append((job_id, Path(audio_path), metadata))

    async def report_failure(self, job_id, code, message):
        self.failure_calls.append((job_id, code, message))

    async def aclose(self):
        return None


class FakeSession:
    async def close(self):
        return None


class FakeProvider:
    pass


class FakePipeline:
    def __init__(self, output_dir: Path):
        self.output_dir = output_dir
        self.requests = []

    async def run(self, request):
        self.requests.append(request)
        output = self.output_dir / f"{request.request_id}.wav"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(b"generated-audio")
        return GenerationResult(
            request_id=request.request_id,
            output_path=output,
            sha256=hashlib.sha256(output.read_bytes()).hexdigest(),
            size_bytes=output.stat().st_size,
            duration_seconds=1.0,
            sample_rate=24_000,
            channels=1,
        )


def test_run_once_registers_browser_claims_generates_and_uploads(tmp_path):
    client = FakeClient(make_job())
    pipeline = FakePipeline(tmp_path / "output")
    worker = BrowserWorker(
        client=client,
        session=FakeSession(),
        provider=FakeProvider(),
        pipeline=pipeline,
        output_dir=tmp_path / "output",
        heartbeat_interval_seconds=0.001,
    )

    assert run_async(worker.run_once()) is True

    assert len(client.register_calls) == 1
    assert client.register_calls[0][0] == "server-playwright-worker"
    assert client.register_calls[0][1]["cudaAvailable"] is False
    assert client.register_calls[0][2]["provider"] == "google-ai-studio-browser"
    assert pipeline.requests[0].request_id == "job-1"
    assert pipeline.requests[0].text == make_job().text
    assert client.upload_calls[0][0] == "job-1"
    assert client.upload_calls[0][2]["sha256"] == hashlib.sha256(
        client.upload_calls[0][1].read_bytes()
    ).hexdigest()
    assert client.failure_calls == []


def test_auth_required_pauses_without_reclaiming_jobs(tmp_path):
    client = FakeClient(make_job())

    class AuthRequiredPipeline:
        async def run(self, request):
            raise WorkerError(
                ErrorCode.AUTH_REQUIRED,
                "需要在浏览器中手工登录 Google",
                retryable=False,
            )

    worker = BrowserWorker(
        client=client,
        session=FakeSession(),
        provider=FakeProvider(),
        pipeline=AuthRequiredPipeline(),
        output_dir=tmp_path / "output",
    )

    assert run_async(worker.run_once()) is False
    assert worker.state is BrowserWorkerState.AUTH_REQUIRED
    assert client.claim_calls == 1
    assert client.failure_calls[0][1] == "AUTH_REQUIRED"

    assert run_async(worker.run_once()) is False
    assert client.claim_calls == 1
    assert len(client.register_calls) == 1
