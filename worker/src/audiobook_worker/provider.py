from pathlib import Path
from typing import Protocol

from .contracts import GenerationRequest, RuntimeStatus


class TtsProvider(Protocol):
    async def health_check(self) -> RuntimeStatus: ...

    async def check_auth(self) -> RuntimeStatus: ...

    async def generate(
        self, request: GenerationRequest, destination: Path
    ) -> Path: ...
