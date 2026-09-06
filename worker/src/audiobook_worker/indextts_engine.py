from pathlib import Path
import json
from typing import Any

from ._candidate_engine import CandidateInferenceRequest, LazyCandidateEngine
from .contracts import EngineCapabilities


_INDEX_PARAMETER_NAMES = frozenset(
    {
        "emo_audio_prompt",
        "emo_alpha",
        "emo_vector",
        "use_emo_text",
        "emo_text",
        "use_random",
        "interval_silence",
        "max_text_tokens_per_segment",
        "stream_return",
        "more_segment_before",
        "do_sample",
        "length_penalty",
        "temperature",
        "top_k",
        "top_p",
        "repetition_penalty",
        "max_mel_tokens",
        "num_beams",
    }
)


def _parse_parameters(request: CandidateInferenceRequest) -> dict[str, Any]:
    try:
        parameters = json.loads(request.parameters_json or "{}")
    except (TypeError, json.JSONDecodeError) as error:
        raise ValueError(
            "indextts-2.5 parameters_json must be a valid JSON object"
        ) from error
    if not isinstance(parameters, dict):
        raise ValueError("indextts-2.5 parameters_json must be a JSON object")
    unknown = sorted(set(parameters) - _INDEX_PARAMETER_NAMES)
    if unknown:
        raise ValueError(
            f"indextts-2.5 has unsupported parameters: {', '.join(unknown)}"
        )
    numeric_positive = ("temperature", "top_p", "repetition_penalty", "emo_alpha")
    for name in numeric_positive:
        if name in parameters and (
            isinstance(parameters[name], bool)
            or not isinstance(parameters[name], (int, float))
            or parameters[name] <= 0
            or (name in {"top_p", "emo_alpha"} and parameters[name] > 1)
        ):
            limit = " between 0 and 1" if name in {"top_p", "emo_alpha"} else " positive"
            raise ValueError(f"indextts-2.5 parameter {name} must be{limit} number")
    for name in ("top_k", "max_mel_tokens", "num_beams"):
        if name in parameters and (
            type(parameters[name]) is not int or parameters[name] <= 0
        ):
            raise ValueError(
                f"indextts-2.5 parameter {name} must be a positive integer"
            )
    for name in ("do_sample",):
        if name in parameters and not isinstance(parameters[name], bool):
            raise ValueError(f"indextts-2.5 parameter {name} must be boolean")
    for name in ("use_emo_text", "use_random", "stream_return"):
        if name in parameters and not isinstance(parameters[name], bool):
            raise ValueError(f"indextts-2.5 parameter {name} must be boolean")
    for name in ("interval_silence", "max_text_tokens_per_segment", "more_segment_before"):
        if name in parameters and (
            type(parameters[name]) is not int or parameters[name] < 0
        ):
            raise ValueError(
                f"indextts-2.5 parameter {name} must be a non-negative integer"
            )
    if "emo_vector" in parameters and not isinstance(parameters["emo_vector"], list):
        raise ValueError("indextts-2.5 parameter emo_vector must be a JSON array")
    return parameters


def _request_kwargs(
    request: CandidateInferenceRequest, destination: Path
) -> dict[str, Any]:
    if request.reference_audio_path is None:
        raise ValueError("indextts-2.5 requires reference_audio")
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


class _IndexTtsLoader:
    def __init__(self, model_path: Path | str | None) -> None:
        self.model_path = Path(model_path) if model_path is not None else None

    def load(self, model_id: str, device: str) -> Any:
        if self.model_path is None or not self.model_path.is_dir():
            raise RuntimeError("indextts requires an existing local model_path; downloads are disabled")
        config = self.model_path / "config.yaml"
        if not config.is_file():
            raise RuntimeError(
                "indextts model_path must contain config.yaml; downloads are disabled"
            )
        aux_root = self.model_path / "aux"
        aux_paths = {
            "w2v_bert": aux_root / "w2v-bert-2.0",
            "semantic_codec": aux_root / "semantic_codec_model.safetensors",
            "campplus": aux_root / "campplus_cn_common.bin",
            "bigvgan": aux_root / "bigvgan",
        }
        if (
            not aux_paths["w2v_bert"].is_dir()
            or not aux_paths["semantic_codec"].is_file()
            or not aux_paths["campplus"].is_file()
            or not aux_paths["bigvgan"].is_dir()
        ):
            raise RuntimeError(
                "indextts model_path must contain local aux assets under aux/ "
                "(w2v-bert-2.0, semantic_codec_model.safetensors, "
                "campplus_cn_common.bin, bigvgan)"
            )
        try:
            from indextts.infer_v2 import IndexTTS2
        except ImportError as exc:
            raise RuntimeError("indextts dependency is required for IndexTTS2") from exc
        model = IndexTTS2(
            cfg_path=str(config),
            model_dir=str(self.model_path),
            use_fp16=device.startswith("cuda"),
            device=device,
            use_cuda_kernel=False,
            use_deepspeed=False,
            use_accel=False,
            use_torch_compile=False,
            use_qwen_emo=False,
            aux_paths={key: str(value) for key, value in aux_paths.items()},
        )
        return _IndexTtsModelApi(model)


class _IndexTtsModelApi:
    """把完整 Worker 请求映射为 IndexTTS 的本地推理调用。"""

    def __init__(self, model: Any) -> None:
        self.model = model

    def synthesize(self, **request: Any) -> Any:
        reference_audio = request["reference_audio"]
        if reference_audio is None:
            raise ValueError("indextts-2.5 requires reference_audio")
        parameters = dict(request["parameters"])
        kwargs = {
            key: parameters.pop(key)
            for key in (
                "emo_audio_prompt",
                "emo_alpha",
                "emo_vector",
                "use_emo_text",
                "emo_text",
                "use_random",
                "interval_silence",
                "max_text_tokens_per_segment",
                "stream_return",
                "more_segment_before",
            )
            if key in parameters
        }
        kwargs.update(parameters)
        return self.model.infer(
            spk_audio_prompt=str(reference_audio),
            text=request["text"],
            output_path=str(request["destination"]),
            verbose=False,
            **kwargs,
        )


class _IndexTtsAdapter:
    def synthesize(self, model: Any, request: CandidateInferenceRequest, destination: Path) -> Path:
        kwargs = _request_kwargs(request, destination)
        api = getattr(model, "synthesize", None)
        if not callable(api):
            raise RuntimeError("indextts loader must return its model-specific API wrapper")
        result = api(**kwargs)
        return Path(result) if isinstance(result, (Path, str)) else destination


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
