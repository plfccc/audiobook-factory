from pathlib import Path
from collections.abc import Iterable, Mapping
import json
from typing import Any

from ._candidate_engine import CandidateInferenceRequest, LazyCandidateEngine
from .contracts import EngineCapabilities


_COSYVOICE_PARAMETER_NAMES = frozenset({"speed", "stream", "text_frontend"})


def _parse_parameters(request: CandidateInferenceRequest) -> dict[str, Any]:
    try:
        parameters = json.loads(request.parameters_json or "{}")
    except (TypeError, json.JSONDecodeError) as error:
        raise ValueError(
            "cosyvoice3 parameters_json must be a valid JSON object"
        ) from error
    if not isinstance(parameters, dict):
        raise ValueError("cosyvoice3 parameters_json must be a JSON object")
    unknown = sorted(set(parameters) - _COSYVOICE_PARAMETER_NAMES)
    if unknown:
        raise ValueError(
            f"cosyvoice3 has unsupported parameters: {', '.join(unknown)}"
        )
    if "speed" in parameters and (
        isinstance(parameters["speed"], bool)
        or not isinstance(parameters["speed"], (int, float))
        or parameters["speed"] <= 0
    ):
        raise ValueError("cosyvoice3 parameter speed must be a positive number")
    for name in ("stream", "text_frontend"):
        if name in parameters and not isinstance(parameters[name], bool):
            raise ValueError(f"cosyvoice3 parameter {name} must be boolean")
    return parameters


def _request_kwargs(
    request: CandidateInferenceRequest, destination: Path
) -> dict[str, Any]:
    return {
        "provider": request.provider,
        "model": request.model,
        "version": request.version,
        "language": request.language,
        "voice": request.voice,
        "style_prompt": request.style_prompt,
        "parameters_json": request.parameters_json,
        "reference_audio": request.reference_audio_path,
        "reference_text": request.reference_text,
        "design_prompt": request.design_prompt,
        "text": request.text,
        "destination": destination,
        "parameters": _parse_parameters(request),
    }


class _CosyVoiceLoader:
    def __init__(self, model_path: Path | str | None) -> None:
        self.model_path = Path(model_path) if model_path is not None else None

    def load(self, model_id: str, device: str) -> Any:
        if self.model_path is None or not self.model_path.is_dir():
            raise RuntimeError("cosyvoice requires an existing local model_path; downloads are disabled")
        config = self.model_path / "cosyvoice3.yaml"
        if not config.is_file():
            raise RuntimeError(
                "cosyvoice model_path must contain cosyvoice3.yaml"
            )
        try:
            from cosyvoice.cli.cosyvoice import CosyVoice3
        except ImportError as exc:
            raise RuntimeError("cosyvoice dependency is required for CosyVoice3") from exc
        model = CosyVoice3(model_dir=str(self.model_path), load_trt=False)
        return _CosyVoiceModelApi(model)


class _CosyVoiceModelApi:
    """把完整 Worker 请求映射为 CosyVoice 的本地推理调用。"""

    def __init__(self, model: Any) -> None:
        self.model = model

    def synthesize(self, **request: Any) -> Any:
        parameters = dict(request["parameters"])
        reference_audio = request["reference_audio"]
        reference_text = request["reference_text"] or ""
        text = request["text"]
        kwargs = {
            key: parameters[key]
            for key in ("speed", "stream", "text_frontend")
            if key in parameters
        }
        if reference_audio is not None:
            if not reference_text.strip():
                raise ValueError("cosyvoice zero-shot synthesis requires reference_text")
            if request.get("design_prompt") and hasattr(self.model, "inference_instruct2"):
                return self.model.inference_instruct2(
                    text,
                    request["design_prompt"],
                    str(reference_audio),
                    **kwargs,
                )
            if not hasattr(self.model, "inference_zero_shot"):
                raise RuntimeError("cosyvoice model has no zero-shot inference API")
            return self.model.inference_zero_shot(
                text,
                reference_text,
                str(reference_audio),
                **kwargs,
            )
        if not hasattr(self.model, "inference_sft"):
            raise RuntimeError("cosyvoice model has no speaker inference API")
        return self.model.inference_sft(text, request["voice"], **kwargs)


def _iter_cosyvoice_outputs(result: Any) -> Iterable[Any]:
    if isinstance(result, Mapping) or isinstance(result, (str, bytes, Path)):
        return (result,)
    try:
        return iter(result)
    except TypeError:
        return (result,)


class _CosyVoiceAdapter:
    def synthesize(self, model: Any, request: CandidateInferenceRequest, destination: Path) -> Path:
        kwargs = _request_kwargs(request, destination)
        api = getattr(model, "synthesize", None)
        if not callable(api):
            raise RuntimeError("cosyvoice loader must return its model-specific API wrapper")
        result = api(**kwargs)
        if isinstance(result, (Path, str)):
            return Path(result)
        chunks = []
        for item in _iter_cosyvoice_outputs(result):
            audio = item["tts_speech"] if isinstance(item, Mapping) else item
            if audio is None:
                continue
            if hasattr(audio, "detach"):
                audio = audio.detach().cpu().numpy()
            elif hasattr(audio, "cpu"):
                audio = audio.cpu().numpy()
            chunks.append(audio)
        if not chunks:
            raise RuntimeError("cosyvoice inference returned no audio")
        import numpy as np
        import soundfile as sf
        audio = chunks[0] if len(chunks) == 1 else np.concatenate(chunks, axis=-1)
        sf.write(str(destination), audio, int(getattr(model, "sample_rate", 22050)))
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
