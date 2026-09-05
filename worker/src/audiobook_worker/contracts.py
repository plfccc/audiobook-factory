from dataclasses import dataclass
from hashlib import sha256
import json
from pathlib import Path

from .errors import ErrorCode

_FORMATS = frozenset({"wav", "mp3", "m4b"})


def _required(value: str, name: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError(f"{name} must not be blank")
    return value


@dataclass(frozen=True)
class TtsPreset:
    provider: str
    model: str
    voice: str
    style_prompt: str
    language: str
    output_format: str

    def __post_init__(self) -> None:
        for name in ("provider", "model", "voice", "language", "output_format"):
            _required(getattr(self, name), name)
        if self.output_format.lower() not in _FORMATS:
            raise ValueError(f"unsupported output_format: {self.output_format}")


@dataclass(frozen=True)
class GenerationRequest:
    request_id: str
    text: str
    preset: TtsPreset
    output_dir: Path

    def __post_init__(self) -> None:
        _required(self.request_id, "request_id")
        _required(self.text, "text")

    @property
    def text_sha256(self) -> str:
        return sha256(self.text.encode("utf-8")).hexdigest()

    @property
    def preset_snapshot_sha256(self) -> str:
        payload = {name: getattr(self.preset, name) for name in self.preset.__dataclass_fields__}
        encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
        return sha256(encoded).hexdigest()


@dataclass(frozen=True)
class GenerationResult:
    request_id: str
    output_path: Path
    sha256: str
    size_bytes: int
    duration_seconds: float
    sample_rate: int
    channels: int


@dataclass(frozen=True)
class RuntimeStatus:
    ok: bool
    code: ErrorCode | None
    message: str
