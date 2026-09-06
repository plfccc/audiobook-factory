from __future__ import annotations

import asyncio
import hashlib
import inspect
from pathlib import Path
from dataclasses import dataclass
from typing import Any, Callable, Protocol
import wave

from .contracts import EngineCapabilities, GenerationResult, PreparedVoice, TtsJob, VoiceProfile
from .runtime_probe import RuntimeProbe


class CandidateModelLoader(Protocol):
    """模型特定 loader 的执行边界；实现只能从已存在的本地路径装载。"""

    def load(self, model_id: str, device: str) -> Any:
        ...


@dataclass(frozen=True)
class CandidateInferenceRequest:
    """传给 model-specific adapter 的完整请求快照。"""

    text: str
    model_id: str
    model_version: str
    language: str
    voice: str
    style_prompt: str
    parameters_json: str
    reference_audio_path: Path | None
    reference_text: str | None
    design_prompt: str | None
    destination: Path


class CandidateAdapter(Protocol):
    """候选模型真实推理边界；adapter 不得静默丢弃请求字段。"""

    def synthesize(
        self, model: Any, request: CandidateInferenceRequest, destination: Path
    ) -> Path | str | None:
        ...


class LazyCandidateEngine:
    """候选模型的统一惰性适配层；模型包只在真正合成时加载。"""

    engine_id: str
    model_id: str
    model_version: str
    capabilities: EngineCapabilities
    dependency_name: str
    model_loader_factory: Callable[[Path | str | None], CandidateModelLoader] | None = None
    model_adapter_factory: Callable[[], CandidateAdapter] | None = None

    def __init__(self, model_loader: Any | None = None, model_adapter: Any | None = None,
                 probe: RuntimeProbe | None = None, *, device: str = "cuda:0",
                 model_path: Path | str | None = None) -> None:
        if model_loader is None:
            if self.model_loader_factory is None:
                raise RuntimeError(f"{self.__class__.__name__} has no model-specific loader")
            model_loader = self.model_loader_factory(model_path)
        if model_adapter is None:
            if self.model_adapter_factory is None:
                raise RuntimeError(f"{self.__class__.__name__} has no model-specific adapter")
            model_adapter = self.model_adapter_factory()
        self._model_loader: CandidateModelLoader = model_loader
        self._model_adapter: CandidateAdapter = model_adapter
        self.probe = probe or RuntimeProbe.detect()
        self.device = device
        self._model: Any | None = None

    @property
    def model_identity(self) -> str:
        return f"{self.model_id}@{self.model_version}"

    async def prepare_voice(self, profile: VoiceProfile) -> PreparedVoice:
        if not isinstance(profile, VoiceProfile):
            raise TypeError("profile must be a VoiceProfile")
        reference = profile.reference_audio_path
        reference_digest = "none"
        if reference is not None:
            reference_path = Path(reference)
            if not reference_path.is_file():
                raise ValueError(f"voice reference audio does not exist: {reference_path}")
            digest = await asyncio.to_thread(_sha256_file, reference_path)
            reference_digest = digest
        return PreparedVoice(
            profile_id=profile.profile_id,
            cache_key=f"{self.engine_id}:{profile.profile_id}:{reference_digest}",
            reference_audio_path=reference,
            reference_text=profile.reference_text,
            design_prompt=profile.design_prompt,
        )

    async def synthesize(self, job: TtsJob, destination: Path) -> GenerationResult:
        if not isinstance(job, TtsJob):
            raise TypeError("job must be a TtsJob")
        if job.preset.provider != self.engine_id:
            raise ValueError("job preset provider does not match the selected engine")
        if job.preset.model != self.model_id:
            raise ValueError("job preset model does not match the selected engine")
        if job.preset.model_version not in ("unspecified", self.model_version):
            raise ValueError("job preset model version does not match the selected engine")
        if job.preset.language not in self.capabilities.languages:
            raise ValueError(f"language is not supported by {self.engine_id}: {job.preset.language}")
        if job.preset.output_format.lower() != "wav":
            raise ValueError(f"{self.__class__.__name__} only supports WAV output")
        if self.capabilities.voice_clone and job.voice_profile is None:
            raise ValueError(f"{self.__class__.__name__} requires a voice profile")
        model = await asyncio.to_thread(self._load_model)
        prepared = await self.prepare_voice(job.voice_profile or VoiceProfile(
            profile_id="default", name="default", reference_text=""
        ))
        output = Path(destination)
        output.parent.mkdir(parents=True, exist_ok=True)
        request = CandidateInferenceRequest(
            text=job.text,
            model_id=job.preset.model,
            model_version=job.preset.model_version,
            language=job.preset.language,
            voice=job.preset.voice,
            style_prompt=job.preset.style_prompt,
            parameters_json=job.preset.parameters_json,
            reference_audio_path=prepared.reference_audio_path,
            reference_text=prepared.reference_text,
            design_prompt=prepared.design_prompt,
            destination=output,
        )
        # The request object is the stable adapter contract. Keep the four-argument
        # form temporarily usable for the existing offline/fake adapters.
        synthesize = self._model_adapter.synthesize
        if len(inspect.signature(synthesize).parameters) == 4:
            generated = await asyncio.to_thread(
                synthesize, model, job, prepared, output
            )
        else:
            generated = await asyncio.to_thread(synthesize, model, request, output)
        output = Path(generated or output)
        if not output.is_file() or output.stat().st_size == 0:
            raise RuntimeError(f"{self.__class__.__name__} did not produce WAV output")
        try:
            with wave.open(str(output), "rb") as audio:
                frames = audio.getnframes()
                sample_rate = audio.getframerate()
                channels = audio.getnchannels()
                if frames <= 0 or sample_rate <= 0 or channels <= 0:
                    raise ValueError("invalid WAV metadata")
        except (OSError, EOFError, wave.Error, ValueError) as error:
            raise RuntimeError(
                f"{self.__class__.__name__} produced an invalid WAV output"
            ) from error
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        return GenerationResult(
            job.job_id, output, digest, output.stat().st_size,
            frames / sample_rate, sample_rate, channels
        )

    def _load_model(self) -> Any:
        if self._model is None:
            self._model = self._model_loader.load(self.model_id, self.device)
        return self._model


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()
