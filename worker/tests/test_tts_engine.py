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
