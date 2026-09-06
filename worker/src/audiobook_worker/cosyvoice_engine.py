from pathlib import Path
from typing import Any

from ._candidate_engine import CandidateInferenceRequest, LazyCandidateEngine
from .contracts import EngineCapabilities


class _CosyVoiceLoader:
    def __init__(self, model_path: Path | str | None) -> None:
        self.model_path = Path(model_path) if model_path is not None else None

    def load(self, model_id: str, device: str) -> Any:
        if self.model_path is None or not self.model_path.is_dir():
            raise RuntimeError("cosyvoice requires an existing local model_path; downloads are disabled")
        try:
            from cosyvoice.cli.cosyvoice import CosyVoice3
        except ImportError as exc:
            raise RuntimeError("cosyvoice dependency is required for CosyVoice3") from exc
        return CosyVoice3(model_dir=str(self.model_path), load_jit=False, load_trt=False)


class _CosyVoiceAdapter:
    def synthesize(self, model: Any, request: CandidateInferenceRequest, destination: Path) -> Path:
        kwargs = {"speed": 1.0}
        if request.reference_audio_path is not None:
            kwargs.update({"prompt_speech_16k": str(request.reference_audio_path), "prompt_text": request.reference_text or ""})
        try:
            result = model.inference_sft(request.text, request.voice, **kwargs)
        except AttributeError:
            result = model.inference_zero_shot(request.text, request.style_prompt, **kwargs)
        audio = result["tts_speech"] if isinstance(result, dict) else result
        import soundfile as sf
        sf.write(str(destination), audio.detach().cpu().numpy() if hasattr(audio, "detach") else audio, 22050)
        return destination


def _loader_factory(model_path: Path | str | None) -> _CosyVoiceLoader:
    return _CosyVoiceLoader(model_path)


def _adapter_factory() -> _CosyVoiceAdapter:
    return _CosyVoiceAdapter()


class CosyVoice3Engine(LazyCandidateEngine):
    engine_id = "cosyvoice3"
    model_id = "FunAudioLLM/Fun-CosyVoice3-0.5B-2512"
    model_version = "3.0"
    dependency_name = "cosyvoice"
    model_loader_factory = staticmethod(_loader_factory)
    model_adapter_factory = staticmethod(_adapter_factory)
    capabilities = EngineCapabilities(("zh-CN", "en-US"), False, True, True, False)


__all__ = ["CosyVoice3Engine"]
