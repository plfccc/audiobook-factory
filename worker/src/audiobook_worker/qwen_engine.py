from __future__ import annotations

import asyncio
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import struct
import tempfile
import threading
from typing import Any, Mapping
import wave

from .contracts import (
    EngineCapabilities,
    GenerationResult,
    PreparedVoice,
    TtsJob,
    VoiceProfile,
)
from .runtime_probe import RuntimeProbe
from .text_normalization import normalize_language, normalize_text
from .voice_profiles import VoiceProfileStore, sha256_file


BASE_MODEL_ID = "Qwen/Qwen3-TTS-12Hz-1.7B-Base"
DESIGN_MODEL_ID = "Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign"

DEFAULT_CLONE_PARAMETERS = {
    "temperature": 0.75,
    "top_k": 50,
    "top_p": 1.0,
    "repetition_penalty": 1.05,
    "max_new_tokens": 2048,
    "do_sample": True,
}

DEFAULT_DESIGN_PARAMETERS = {
    "temperature": 0.8,
    "top_k": 50,
    "top_p": 1.0,
    "repetition_penalty": 1.05,
    "max_new_tokens": 2048,
    "do_sample": True,
}

_QWEN_PARAMETER_NAMES = frozenset(
    {
        "temperature",
        "top_k",
        "top_p",
        "repetition_penalty",
        "max_new_tokens",
        "do_sample",
        "seed",
    }
)


class QwenModelLoader:
    """惰性加载 qwen-tts；导入本模块不会导入 torch。"""

    def load(self, model_id: str, device: str) -> Any:
        import torch
        from qwen_tts import Qwen3TTSModel

        kwargs: dict[str, Any] = {
            "device_map": device,
            "attn_implementation": "sdpa",
        }
        if str(device).startswith("cuda"):
            kwargs["dtype"] = torch.float16
        else:
            kwargs["dtype"] = torch.float32
        return Qwen3TTSModel.from_pretrained(model_id, **kwargs)


class QwenModelAdapter:
    """将稳定的 Worker 调用转换为 qwen-tts 模型 API。"""

    def __init__(self, model: Any | None = None):
        self.model = model

    def create_voice_clone_prompt(
        self, reference_audio: Path | str, reference_text: str
    ) -> Any:
        model = self._require_model()
        create_prompt = getattr(model, "create_voice_clone_prompt")
        try:
            return create_prompt(
                ref_audio=str(reference_audio),
                ref_text=reference_text,
                x_vector_only_mode=False,
            )
        except TypeError:
            return create_prompt(str(reference_audio), reference_text)

    def generate_voice_clone(
        self,
        model: Any,
        prompt: Any,
        text: str,
        language: str,
        output_path: Path | str,
        parameters: Mapping[str, Any],
    ) -> Path:
        qwen_parameters = dict(DEFAULT_CLONE_PARAMETERS)
        qwen_parameters.update(parameters)
        speed = qwen_parameters.pop("speed", None)
        qwen_parameters = {
            key: value
            for key, value in qwen_parameters.items()
            if key in _QWEN_PARAMETER_NAMES
        }
        generated = model.generate_voice_clone(
            text=text,
            language=language,
            voice_clone_prompt=prompt,
            **qwen_parameters,
        )
        output = Path(output_path)
        if generated is not None:
            waveforms, sample_rate = generated
            waveform = _first_waveform(waveforms)
            waveform = _change_speed(waveform, speed)
            _write_wav(output, waveform, sample_rate)
        elif not output.exists():
            raise RuntimeError("Qwen generate_voice_clone did not produce WAV output")
        return output

    def generate_voice_design(
        self,
        model: Any,
        text: str,
        language: str,
        design_prompt: str,
        output_path: Path | str,
        parameters: Mapping[str, Any],
    ) -> Path:
        qwen_parameters = dict(DEFAULT_DESIGN_PARAMETERS)
        qwen_parameters.update(parameters)
        qwen_parameters.pop("speed", None)
        qwen_parameters = {
            key: value
            for key, value in qwen_parameters.items()
            if key in _QWEN_PARAMETER_NAMES
        }
        generated = model.generate_voice_design(
            text=text,
            language=language,
            instruct=design_prompt,
            **qwen_parameters,
        )
        output = Path(output_path)
        if generated is not None:
            waveforms, sample_rate = generated
            _write_wav(output, _first_waveform(waveforms), sample_rate)
        elif not output.exists():
            raise RuntimeError("Qwen generate_voice_design did not produce WAV output")
        return output

    def _require_model(self) -> Any:
        if self.model is None:
            raise RuntimeError("QwenModelAdapter requires a model for this operation")
        return self.model


@dataclass(frozen=True)
class _PreparedModel:
    model: Any
    prepared: PreparedVoice | None


class Qwen3TtsEngine:
    engine_id = "qwen3-tts"
    capabilities = EngineCapabilities(
        languages=("Chinese", "English", "Japanese", "Korean"),
        voice_design=True,
        voice_clone=True,
        emotion_control=False,
        duration_control=False,
    )

    def __init__(
        self,
        model_loader: Any | None = None,
        cache_dir: Path | str | None = None,
        *,
        model_id: str = BASE_MODEL_ID,
        design_model_id: str = DESIGN_MODEL_ID,
        device: str = "cuda:0",
        model_adapter: Any | None = None,
        voice_store: VoiceProfileStore | None = None,
    ):
        self._model_loader = model_loader or QwenModelLoader()
        self.cache_dir = Path(
            cache_dir or (Path(tempfile.gettempdir()) / "audiobook-worker")
        )
        self.model_id = model_id
        self.design_model_id = design_model_id
        self.device = device
        self._model_adapter = model_adapter
        self._voice_store = voice_store or VoiceProfileStore()
        self._models: dict[str, Any] = {}
        self._clone_prompt_cache: dict[str, Any] = {}
        self._lock = threading.RLock()
        self.probe = RuntimeProbe.detect()

    def prepare_voice(self, profile: VoiceProfile) -> PreparedVoice:
        return self._prepare_voice(profile, self.model_id)

    def _prepare_voice(
        self, profile: VoiceProfile, model_id: str
    ) -> PreparedVoice:
        if not isinstance(profile, VoiceProfile):
            raise TypeError("profile must be a VoiceProfile")

        reference_audio, reference_text = self._materialize_reference(profile)
        reference_digest = sha256_file(reference_audio)
        cache_key = hashlib.sha256(
            f"{model_id}\0{reference_digest}\0{reference_text}".encode("utf-8")
        ).hexdigest()

        model = self._load_model(model_id)
        with self._lock:
            clone_prompt = self._clone_prompt_cache.get(cache_key)
            if cache_key not in self._clone_prompt_cache:
                adapter = self._adapter_for(model)
                clone_prompt = adapter.create_voice_clone_prompt(
                    reference_audio, reference_text
                )
                self._clone_prompt_cache[cache_key] = clone_prompt

        return PreparedVoice(
            profile_id=profile.profile_id,
            cache_key=cache_key,
            reference_audio_path=reference_audio,
            reference_text=reference_text,
            design_prompt=profile.design_prompt,
            clone_prompt=clone_prompt,
        )

    async def synthesize(
        self, job: TtsJob, destination: Path
    ) -> GenerationResult:
        if not isinstance(job, TtsJob):
            raise TypeError("job must be a TtsJob")
        output = Path(destination)
        output.parent.mkdir(parents=True, exist_ok=True)
        prepared_model = await asyncio.to_thread(self._prepare_for_job, job)
        await asyncio.to_thread(
            self._synthesize_blocking,
            job,
            prepared_model,
            output,
        )
        return await asyncio.to_thread(_generation_result, job.job_id, output)

    def _prepare_for_job(self, job: TtsJob) -> _PreparedModel:
        prepared = (
            self._prepare_voice(job.voice_profile, job.preset.model)
            if job.voice_profile is not None
            else None
        )
        return _PreparedModel(self._load_model(job.preset.model), prepared)

    def _synthesize_blocking(
        self, job: TtsJob, prepared_model: _PreparedModel, destination: Path
    ) -> None:
        if job.preset.output_format.lower() != "wav":
            raise ValueError("Qwen3TtsEngine only supports WAV output")
        parameters = _parameters_from_json(job.preset.parameters_json)
        text = normalize_text(job.text)
        language = normalize_language(job.preset.language)
        adapter = self._adapter_for(prepared_model.model)
        adapter.generate_voice_clone(
            prepared_model.model,
            prepared_model.prepared.clone_prompt if prepared_model.prepared else None,
            text,
            language,
            destination,
            parameters,
        )
        if not destination.exists() or destination.stat().st_size == 0:
            raise RuntimeError("Qwen3TtsEngine did not produce WAV output")

    def _materialize_reference(self, profile: VoiceProfile) -> tuple[Path, str]:
        reference_text = profile.reference_text
        if not isinstance(reference_text, str) or not reference_text.strip():
            raise ValueError("reference_text is required for Qwen voice cloning")
        reference_text = normalize_text(reference_text)

        if profile.reference_audio_path is not None:
            path = self._voice_store.materialize(
                profile, self.cache_dir / "references"
            )
            return path, reference_text

        if not profile.design_prompt or not profile.design_prompt.strip():
            raise ValueError(
                "voice profile requires reference_audio_path or design_prompt"
            )
        design_key = hashlib.sha256(
            f"{profile.design_prompt}\0{reference_text}".encode("utf-8")
        ).hexdigest()
        design_path = self.cache_dir / "voice-design" / f"{design_key}.wav"
        if not design_path.exists() or design_path.stat().st_size == 0:
            design_path.parent.mkdir(parents=True, exist_ok=True)
            design_model = self._load_model(self.design_model_id)
            adapter = self._adapter_for(design_model)
            adapter.generate_voice_design(
                design_model,
                reference_text,
                normalize_language("zh-CN"),
                profile.design_prompt.strip(),
                design_path,
                {},
            )
        return (
            self._voice_store.materialize_path(
                design_path, self.cache_dir / "references"
            ),
            reference_text,
        )

    def _load_model(self, model_id: str) -> Any:
        with self._lock:
            if model_id in self._models:
                return self._models[model_id]
            loader = self._model_loader
            if hasattr(loader, "load"):
                model = loader.load(model_id, self.device)
            else:
                model = loader(model_id, self.device)
            self._models[model_id] = model
            return model

    def _adapter_for(self, model: Any) -> Any:
        if self._model_adapter is not None:
            return self._model_adapter
        return QwenModelAdapter(model)


def _parameters_from_json(parameters_json: str) -> dict[str, Any]:
    payload = json.loads(parameters_json)
    if not isinstance(payload, dict):
        raise ValueError("parameters_json must be a JSON object")
    normalized: dict[str, Any] = {}
    aliases = {
        "top-k": "top_k",
        "top-p": "top_p",
        "max-new-tokens": "max_new_tokens",
    }
    for key, value in payload.items():
        normalized[aliases.get(key, key)] = value
    return normalized


def _first_waveform(waveforms: Any) -> Any:
    if hasattr(waveforms, "ndim"):
        if waveforms.ndim == 1:
            return waveforms
        return waveforms[0]
    if isinstance(waveforms, (list, tuple)):
        if not waveforms:
            raise ValueError("Qwen returned no waveform")
        if isinstance(waveforms[0], (int, float)):
            return waveforms
        return waveforms[0]
    return waveforms


def _change_speed(waveform: Any, speed: Any) -> Any:
    if speed is None:
        return waveform
    speed_value = float(speed)
    if speed_value <= 0:
        raise ValueError("speed must be positive")
    if abs(speed_value - 1.0) < 1e-9:
        return waveform
    try:
        import numpy as np
    except ImportError:
        return waveform

    samples = np.asarray(waveform)
    if samples.ndim == 0 or len(samples) == 0:
        return waveform
    target_length = max(1, round(len(samples) / speed_value))
    indexes = np.linspace(0, len(samples) - 1, target_length).astype(int)
    return samples[indexes]


def _write_wav(path: Path, waveform: Any, sample_rate: int) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        import soundfile as sf
    except ImportError:
        _write_wav_without_soundfile(path, waveform, sample_rate)
        return
    sf.write(str(path), waveform, int(sample_rate), format="WAV")


def _write_wav_without_soundfile(path: Path, waveform: Any, sample_rate: int) -> None:
    values = waveform.tolist() if hasattr(waveform, "tolist") else waveform
    if not isinstance(values, (list, tuple)):
        values = [values]
    channels = 1
    if values and isinstance(values[0], (list, tuple)):
        channels = len(values[0]) or 1
        frames = values
    else:
        frames = [(value,) for value in values]

    pcm = bytearray()
    for frame in frames:
        if not isinstance(frame, (list, tuple)):
            frame = (frame,)
        for value in frame:
            number = float(value)
            if -1.0 <= number <= 1.0:
                number *= 32767
            pcm.extend(struct.pack("<h", max(-32768, min(32767, round(number)))))

    with wave.open(str(path), "wb") as output:
        output.setnchannels(channels)
        output.setsampwidth(2)
        output.setframerate(int(sample_rate))
        output.writeframes(bytes(pcm))


def _generation_result(request_id: str, output_path: Path) -> GenerationResult:
    try:
        with wave.open(str(output_path), "rb") as audio:
            frames = audio.getnframes()
            sample_rate = audio.getframerate()
            channels = audio.getnchannels()
    except (OSError, wave.Error) as exc:
        raise ValueError(
            f"generated output is not a readable WAV: {output_path}"
        ) from exc
    if frames <= 0 or sample_rate <= 0 or channels <= 0:
        raise ValueError(f"generated output has invalid WAV metadata: {output_path}")
    digest = hashlib.sha256()
    with output_path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    size_bytes = output_path.stat().st_size
    return GenerationResult(
        request_id=request_id,
        output_path=output_path,
        sha256=digest.hexdigest(),
        size_bytes=size_bytes,
        duration_seconds=frames / sample_rate,
        sample_rate=sample_rate,
        channels=channels,
    )


__all__ = [
    "BASE_MODEL_ID",
    "DESIGN_MODEL_ID",
    "Qwen3TtsEngine",
    "QwenModelAdapter",
    "QwenModelLoader",
]
