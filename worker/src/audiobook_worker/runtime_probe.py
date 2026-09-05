from dataclasses import dataclass
import platform


@dataclass(frozen=True)
class RuntimeProbe:
    cuda_available: bool
    gpu_name: str | None
    gpu_memory_bytes: int
    cuda_version: str | None
    torch_version: str | None
    python_version: str

    @classmethod
    def detect(cls) -> "RuntimeProbe":
        return detect_runtime()


def detect_runtime() -> RuntimeProbe:
    python_version = platform.python_version()
    try:
        import torch
    except (ImportError, OSError):
        return RuntimeProbe(False, None, 0, None, None, python_version)

    torch_version = str(getattr(torch, "__version__", "unknown"))
    cuda = getattr(torch, "cuda", None)
    if cuda is None or not cuda.is_available():
        return RuntimeProbe(False, None, 0, None, torch_version, python_version)

    gpu_name = str(cuda.get_device_name(0))
    gpu_memory_bytes = int(cuda.get_device_properties(0).total_memory)
    torch_runtime = getattr(torch, "version", None)
    cuda_version = getattr(torch_runtime, "cuda", None)
    if cuda_version is not None:
        cuda_version = str(cuda_version)
    return RuntimeProbe(
        True,
        gpu_name,
        gpu_memory_bytes,
        cuda_version,
        torch_version,
        python_version,
    )
