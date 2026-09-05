from __future__ import annotations

from .model_registry import ModelProfile, ModelRegistry
from .runtime_probe import RuntimeProbe


class GpuSelector:
    """选择当前 GPU 能够稳定加载的最高优先级模型。

    这是无状态入口，保留为类方法风格以便 Notebook 和 Worker 注入替代
    的 ModelRegistry。没有 CUDA 或显存不足时始终返回 None，不会退回 CPU。
    """

    @staticmethod
    def select(
        registry: ModelRegistry,
        probe: RuntimeProbe,
        requested_engine: str | None = None,
    ) -> ModelProfile | None:
        if probe is None or not probe.cuda_available:
            return None
        selector = getattr(registry, "select", None)
        if not callable(selector):
            raise TypeError("registry must provide select(probe, requested_engine)")
        return selector(probe, requested_engine)


__all__ = ["GpuSelector"]
