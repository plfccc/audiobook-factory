from __future__ import annotations

import hashlib
import importlib.util

from audiobook_worker.contracts import VoiceProfile


def _voice_profile_store_type():
    assert importlib.util.find_spec("audiobook_worker.voice_profiles") is not None
    from audiobook_worker.voice_profiles import VoiceProfileStore

    return VoiceProfileStore


def _normalization_types():
    assert importlib.util.find_spec("audiobook_worker.text_normalization") is not None
    from audiobook_worker.text_normalization import normalize_language, normalize_text

    return normalize_language, normalize_text


def test_materialize_reuses_sha256_named_reference_audio(tmp_path):
    VoiceProfileStore = _voice_profile_store_type()
    source = tmp_path / "source.wav"
    payload = b"same reference audio"
    source.write_bytes(payload)
    profile = VoiceProfile("voice-1", "旁白", source, "参考文本")
    target_dir = tmp_path / "colab-cache"

    materialized = VoiceProfileStore().materialize(profile, target_dir)

    expected_hash = hashlib.sha256(payload).hexdigest()
    assert materialized.parent == target_dir
    assert expected_hash in materialized.name
    assert materialized.read_bytes() == payload


def test_materialize_uses_a_new_hash_path_when_reference_changes(tmp_path):
    VoiceProfileStore = _voice_profile_store_type()
    source = tmp_path / "source.wav"
    target_dir = tmp_path / "colab-cache"
    store = VoiceProfileStore()

    source.write_bytes(b"first")
    first = store.materialize(
        VoiceProfile("voice-1", "旁白", source, "参考文本"), target_dir
    )
    source.write_bytes(b"second")
    second = store.materialize(
        VoiceProfile("voice-1", "旁白", source, "参考文本"), target_dir
    )

    assert first != second
    assert first.read_bytes() == b"first"
    assert second.read_bytes() == b"second"


def test_materialize_rejects_missing_reference_audio(tmp_path):
    VoiceProfileStore = _voice_profile_store_type()
    profile = VoiceProfile(
        "voice-1", "旁白", tmp_path / "missing.wav", "参考文本"
    )

    try:
        VoiceProfileStore().materialize(profile, tmp_path / "cache")
    except FileNotFoundError:
        pass
    else:
        raise AssertionError("missing reference audio must be rejected")


def test_normalize_text_collapses_notebook_whitespace():
    _, normalize_text = _normalization_types()

    assert normalize_text("  这是\n 一段。\t") == "这是 一段。"


def test_normalize_language_maps_preset_language_to_qwen_name():
    normalize_language, _ = _normalization_types()

    assert normalize_language("zh-CN") == "Chinese"
    assert normalize_language("en_US") == "English"
