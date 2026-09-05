import hashlib
import json
from pathlib import Path
import wave

import pytest

from audiobook_worker.artifacts import ArtifactStore
from audiobook_worker.audio import AudioMetadata, AudioValidator
from audiobook_worker.contracts import GenerationRequest, TtsPreset


def write_wav(path: Path, frame: bytes = b"\x00\x00") -> None:
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(24_000)
        wav.writeframes(frame * 24_000)


def make_request(output_dir: Path, *, request_id: str = "p0-001", text: str = "第一句话。") -> GenerationRequest:
    preset = TtsPreset(
        provider="google-ai-studio-browser",
        model="gemini-tts",
        voice="Kore",
        style_prompt="自然、克制地朗读。",
        language="zh-CN",
        output_format="wav",
    )
    return GenerationRequest(request_id, text, preset, output_dir)


def test_publish_moves_validated_wav_and_writes_manifest(tmp_path):
    download_dir = tmp_path / "downloads"
    output_dir = tmp_path / "output"
    download_dir.mkdir()
    temp_path = download_dir / "download.wav"
    write_wav(temp_path)
    metadata = AudioValidator().validate(temp_path)
    request = make_request(output_dir)

    published = ArtifactStore().publish(temp_path, request, metadata)

    assert published == output_dir / "p0-001.wav"
    assert published.read_bytes().startswith(b"RIFF")
    assert not temp_path.exists()
    manifest = json.loads((output_dir / "p0-001.json").read_text(encoding="utf-8"))
    assert manifest == {
        "request_id": "p0-001",
        "text_sha256": request.text_sha256,
        "preset_snapshot_sha256": request.preset_snapshot_sha256,
        "metadata": {
            "codec": "pcm_s16le",
            "sample_rate": 24_000,
            "channels": 1,
            "duration_seconds": pytest.approx(1.0, abs=0.05),
            "size_bytes": published.stat().st_size,
            "sha256": hashlib.sha256(published.read_bytes()).hexdigest(),
        },
    }
    assert list(output_dir.glob(".*.tmp")) == []


def test_publish_returns_matching_existing_artifact_idempotently(tmp_path):
    output_dir = tmp_path / "output"
    first_temp = tmp_path / "first.wav"
    second_temp = tmp_path / "second.wav"
    write_wav(first_temp)
    write_wav(second_temp, b"\x01\x00")
    metadata = AudioValidator().validate(first_temp)
    second_metadata = AudioValidator().validate(second_temp)
    request = make_request(output_dir)
    store = ArtifactStore()
    first = store.publish(first_temp, request, metadata)

    second = store.publish(second_temp, request, second_metadata)

    assert second == first
    assert second_temp.exists()
    assert hashlib.sha256(first.read_bytes()).hexdigest() == metadata.sha256
    assert hashlib.sha256(second_temp.read_bytes()).hexdigest() != metadata.sha256


@pytest.mark.parametrize(
    "conflict",
    ["different-request", "tampered-audio", "audio-without-manifest", "manifest-without-audio"],
)
def test_publish_refuses_to_overwrite_unrelated_or_invalid_existing_artifacts(tmp_path, conflict):
    output_dir = tmp_path / "output"
    first_temp = tmp_path / "first.wav"
    candidate = tmp_path / "candidate.wav"
    write_wav(first_temp)
    write_wav(candidate)
    metadata = AudioValidator().validate(candidate)
    store = ArtifactStore()
    original_request = make_request(output_dir)

    if conflict == "manifest-without-audio":
        output_dir.mkdir()
        (output_dir / "p0-001.json").write_text("{}", encoding="utf-8")
    else:
        store.publish(first_temp, original_request, AudioValidator().validate(first_temp))
        if conflict == "different-request":
            request = make_request(output_dir, text="不同文本。")
        else:
            request = original_request
        if conflict == "tampered-audio":
            (output_dir / "p0-001.wav").write_bytes(b"tampered")
        elif conflict == "audio-without-manifest":
            (output_dir / "p0-001.json").unlink()
        with pytest.raises(FileExistsError):
            store.publish(candidate, request, metadata)
        assert candidate.exists()
        return

    with pytest.raises(FileExistsError):
        store.publish(candidate, original_request, metadata)
    assert candidate.exists()


def test_publish_rejects_metadata_that_does_not_match_source(tmp_path):
    output_dir = tmp_path / "output"
    temp_path = tmp_path / "download.wav"
    write_wav(temp_path)
    metadata = AudioValidator().validate(temp_path)
    wrong_metadata = AudioMetadata(
        codec=metadata.codec,
        sample_rate=metadata.sample_rate,
        channels=metadata.channels,
        duration_seconds=metadata.duration_seconds,
        size_bytes=metadata.size_bytes,
        sha256="0" * 64,
    )

    with pytest.raises(ValueError, match="metadata"):
        ArtifactStore().publish(temp_path, make_request(output_dir), wrong_metadata)

    assert temp_path.exists()
    assert not output_dir.exists()


@pytest.mark.parametrize("request_id", ["../escape", "nested/name", ".", ".."])
def test_publish_rejects_unsafe_request_id(tmp_path, request_id):
    temp_path = tmp_path / "download.wav"
    write_wav(temp_path)
    metadata = AudioValidator().validate(temp_path)

    with pytest.raises(ValueError, match="request_id"):
        ArtifactStore().publish(temp_path, make_request(tmp_path / "output", request_id=request_id), metadata)

    assert temp_path.exists()
