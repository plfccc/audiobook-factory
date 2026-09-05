import pytest
from pathlib import Path

from audiobook_worker.contracts import GenerationRequest, RuntimeStatus, TtsPreset
from audiobook_worker.errors import ErrorCode, WorkerError


def test_request_keeps_preset_snapshot_and_rejects_blank_text(tmp_path: Path):
    preset = TtsPreset(
        provider="google-ai-studio-browser",
        model="gemini-tts",
        voice="Kore",
        style_prompt="自然、克制地朗读。",
        language="zh-CN",
        output_format="wav",
    )

    request = GenerationRequest("p0-001", "第一句话。", preset, tmp_path)

    assert request.request_id == "p0-001"
    assert request.preset.voice == "Kore"
    with pytest.raises(ValueError, match="text"):
        GenerationRequest("p0-002", "   ", preset, tmp_path)


def test_contracts_reject_blank_request_id_and_unsupported_output_format(tmp_path: Path):
    with pytest.raises(ValueError, match="request_id"):
        GenerationRequest("  ", "text", TtsPreset("p", "m", "v", "", "zh", "wav"), tmp_path)
    with pytest.raises(ValueError, match="unsupported output_format"):
        TtsPreset("p", "m", "v", "", "zh", "ogg")


def test_contracts_are_immutable(tmp_path: Path):
    preset = TtsPreset("p", "m", "v", "", "zh", "wav")
    request = GenerationRequest("id", "text", preset, tmp_path)
    with pytest.raises(AttributeError):
        request.text = "changed"
    with pytest.raises(AttributeError):
        preset.voice = "changed"


def test_error_codes_and_worker_error_contract_are_stable():
    assert [code.value for code in ErrorCode] == [
        "AUTH_REQUIRED", "HUMAN_REQUIRED", "QUOTA_PAUSED", "PAGE_NOT_READY",
        "GENERATION_TIMEOUT", "DOWNLOAD_TIMEOUT", "AUDIO_INVALID", "PERMANENT_FAILED",
    ]
    error = WorkerError(ErrorCode.DOWNLOAD_TIMEOUT, "download took too long", retryable=True)
    assert str(error) == "download took too long"
    assert error.code is ErrorCode.DOWNLOAD_TIMEOUT
    assert error.retryable is True


def test_successful_runtime_status_can_have_no_error_code():
    status = RuntimeStatus(ok=True, code=None, message="ready")
    assert status.ok is True
    assert status.code is None


def test_request_hashes_are_deterministic_and_change_sensitive(tmp_path: Path):
    preset = TtsPreset("p", "m", "v", "style", "zh", "wav")
    same = GenerationRequest("a", "hello", preset, tmp_path)
    again = GenerationRequest("b", "hello", preset, tmp_path)
    changed_text = GenerationRequest("c", "hello!", preset, tmp_path)
    changed_preset = GenerationRequest("d", "hello", TtsPreset("p", "m", "other", "style", "zh", "wav"), tmp_path)
    assert same.text_sha256 == again.text_sha256
    assert same.preset_snapshot_sha256 == again.preset_snapshot_sha256
    assert same.text_sha256 != changed_text.text_sha256
    assert same.preset_snapshot_sha256 != changed_preset.preset_snapshot_sha256
