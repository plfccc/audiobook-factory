from pathlib import Path
import json
from typing import Any

from ._candidate_engine import CandidateInferenceRequest, LazyCandidateEngine
from .contracts import EngineCapabilities


_F5_PARAMETER_NAMES = frozenset(
    {
        "cfg_strength",
        "sway_sampling_coef",
        "nfe_step",
        "speed",
        "remove_silence",
        "cross_fade_duration",
        "seed",
    }
)


def _parse_parameters(request: CandidateInferenceRequest) -> dict[str, Any]:
    try:
        parameters = json.loads(request.parameters_json or "{}")
    except (TypeError, json.JSONDecodeError) as error:
        raise ValueError(
            "f5-tts parameters_json must be a valid JSON object"
        ) from error
    if not isinstance(parameters, dict):
        raise ValueError("f5-tts parameters_json must be a JSON object")
    unknown = sorted(set(parameters) - _F5_PARAMETER_NAMES)
    if unknown:
        raise ValueError(f"f5-tts has unsupported parameters: {', '.join(unknown)}")
    for name in (
        "cfg_strength",
        "sway_sampling_coef",
        "speed",
        "cross_fade_duration",
    ):
        if name in parameters and (
            isinstance(parameters[name], bool)
            or not isinstance(parameters[name], (int, float))
            or parameters[name] < 0
            or (name in {"cfg_strength", "speed"} and parameters[name] == 0)
        ):
            qualifier = "positive" if name in {"cfg_strength", "speed"} else "non-negative"
            raise ValueError(f"f5-tts parameter {name} must be a {qualifier} number")
    if "nfe_step" in parameters and (
        type(parameters["nfe_step"]) is not int or parameters["nfe_step"] <= 0
    ):
        raise ValueError("f5-tts parameter nfe_step must be a positive integer")
    if "seed" in parameters and type(parameters["seed"]) is not int:
        raise ValueError("f5-tts parameter seed must be an integer")
    if "remove_silence" in parameters and not isinstance(
        parameters["remove_silence"], bool
    ):
        raise ValueError("f5-tts parameter remove_silence must be boolean")
    return parameters


def _request_kwargs(
    request: CandidateInferenceRequest, destination: Path
) -> dict[str, Any]:
    if request.reference_audio_path is None:
        raise ValueError("f5-tts requires reference_audio")
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


class _F5Loader:
    def __init__(self, model_path: Path | str | None) -> None:
        self.model_path = Path(model_path) if model_path is not None else None

    def load(self, model_id: str, device: str) -> Any:
        if self.model_path is None or not self.model_path.is_dir():
            raise RuntimeError("f5_tts requires an existing local model_path; downloads are disabled")
        model_files = tuple(
            path
            for path in self.model_path.iterdir()
            if path.is_file()
            and path.suffix.lower() in {".safetensors", ".ckpt", ".bin", ".pth"}
        )
        if not model_files:
            raise RuntimeError(
                "f5_tts model_path must contain a local .safetensors, .ckpt, .bin, or .pth checkpoint"
            )
        vocoder_path = self.model_path / "vocos"
        if not vocoder_path.is_dir():
            raise RuntimeError(
                "f5_tts model_path must contain a local vocoder directory at vocos/"
            )
        try:
            from f5_tts.api import F5TTS
        except ImportError as exc:
            raise RuntimeError("f5_tts dependency is required for F5-TTS") from exc
        checkpoint = sorted(model_files, key=lambda path: path.name)[0]
        return _F5ModelApi(
            F5TTS(
                ckpt_file=str(checkpoint),
                vocoder_local_path=str(vocoder_path),
                device=device,
            )
        )


class _F5ModelApi:
    """把完整 Worker 请求映射为 F5-TTS 的本地推理调用。"""

    def __init__(self, model: Any) -> None:
        self.model = model

    def synthesize(self, **request: Any) -> Any:
        reference_audio = request["reference_audio"]
        if reference_audio is None:
            raise ValueError("f5-tts requires reference_audio")
        return self.model.infer(
            ref_file=str(reference_audio),
            ref_text=request["reference_text"] or "",
            gen_text=request["text"],
            file_wave=str(request["destination"]),
            **dict(request["parameters"]),
        )


class _F5Adapter:
    def synthesize(self, model: Any, request: CandidateInferenceRequest, destination: Path) -> Path:
        kwargs = _request_kwargs(request, destination)
        api = getattr(model, "synthesize", None) or getattr(model, "infer", None)
        if not callable(api):
            raise RuntimeError("f5_tts model has no supported inference API")
        result = api(**kwargs)
        return Path(result) if isinstance(result, (Path, str)) else destination


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
