from pathlib import Path
from typing import Protocol, runtime_checkable

from .contracts import (
    EngineCapabilities,
    GenerationResult,
    PreparedVoice,
    TtsJob,
    VoiceProfile,
)
from .runtime_probe import RuntimeProbe


@runtime_checkable
class TtsEngine(Protocol):
    engine_id: str
    capabilities: EngineCapabilities
    probe: RuntimeProbe

    async def prepare_voice(self, profile: VoiceProfile) -> PreparedVoice:
        ...

    async def synthesize(self, job: TtsJob, destination: Path) -> GenerationResult:
        ...
