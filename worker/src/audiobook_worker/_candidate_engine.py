from __future__ import annotations

import asyncio
import hashlib
from pathlib import Path
from typing import Any

from .contracts import EngineCapabilities, GenerationResult, PreparedVoice, TtsJob, VoiceProfile
from .runtime_probe import RuntimeProbe


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
        self._model_adapter = model_adapter
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
        if reference is not None and Path(reference).is_file():
            digest = hashlib.sha256(Path(reference).read_bytes()).hexdigest()
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
        if job.preset.output_format.lower() != "wav":
            raise ValueError(f"{self.__class__.__name__} only supports WAV output")
        model = await asyncio.to_thread(self._load_model)
        prepared = await self.prepare_voice(job.voice_profile or VoiceProfile(
            profile_id="default", name="default", reference_text=""
        ))
        output = Path(destination)
        output.parent.mkdir(parents=True, exist_ok=True)
        adapter = self._model_adapter or _DefaultCandidateAdapter(self.dependency_name)
        generated = await asyncio.to_thread(adapter.synthesize, model, job, prepared, output)
        output = Path(generated or output)
        if not output.is_file() or output.stat().st_size == 0:
            raise RuntimeError(f"{self.__class__.__name__} did not produce WAV output")
        digest = hashlib.sha256(output.read_bytes()).hexdigest()
        return GenerationResult(job.job_id, output, digest, output.stat().st_size, 0.0, 0, 0)

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
            __import__(self.dependency_name)
        except ImportError as error:
            raise RuntimeError(
                f"{self.dependency_name} is not installed; install the optional Colab model package before using this engine"
            ) from error
        raise RuntimeError(
            f"{self.dependency_name} adapter is not configured for model {model_id}; no weights were downloaded"
        )


class _DefaultCandidateAdapter:
    def __init__(self, dependency_name: str) -> None:
        self.dependency_name = dependency_name

    def synthesize(self, model: Any, job: TtsJob, prepared: PreparedVoice, destination: Path) -> Path:
        raise RuntimeError(f"{self.dependency_name} adapter is not configured; provide a model_adapter")
