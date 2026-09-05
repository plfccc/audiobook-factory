import asyncio
from pathlib import Path
from types import SimpleNamespace

import pytest

from audiobook_worker.audio import AudioMetadata
from audiobook_worker.contracts import (
    GenerationRequest,
    RuntimeStatus,
    TtsPreset,
)
from audiobook_worker.errors import ErrorCode, WorkerError
from audiobook_worker.pipeline import P0Pipeline


def make_request(output_dir: Path) -> GenerationRequest:
    return GenerationRequest(
        "p0-test-001",
        "这是一个测试片段。",
        TtsPreset(
            "google-ai-studio-browser",
            "gemini-tts",
            "Kore",
            "自然、克制地朗读。",
            "zh-CN",
            "wav",
        ),
        output_dir,
    )


def make_metadata() -> AudioMetadata:
    return AudioMetadata(
        codec="pcm_s16le",
        sample_rate=24_000,
        channels=1,
        duration_seconds=1.0,
        size_bytes=1_024,
        sha256="a" * 64,
    )


class FakeProvider:
    def __init__(self, temp_audio: bytes = b"valid audio"):
        self.events: list[str] = []
        self.generate_calls = 0
        self.temp_audio = temp_audio
        self.capture_calls: list[tuple[str, str]] = []

    async def check_auth(self) -> RuntimeStatus:
        self.events.append("auth")
        return RuntimeStatus(True, None, "authenticated")

    async def generate(self, request, destination: Path) -> Path:
        self.events.append("generate")
        self.generate_calls += 1
        destination.write_bytes(self.temp_audio)
        return destination

    async def capture_diagnostics(self, request_id: str, reason: str) -> None:
        self.capture_calls.append((request_id, reason))


class FakeValidator:
    def __init__(self, metadata: AudioMetadata | None = None, error=None):
        self.events: list[str] = []
        self.metadata = metadata or make_metadata()
        self.error = error

    def validate(self, path: Path) -> AudioMetadata:
        self.events.append("validate")
        if self.error is not None:
            raise self.error
        return self.metadata


class FakeArtifacts:
    def __init__(self):
        self.events: list[str] = []
        self.calls = 0

    def publish(self, temp_path: Path, request: GenerationRequest, metadata: AudioMetadata) -> Path:
        self.events.append("publish")
        self.calls += 1
        final_path = request.output_dir / f"{request.request_id}.wav"
        final_path.parent.mkdir(parents=True, exist_ok=True)
        final_path.write_bytes(temp_path.read_bytes())
        return final_path


def test_pipeline_runs_auth_generation_validation_and_publication_in_order(tmp_path):
    async def exercise():
        provider = FakeProvider()
        validator = FakeValidator()
        artifacts = FakeArtifacts()
        request = make_request(tmp_path / "output")

        result = await P0Pipeline(
            provider,
            validator,
            artifacts,
            settings=SimpleNamespace(max_attempts=1),
        ).run(request)

        assert result.request_id == request.request_id
        assert result.output_path == request.output_dir / "p0-test-001.wav"
        assert result.sha256 == "a" * 64
        assert provider.events == ["auth", "generate"]
        assert validator.events == ["validate"]
        assert artifacts.events == ["publish"]
        assert result.output_path.exists()

    asyncio.run(exercise())


def test_pipeline_does_not_publish_invalid_audio(tmp_path):
    async def exercise():
        provider = FakeProvider()
        validator = FakeValidator(
            error=WorkerError(
                ErrorCode.AUDIO_INVALID,
                "AUDIO_INVALID: broken audio",
                retryable=True,
            )
        )
        artifacts = FakeArtifacts()

        with pytest.raises(WorkerError, match="AUDIO_INVALID"):
            await P0Pipeline(
                provider,
                validator,
                artifacts,
                settings=SimpleNamespace(max_attempts=1),
            ).run(make_request(tmp_path / "output"))

        assert artifacts.calls == 0
        assert list((tmp_path / "output").glob("*.wav")) == []
        assert provider.capture_calls[0][0] == "p0-test-001"

    asyncio.run(exercise())


def test_pipeline_retries_bounded_retryable_failures(tmp_path):
    async def exercise():
        provider = FakeProvider()
        original_generate = provider.generate

        async def fail_twice(request, destination):
            if provider.generate_calls < 2:
                provider.events.append("generate")
                provider.generate_calls += 1
                raise WorkerError(
                    ErrorCode.DOWNLOAD_TIMEOUT,
                    "download timed out",
                    retryable=True,
                )
            return await original_generate(request, destination)

        provider.generate = fail_twice
        validator = FakeValidator()
        artifacts = FakeArtifacts()

        result = await P0Pipeline(
            provider,
            validator,
            artifacts,
            settings=SimpleNamespace(max_attempts=3),
        ).run(make_request(tmp_path / "output"))

        assert result.output_path.exists()
        assert provider.generate_calls == 3
        assert provider.capture_calls == []

    asyncio.run(exercise())


def test_pipeline_stops_before_generation_when_auth_is_required(tmp_path):
    async def exercise():
        provider = FakeProvider()

        async def logged_out():
            provider.events.append("auth")
            return RuntimeStatus(False, ErrorCode.AUTH_REQUIRED, "sign-in required")

        provider.check_auth = logged_out

        with pytest.raises(WorkerError) as caught:
            await P0Pipeline(
                provider,
                FakeValidator(),
                FakeArtifacts(),
                settings=SimpleNamespace(max_attempts=3),
            ).run(make_request(tmp_path / "output"))

        assert caught.value.code is ErrorCode.AUTH_REQUIRED
        assert provider.events == ["auth"]
        assert provider.generate_calls == 0

    asyncio.run(exercise())
