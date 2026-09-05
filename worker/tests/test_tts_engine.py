from dataclasses import asdict
import inspect
from pathlib import Path

import pytest

from audiobook_worker.contracts import (
    EngineCapabilities,
    PreparedVoice,
    TtsJob,
    TtsPreset,
    VoiceProfile,
)
from audiobook_worker.tts_engine import TtsEngine


def _preset(**overrides):
    values = {
        "provider": "qwen3-tts",
        "model": "Qwen/Qwen3-TTS-12Hz-1.7B-Base",
        "voice": "voice-1",
        "style_prompt": "自然朗读。",
        "language": "zh-CN",
        "output_format": "wav",
    }
    values.update(overrides)
    return TtsPreset(**values)


def _job(**overrides):
    values = {
        "job_id": "job-1",
        "book_id": "book-1",
        "book_version_id": "book-version-1",
        "chapter_id": "chapter-1",
        "chapter_index": 1,
        "segment_index": 1,
        "text": "第一句话。",
        "preset": _preset(),
    }
    values.update(overrides)
    return TtsJob(**values)


def test_tts_contracts_keep_voice_and_job_metadata():
    capabilities = EngineCapabilities(
        languages=("zh-CN", "en-US"),
        voice_design=True,
        voice_clone=True,
        emotion_control=False,
        duration_control=False,
    )
    profile = VoiceProfile(
        "voice-1",
        "旁白",
        Path("reference.wav"),
        "参考文本。",
        "温和、克制。",
    )
    prepared = PreparedVoice(
        "voice-1",
        "voice-1:cache-key",
        profile.reference_audio_path,
        profile.reference_text,
        profile.design_prompt,
        "clone prompt kept in worker memory",
    )
    preset = TtsPreset(
        "qwen3-tts",
        "Qwen/Qwen3-TTS-12Hz-1.7B-Base",
        "voice-1",
        "自然朗读。",
        "zh-CN",
        "wav",
    )
    job = TtsJob(
        "job-1",
        "book-1",
        "book-version-1",
        "chapter-1",
        1,
        1,
        "第一句话。",
        preset,
        profile,
    )

    assert capabilities.languages == ("zh-CN", "en-US")
    assert prepared.clone_prompt == "clone prompt kept in worker memory"
    assert job.voice_profile is profile
    assert job.preset.segment_target_chars == 220
    assert job.preset.segment_max_chars == 320


def test_tts_engine_protocol_exposes_shared_operations():
    assert TtsEngine.__annotations__["engine_id"] is str
    assert "capabilities" in TtsEngine.__annotations__
    assert "probe" in TtsEngine.__annotations__
    assert callable(TtsEngine.prepare_voice)
    assert callable(TtsEngine.synthesize)
    assert inspect.iscoroutinefunction(TtsEngine.prepare_voice)
    assert inspect.iscoroutinefunction(TtsEngine.synthesize)


def test_tts_contracts_are_immutable():
    profile = VoiceProfile("voice-1", "旁白")

    with pytest.raises(AttributeError):
        profile.name = "changed"


def test_legacy_tts_preset_constructor_uses_new_defaults():
    preset = TtsPreset("provider", "model", "voice", "", "zh-CN", "wav")

    assert preset.model_version == "unspecified"
    assert preset.voice_profile_id is None
    assert preset.parameters_json == "{}"
    assert preset.segment_target_chars == 220
    assert preset.segment_max_chars == 320


def test_tts_preset_rejects_invalid_json_and_segment_ranges():
    with pytest.raises(ValueError, match="parameters_json"):
        _preset(parameters_json="not-json")
    with pytest.raises(ValueError, match="segment_target_chars"):
        _preset(segment_target_chars=0)
    with pytest.raises(ValueError, match="segment_max_chars"):
        _preset(segment_max_chars=0)
    with pytest.raises(ValueError, match="segment_target_chars"):
        _preset(segment_target_chars=321, segment_max_chars=320)


def test_voice_profile_rejects_blank_profile_id():
    with pytest.raises(ValueError, match="profile_id"):
        VoiceProfile(" ", "旁白")


@pytest.mark.parametrize("field", ["profile_id", "cache_key"])
def test_prepared_voice_rejects_blank_memory_ids(field):
    values = {"profile_id": "voice-1", "cache_key": "cache-key"}
    values[field] = " "
    with pytest.raises(ValueError, match=field):
        PreparedVoice(**values)


def test_tts_preset_rejects_blank_voice_profile_id():
    with pytest.raises(ValueError, match="voice_profile_id"):
        _preset(voice_profile_id=" ")


@pytest.mark.parametrize("field", ["job_id", "book_id", "book_version_id", "chapter_id"])
def test_tts_job_rejects_blank_ids(field):
    with pytest.raises(ValueError, match=field):
        _job(**{field: " "})


@pytest.mark.parametrize("field", ["chapter_index", "segment_index"])
def test_tts_job_rejects_negative_indexes(field):
    with pytest.raises(ValueError, match=field):
        _job(**{field: -1})


def test_tts_job_rejects_mismatched_voice_profile_id():
    with pytest.raises(ValueError, match="voice_profile"):
        _job(
            preset=_preset(voice_profile_id="voice-1"),
            voice_profile=VoiceProfile("voice-2", "另一位旁白"),
        )


def test_prepared_voice_clone_prompt_is_not_in_job_or_preset_payload():
    profile = VoiceProfile("voice-1", "旁白")
    prepared = PreparedVoice(
        profile_id="voice-1",
        cache_key="voice-1:cache-key",
        clone_prompt="worker-memory-only",
    )
    job = _job(preset=_preset(voice_profile_id=profile.profile_id), voice_profile=profile)

    assert prepared.clone_prompt == "worker-memory-only"
    assert "clone_prompt" not in asdict(job)
    assert "clone_prompt" not in asdict(job.preset)
