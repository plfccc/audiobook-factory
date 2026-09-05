from dataclasses import dataclass
from typing import Iterable

from .contracts import EngineCapabilities
from .runtime_probe import RuntimeProbe


@dataclass(frozen=True)
class ModelProfile:
    engine_id: str
    model_id: str
    model_version: str
    minimum_vram_bytes: int
    priority: int
    capabilities: EngineCapabilities
    license_url: str = ""


class ModelRegistry:
    def __init__(self, profiles: Iterable[ModelProfile]):
        self._profiles = tuple(profiles)

    @property
    def models(self) -> tuple[ModelProfile, ...]:
        return self._profiles

    @property
    def profiles(self) -> tuple[ModelProfile, ...]:
        return self._profiles

    @classmethod
    def default(cls) -> "ModelRegistry":
        return cls(
            (
                ModelProfile(
                    engine_id="qwen3-tts",
                    model_id="Qwen/Qwen3-TTS-12Hz-1.7B-Base",
                    model_version="1.0",
                    minimum_vram_bytes=8 * 1024**3,
                    priority=500,
                    capabilities=EngineCapabilities(
                        languages=("zh-CN", "en-US"),
                        voice_design=False,
                        voice_clone=True,
                        emotion_control=False,
                        duration_control=False,
                    ),
                    license_url="https://github.com/QwenLM/Qwen3-TTS/blob/main/LICENSE",
                ),
                ModelProfile(
                    engine_id="qwen3-tts",
                    model_id="Qwen/Qwen3-TTS-12Hz-0.6B-Base",
                    model_version="1.0",
                    minimum_vram_bytes=4 * 1024**3,
                    priority=400,
                    capabilities=EngineCapabilities(
                        languages=("zh-CN", "en-US"),
                        voice_design=False,
                        voice_clone=True,
                        emotion_control=False,
                        duration_control=False,
                    ),
                    license_url="https://github.com/QwenLM/Qwen3-TTS/blob/main/LICENSE",
                ),
                ModelProfile(
                    engine_id="cosyvoice3",
                    model_id="FunAudioLLM/Fun-CosyVoice3-0.5B-2512",
                    model_version="3.0",
                    minimum_vram_bytes=8 * 1024**3,
                    priority=300,
                    capabilities=EngineCapabilities(
                        languages=("zh-CN", "en-US"),
                        voice_design=False,
                        voice_clone=True,
                        emotion_control=True,
                        duration_control=False,
                    ),
                    license_url="https://github.com/FunAudioLLM/CosyVoice/blob/main/LICENSE",
                ),
                ModelProfile(
                    engine_id="indextts-2.5",
                    model_id="IndexTeam/IndexTTS-2.5",
                    model_version="2.5",
                    minimum_vram_bytes=8 * 1024**3,
                    priority=200,
                    capabilities=EngineCapabilities(
                        languages=("zh-CN", "en-US"),
                        voice_design=False,
                        voice_clone=True,
                        emotion_control=True,
                        duration_control=True,
                    ),
                    license_url="https://github.com/index-tts/index-tts/blob/main/LICENSE",
                ),
                ModelProfile(
                    engine_id="f5-tts",
                    model_id="SWivid/F5-TTS",
                    model_version="1.0",
                    minimum_vram_bytes=6 * 1024**3,
                    priority=100,
                    capabilities=EngineCapabilities(
                        languages=("zh-CN", "en-US"),
                        voice_design=False,
                        voice_clone=True,
                        emotion_control=False,
                        duration_control=False,
                    ),
                    license_url="https://github.com/SWivid/F5-TTS/blob/main/LICENSE",
                ),
            )
        )

    def select(
        self, probe: RuntimeProbe, requested_engine: str | None = None
    ) -> ModelProfile | None:
        if not probe.cuda_available:
            return None

        compatible = (
            profile
            for profile in self._profiles
            if profile.minimum_vram_bytes <= probe.gpu_memory_bytes
            and (
                requested_engine is None
                or profile.engine_id == requested_engine
            )
        )
        return max(compatible, key=lambda profile: profile.priority, default=None)
