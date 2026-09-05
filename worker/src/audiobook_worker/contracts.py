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
class EngineCapabilities:
    languages: tuple[str, ...]
    voice_design: bool
    voice_clone: bool
    emotion_control: bool
    duration_control: bool


@dataclass(frozen=True)
class VoiceProfile:
    profile_id: str
    name: str
    reference_audio_path: Path | None = None
    reference_text: str | None = None
    design_prompt: str | None = None

    def __post_init__(self) -> None:
        _required(self.profile_id, "profile_id")


@dataclass(frozen=True)
class PreparedVoice:
    profile_id: str
    cache_key: str
    reference_audio_path: Path | None = None
    reference_text: str | None = None
    design_prompt: str | None = None
    clone_prompt: str | None = None

    def __post_init__(self) -> None:
        _required(self.profile_id, "profile_id")
        _required(self.cache_key, "cache_key")


@dataclass(frozen=True)
class TtsPreset:
    provider: str
    model: str
    voice: str
    style_prompt: str
    language: str
    output_format: str
    model_version: str = "unspecified"
    voice_profile_id: str | None = None
    parameters_json: str = "{}"
    segment_target_chars: int = 220
    segment_max_chars: int = 320

    def __post_init__(self) -> None:
        for name in ("provider", "model", "voice", "language", "output_format"):
            _required(getattr(self, name), name)
        if self.output_format.lower() not in _FORMATS:
            raise ValueError(f"unsupported output_format: {self.output_format}")
        if self.model_version is not None:
            _required(self.model_version, "model_version")
        if self.voice_profile_id is not None:
            _required(self.voice_profile_id, "voice_profile_id")
        if not isinstance(self.parameters_json, str):
            raise ValueError("parameters_json must be valid JSON")
        try:
            json.loads(self.parameters_json)
        except json.JSONDecodeError as exc:
            raise ValueError("parameters_json must be valid JSON") from exc
        if type(self.segment_target_chars) is not int or self.segment_target_chars <= 0:
            raise ValueError("segment_target_chars must be a positive integer")
        if type(self.segment_max_chars) is not int or self.segment_max_chars <= 0:
            raise ValueError("segment_max_chars must be a positive integer")
        if self.segment_target_chars > self.segment_max_chars:
            raise ValueError(
                "segment_target_chars must not exceed segment_max_chars"
            )


@dataclass(frozen=True)
class TtsJob:
    job_id: str
    book_id: str
    book_version_id: str
    chapter_id: str
    chapter_index: int
    segment_index: int
    text: str
    preset: TtsPreset
    voice_profile: VoiceProfile | None = None

    def __post_init__(self) -> None:
        for name in ("job_id", "book_id", "book_version_id", "chapter_id"):
            _required(getattr(self, name), name)
        for name in ("chapter_index", "segment_index"):
            value = getattr(self, name)
            if type(value) is not int or value < 0:
                raise ValueError(f"{name} must be a non-negative integer")
        _required(self.text, "text")
        if (
            self.voice_profile is not None
            and self.preset.voice_profile_id is not None
            and self.voice_profile.profile_id != self.preset.voice_profile_id
        ):
            raise ValueError(
                "voice_profile.profile_id must match preset.voice_profile_id"
            )


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
