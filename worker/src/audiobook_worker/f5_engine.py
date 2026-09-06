from pathlib import Path
from typing import Any

from ._candidate_engine import CandidateInferenceRequest, LazyCandidateEngine
from .contracts import EngineCapabilities


class _F5Loader:
    def __init__(self, model_path: Path | str | None) -> None:
        self.model_path = Path(model_path) if model_path is not None else None

    def load(self, model_id: str, device: str) -> Any:
        if self.model_path is None or not self.model_path.is_dir():
            raise RuntimeError("f5_tts requires an existing local model_path; downloads are disabled")
        try:
            from f5_tts.api import F5TTS
        except ImportError as exc:
            raise RuntimeError("f5_tts dependency is required for F5-TTS") from exc
        return F5TTS(model=str(self.model_path), device=device)


class _F5Adapter:
    def synthesize(self, model: Any, request: CandidateInferenceRequest, destination: Path) -> Path:
        if request.reference_audio_path is None:
            raise ValueError("f5_tts requires reference voice audio")
        model.infer(ref_file=str(request.reference_audio_path), ref_text=request.reference_text or "", gen_text=request.text, file_wave=str(destination))
        return destination


def _loader_factory(model_path: Path | str | None) -> _F5Loader:
    return _F5Loader(model_path)


def _adapter_factory() -> _F5Adapter:
    return _F5Adapter()


class F5TtsEngine(LazyCandidateEngine):
    engine_id = "f5-tts"
    model_id = "SWivid/F5-TTS"
    model_version = "1.0"
    dependency_name = "f5_tts"
    model_loader_factory = staticmethod(_loader_factory)
    model_adapter_factory = staticmethod(_adapter_factory)
    capabilities = EngineCapabilities(("zh-CN", "en-US"), False, True, False, False)


__all__ = ["F5TtsEngine"]
