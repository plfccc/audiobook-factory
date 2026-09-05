import hashlib
import json
from pathlib import Path
import subprocess
import wave

import pytest

from audiobook_worker.audio import AudioValidator
from audiobook_worker.errors import ErrorCode, WorkerError


def write_wav(path):
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(24_000)
        wav.writeframes(b"\x00\x00" * 24_000)


def test_validate_wav_returns_metadata(tmp_path):
    path = tmp_path / "voice.wav"
    write_wav(path)

    metadata = AudioValidator().validate(path)

    assert metadata.codec == "pcm_s16le"
    assert metadata.channels == 1
    assert metadata.sample_rate == 24_000
    assert metadata.duration_seconds == pytest.approx(1.0, abs=0.05)
    assert metadata.size_bytes == path.stat().st_size
    assert metadata.sha256 == hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.mark.parametrize("case", ["missing", "too-small"])
def test_validate_rejects_missing_or_too_small_file(tmp_path, case):
    path = tmp_path / "broken.wav"
    if case == "too-small":
        path.write_bytes(b"not-a-wav")

    with pytest.raises(WorkerError, match="AUDIO_INVALID") as raised:
        AudioValidator().validate(path)

    assert raised.value.code is ErrorCode.AUDIO_INVALID
    assert raised.value.retryable is True


def test_validate_rejects_corrupt_file_larger_than_minimum(tmp_path):
    path = tmp_path / "broken.wav"
    path.write_bytes(b"not-a-wav" * 200)

    with pytest.raises(WorkerError, match="AUDIO_INVALID"):
        AudioValidator().validate(path)


@pytest.mark.parametrize(
    ("probe_payload", "reason"),
    [
        (
            {
                "format": {"duration": "0"},
                "streams": [{"codec_name": "pcm_s16le", "sample_rate": "24000", "channels": 1}],
            },
            "duration",
        ),
        ({"format": {"duration": "1.0"}, "streams": []}, "stream"),
        (
            {
                "format": {"duration": "1.0"},
                "streams": [{"codec_name": "pcm_s16le", "sample_rate": "24000"}],
            },
            "fields",
        ),
    ],
)
def test_validate_rejects_invalid_probe_metadata(tmp_path, monkeypatch, probe_payload, reason):
    path = tmp_path / "voice.wav"
    path.write_bytes(b"0" * 1024)

    def fake_run(args, **kwargs):
        assert isinstance(args, list)
        return subprocess.CompletedProcess(args, 0, stdout=json.dumps(probe_payload), stderr="")

    monkeypatch.setattr("audiobook_worker.audio.subprocess.run", fake_run)

    with pytest.raises(WorkerError, match=f"AUDIO_INVALID.*{reason}"):
        AudioValidator().validate(path)


def test_validate_rejects_decode_failure(tmp_path, monkeypatch):
    path = tmp_path / "voice.wav"
    path.write_bytes(b"0" * 1024)
    calls = []

    def fake_run(args, **kwargs):
        calls.append(args)
        if args[0] == "ffprobe":
            payload = {
                "format": {"duration": "1.0"},
                "streams": [{"codec_name": "pcm_s16le", "sample_rate": "24000", "channels": 1}],
            }
            return subprocess.CompletedProcess(args, 0, stdout=json.dumps(payload), stderr="")
        return subprocess.CompletedProcess(args, 1, stdout="", stderr="decode failed")

    monkeypatch.setattr("audiobook_worker.audio.subprocess.run", fake_run)

    with pytest.raises(WorkerError, match="AUDIO_INVALID.*decode"):
        AudioValidator().validate(path)

    assert calls[1] == ["ffmpeg", "-v", "error", "-i", str(path), "-f", "null", "-"]


@pytest.mark.parametrize("timed_out_command", ["ffprobe", "ffmpeg"])
def test_validate_maps_subprocess_timeout_to_retryable_audio_invalid(
    tmp_path, monkeypatch, timed_out_command
):
    path = tmp_path / "voice.wav"
    path.write_bytes(b"0" * 1024)

    def fake_run(args, **kwargs):
        if args[0] == timed_out_command:
            assert "timeout" in kwargs
            assert kwargs["timeout"] > 0
            assert kwargs.get("shell", False) is False
            raise subprocess.TimeoutExpired(args, kwargs["timeout"])
        payload = {
            "format": {"duration": "1.0"},
            "streams": [{"codec_name": "pcm_s16le", "sample_rate": "24000", "channels": 1}],
        }
        return subprocess.CompletedProcess(args, 0, stdout=json.dumps(payload), stderr="")

    monkeypatch.setattr("audiobook_worker.audio.subprocess.run", fake_run)

    with pytest.raises(WorkerError, match="AUDIO_INVALID.*timed out") as raised:
        AudioValidator().validate(path)

    assert raised.value.code is ErrorCode.AUDIO_INVALID
    assert raised.value.retryable is True


def test_validate_maps_hash_read_failure_to_retryable_audio_invalid(tmp_path, monkeypatch):
    path = tmp_path / "voice.wav"
    path.write_bytes(b"0" * 1024)

    def fake_run(args, **kwargs):
        payload = {
            "format": {"duration": "1.0"},
            "streams": [{"codec_name": "pcm_s16le", "sample_rate": "24000", "channels": 1}],
        }
        return subprocess.CompletedProcess(args, 0, stdout=json.dumps(payload), stderr="")

    original_open = Path.open

    def fail_audio_read(self, *args, **kwargs):
        if self == path:
            raise OSError("hash read failed")
        return original_open(self, *args, **kwargs)

    monkeypatch.setattr("audiobook_worker.audio.subprocess.run", fake_run)
    monkeypatch.setattr(Path, "open", fail_audio_read)

    with pytest.raises(WorkerError, match="AUDIO_INVALID.*hash") as raised:
        AudioValidator().validate(path)

    assert raised.value.code is ErrorCode.AUDIO_INVALID
    assert raised.value.retryable is True
    assert isinstance(raised.value.__cause__, OSError)
