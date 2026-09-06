from pathlib import Path
from typing import Any

from ._candidate_engine import CandidateInferenceRequest, LazyCandidateEngine
from .contracts import EngineCapabilities


class _IndexTtsLoader:
    def __init__(self, model_path: Path | str | None) -> None:
        self.model_path = Path(model_path) if model_path is not None else None

    def load(self, model_id: str, device: str) -> Any:
        if self.model_path is None or not self.model_path.is_dir():
            raise RuntimeError("indextts requires an existing local model_path; downloads are disabled")
        try:
            from indextts.infer_v2 import IndexTTS
        except ImportError as exc:
            raise RuntimeError("indextts dependency is required for IndexTTS") from exc
        config = self.model_path / "config.yaml"
        return IndexTTS(cfg_path=str(config), model_dir=str(self.model_path), device=device)


class _IndexTtsAdapter:
    def synthesize(self, model: Any, request: CandidateInferenceRequest, destination: Path) -> Path:
        if request.reference_audio_path is None:
            raise ValueError("indextts requires reference voice audio")
        model.infer(str(request.reference_audio_path), request.text, str(destination), request.reference_text or "")
        return destination


def _loader_factory(model_path: Path | str | None) -> _IndexTtsLoader:
    return _IndexTtsLoader(model_path)


def _adapter_factory() -> _IndexTtsAdapter:
    return _IndexTtsAdapter()


class IndexTts25Engine(LazyCandidateEngine):
    engine_id = "indextts-2.5"
    model_id = "IndexTeam/IndexTTS-2.5"
    model_version = "2.5"
    dependency_name = "indextts"
    model_loader_factory = staticmethod(_loader_factory)
    model_adapter_factory = staticmethod(_adapter_factory)
    capabilities = EngineCapabilities(("zh-CN", "en-US"), False, True, True, True)


__all__ = ["IndexTts25Engine"]
