from audiobook_worker.model_registry import ModelRegistry
from audiobook_worker.runtime_probe import RuntimeProbe


def test_selects_highest_priority_compatible_model():
    probe = RuntimeProbe(
        cuda_available=True,
        gpu_name="Test GPU",
        gpu_memory_bytes=16 * 1024**3,
        cuda_version="12.4",
        torch_version="2.7.0",
        python_version="3.11",
    )

    selected = ModelRegistry.default().select(probe)

    assert selected.model_id == "Qwen/Qwen3-TTS-12Hz-1.7B-Base"


def test_returns_none_without_cuda():
    probe = RuntimeProbe(False, None, 0, None, "2.7.0", "3.11")

    assert ModelRegistry.default().select(probe) is None


def test_registry_records_all_candidate_models_and_license_urls():
    registry = ModelRegistry.default()
    model_ids = {profile.model_id for profile in registry.models}

    assert model_ids == {
        "Qwen/Qwen3-TTS-12Hz-1.7B-Base",
        "Qwen/Qwen3-TTS-12Hz-0.6B-Base",
        "FunAudioLLM/Fun-CosyVoice3-0.5B-2512",
        "IndexTeam/IndexTTS-2.5",
        "SWivid/F5-TTS",
    }
    assert all(profile.model_version for profile in registry.models)
    assert all(profile.minimum_vram_bytes > 0 for profile in registry.models)
    assert all(profile.license_url.startswith("https://") for profile in registry.models)


def test_select_can_be_limited_to_requested_engine_and_vram():
    probe = RuntimeProbe(True, "Small GPU", 6 * 1024**3, "12.4", "2.7.0", "3.11")

    selected = ModelRegistry.default().select(probe, requested_engine="qwen3-tts")

    assert selected is not None
    assert selected.engine_id == "qwen3-tts"
    assert selected.model_id == "Qwen/Qwen3-TTS-12Hz-0.6B-Base"
