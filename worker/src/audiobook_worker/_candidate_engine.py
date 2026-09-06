from __future__ import annotations

import asyncio
import hashlib
import importlib
from pathlib import Path
from typing import Any, Protocol
import wave

from .contracts import EngineCapabilities, GenerationResult, PreparedVoice, TtsJob, VoiceProfile
from .runtime_probe import RuntimeProbe


class CandidateAdapter(Protocol):
    """候选模型真实推理边界；实现可在 Colab 注入，避免绑定某个模型包 API。"""

    def synthesize(
        self, model: Any, job: TtsJob, prepared: PreparedVoice, destination: Path
    ) -> Path | str | None:
        ...


class LazyCandidateEngine:
    """候选模型的统一惰性适配层；模型包只在真正合成时加载。"""

    engine_id: str
    model_id: str
    model_version: str
    capabilities: EngineCapabilities
    dependency_name: str

    def __init__(self, model_loader: Any | None = None, model_adapter: Any | None = None,
                 probe: RuntimeProbe | None = None, *, device: str = "cuda:0") -> None:
        self._model_loader = model_loader or _ImportingModelLoader(self.dependency_name)
        self._model_adapter: CandidateAdapter = model_adapter or _ImportingModelAdapter(self.dependency_name)
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
        generated = await asyncio.to_thread(self._model_adapter.synthesize, model, job, prepared, output)
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
            loader = self._model_loader
            self._model = loader.load(self.model_id, self.device) if hasattr(loader, "load") else loader(self.model_id, self.device)
        return self._model


class _ImportingModelLoader:
    def __init__(self, dependency_name: str) -> None:
        self.dependency_name = dependency_name

    def load(self, model_id: str, device: str) -> Any:
        try:
            module = importlib.import_module(self.dependency_name)
        except ImportError as error:
            raise RuntimeError(
                f"{self.dependency_name} is not installed; install the optional Colab model package before using this engine"
            ) from error
        for name in ("load_model", "from_pretrained", "load"):
            loader = getattr(module, name, None)
            if callable(loader):
                return loader(model_id, device=device)
        raise RuntimeError(f"{self.dependency_name} adapter has no model loader for {model_id}")


class _ImportingModelAdapter:
    def __init__(self, dependency_name: str) -> None:
        self.dependency_name = dependency_name

    def synthesize(self, model: Any, job: TtsJob, prepared: PreparedVoice, destination: Path) -> Path:
        try:
            module = importlib.import_module(self.dependency_name)
        except ImportError as error:
            raise RuntimeError(f"{self.dependency_name} is not installed") from error
        function = getattr(module, "synthesize", None)
        if callable(function):
            result = function(model, job.text, job.preset.language, prepared, destination)
            return Path(result or destination)
        for name in ("synthesize", "generate", "infer"):
            method = getattr(model, name, None)
            if callable(method):
                result = method(
                    job.text,
                    output_path=str(destination),
                    language=job.preset.language,
                    reference_audio=str(prepared.reference_audio_path)
                    if prepared.reference_audio_path else None,
                    reference_text=prepared.reference_text,
                    style_prompt=job.preset.style_prompt,
                )
                return Path(result or destination)
        raise RuntimeError(
            f"{self.dependency_name} adapter is not configured; provide model_adapter"
        )


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()
