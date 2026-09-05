from audiobook_worker.gpu_selector import GpuSelector
from audiobook_worker.model_registry import ModelRegistry
from audiobook_worker.runtime_probe import RuntimeProbe


def test_selects_highest_priority_compatible_qwen_model():
    probe = RuntimeProbe(
        cuda_available=True,
        gpu_name="Test GPU",
        gpu_memory_bytes=16 * 1024**3,
        cuda_version="12.4",
        torch_version="2.7.0",
        python_version="3.11",
    )

    selected = GpuSelector.select(ModelRegistry.default(), probe)

    assert selected is not None
    assert selected.model_id == "Qwen/Qwen3-TTS-12Hz-1.7B-Base"


def test_returns_none_without_cuda_instead_of_using_cpu():
    probe = RuntimeProbe(False, None, 0, None, "2.7.0", "3.11")

    assert GpuSelector.select(ModelRegistry.default(), probe) is None


def test_selects_smaller_model_when_gpu_memory_is_limited():
    probe = RuntimeProbe(
        True,
        "Small GPU",
        4 * 1024**3,
        "12.4",
        "2.7.0",
        "3.11",
    )

    selected = GpuSelector.select(ModelRegistry.default(), probe)

    assert selected is not None
    assert selected.model_id == "Qwen/Qwen3-TTS-12Hz-0.6B-Base"
