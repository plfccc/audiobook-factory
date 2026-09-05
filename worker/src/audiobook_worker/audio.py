from dataclasses import dataclass
from hashlib import sha256
import json
import math
from pathlib import Path
import subprocess

from .errors import ErrorCode, WorkerError

_HASH_BLOCK_SIZE = 1024 * 1024
_MINIMUM_AUDIO_SIZE = 1024
_SUBPROCESS_TIMEOUT_SECONDS = 60


@dataclass(frozen=True)
class AudioMetadata:
    codec: str
    sample_rate: int
    channels: int
    duration_seconds: float
    size_bytes: int
    sha256: str


class AudioValidator:
    def validate(self, path: Path) -> AudioMetadata:
        path = Path(path)
        try:
            size_bytes = path.stat().st_size
        except OSError as exc:
            raise _invalid(f"file is missing or unreadable: {path}") from exc
        if not path.is_file():
            raise _invalid(f"path is not a file: {path}")
        if size_bytes < _MINIMUM_AUDIO_SIZE:
            raise _invalid(f"file is smaller than 1 KiB: {path}")

        probe = _run(
            [
                "ffprobe",
                "-v",
                "error",
                "-show_entries",
                "format=duration:stream=codec_name,sample_rate,channels",
                "-of",
                "json",
                str(path),
            ],
            "ffprobe",
        )
        if probe.returncode != 0:
            raise _invalid(f"ffprobe failed for {path}: {_detail(probe.stderr)}")

        codec, sample_rate, channels, duration_seconds = _parse_probe(probe.stdout)

        decode = _run(
            ["ffmpeg", "-v", "error", "-i", str(path), "-f", "null", "-"],
            "ffmpeg decode",
        )
        if decode.returncode != 0:
            raise _invalid(f"decode failed for {path}: {_detail(decode.stderr)}")

        try:
            file_sha256 = _sha256(path)
        except OSError as exc:
            raise _invalid(f"could not hash audio file {path}: {exc}") from exc

        return AudioMetadata(
            codec=codec,
            sample_rate=sample_rate,
            channels=channels,
            duration_seconds=duration_seconds,
            size_bytes=size_bytes,
            sha256=file_sha256,
        )


def _run(args: list[str], operation: str) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(
            args,
            capture_output=True,
            text=True,
            check=False,
            timeout=_SUBPROCESS_TIMEOUT_SECONDS,
        )
    except subprocess.TimeoutExpired as exc:
        raise _invalid(f"{operation} timed out after {_SUBPROCESS_TIMEOUT_SECONDS} seconds") from exc
    except OSError as exc:
        raise _invalid(f"{operation} could not run: {exc}") from exc


def _parse_probe(stdout: str) -> tuple[str, int, int, float]:
    try:
        payload = json.loads(stdout)
        duration_seconds = float(payload["format"]["duration"])
        streams = payload["streams"]
    except (KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
        raise _invalid("ffprobe returned malformed metadata") from exc

    if not math.isfinite(duration_seconds) or duration_seconds <= 0:
        raise _invalid("ffprobe returned a non-positive duration")
    if not isinstance(streams, list) or not streams:
        raise _invalid("ffprobe returned no audio stream")

    for stream in streams:
        try:
            codec = stream["codec_name"]
            sample_rate = int(stream["sample_rate"])
            channels = int(stream["channels"])
        except (KeyError, TypeError, ValueError):
            continue
        if isinstance(codec, str) and codec.strip() and sample_rate > 0 and channels > 0:
            return codec, sample_rate, channels, duration_seconds
    raise _invalid("ffprobe audio stream is missing required fields")


def _sha256(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(_HASH_BLOCK_SIZE), b""):
            digest.update(block)
    return digest.hexdigest()


def _detail(stderr: str) -> str:
    return stderr.strip() or "no diagnostic output"


def _invalid(message: str) -> WorkerError:
    return WorkerError(ErrorCode.AUDIO_INVALID, f"AUDIO_INVALID: {message}", retryable=True)
