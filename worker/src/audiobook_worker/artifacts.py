from dataclasses import asdict
from hashlib import sha256
import json
import os
from pathlib import Path
import shutil
import tempfile

from .audio import AudioMetadata, AudioValidator
from .contracts import GenerationRequest
from .errors import WorkerError

_COPY_BLOCK_SIZE = 1024 * 1024


class ArtifactStore:
    def publish(
        self,
        temp_path: Path,
        request: GenerationRequest,
        metadata: AudioMetadata,
    ) -> Path:
        temp_path = Path(temp_path)
        _validate_request_id(request.request_id)

        output_dir = Path(request.output_dir)
        final_path = output_dir / f"{request.request_id}.wav"
        manifest_path = final_path.with_suffix(".json")
        expected_identity = {
            "request_id": request.request_id,
            "text_sha256": request.text_sha256,
            "preset_snapshot_sha256": request.preset_snapshot_sha256,
        }
        expected_manifest = {
            **expected_identity,
            "metadata": asdict(metadata),
        }

        existing = _existing_artifact(final_path, manifest_path, expected_identity)
        if existing is not None:
            return existing
        _verify_source(temp_path, metadata)

        output_dir.mkdir(parents=True, exist_ok=True)
        staged_audio = _temporary_sibling(final_path)
        staged_manifest: Path | None = None
        published_audio = False
        try:
            with temp_path.open("rb") as source, staged_audio.open("wb") as destination:
                shutil.copyfileobj(source, destination, length=_COPY_BLOCK_SIZE)
                destination.flush()
                os.fsync(destination.fileno())
            _verify_source(staged_audio, metadata)

            existing = _existing_artifact(final_path, manifest_path, expected_identity)
            if existing is not None:
                return existing

            os.replace(staged_audio, final_path)
            published_audio = True

            staged_manifest = _temporary_sibling(manifest_path)
            with staged_manifest.open("w", encoding="utf-8") as manifest_file:
                json.dump(expected_manifest, manifest_file, ensure_ascii=False, indent=2, sort_keys=True)
                manifest_file.write("\n")
                manifest_file.flush()
                os.fsync(manifest_file.fileno())
            os.replace(staged_manifest, manifest_path)
            _fsync_directory(output_dir)
            temp_path.unlink()
            return final_path
        except Exception:
            if published_audio and not manifest_path.exists() and _matches_metadata(final_path, metadata):
                final_path.unlink()
            raise
        finally:
            staged_audio.unlink(missing_ok=True)
            if staged_manifest is not None:
                staged_manifest.unlink(missing_ok=True)


def _existing_artifact(
    final_path: Path,
    manifest_path: Path,
    expected_identity: dict[str, str],
) -> Path | None:
    audio_exists = final_path.exists()
    manifest_exists = manifest_path.exists()
    if not audio_exists and not manifest_exists:
        return None
    if not audio_exists or not manifest_exists:
        raise FileExistsError(f"incomplete artifact already exists for {final_path.stem}")

    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise FileExistsError(f"unreadable manifest already exists: {manifest_path}") from exc
    if not isinstance(manifest, dict) or any(manifest.get(key) != value for key, value in expected_identity.items()):
        raise FileExistsError(f"unrelated or invalid artifact already exists: {final_path}")
    try:
        recorded_metadata = AudioMetadata(**manifest["metadata"])
        actual_metadata = AudioValidator().validate(final_path)
    except (KeyError, TypeError, WorkerError) as exc:
        raise FileExistsError(f"unrelated or invalid artifact already exists: {final_path}") from exc
    if actual_metadata != recorded_metadata:
        raise FileExistsError(f"unrelated or invalid artifact already exists: {final_path}")
    return final_path


def _verify_source(path: Path, metadata: AudioMetadata) -> None:
    if not _matches_metadata(path, metadata):
        raise ValueError(f"audio metadata does not match source: {path}")


def _matches_metadata(path: Path, metadata: AudioMetadata) -> bool:
    try:
        return path.is_file() and path.stat().st_size == metadata.size_bytes and _sha256(path) == metadata.sha256
    except OSError:
        return False


def _sha256(path: Path) -> str:
    digest = sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(_COPY_BLOCK_SIZE), b""):
            digest.update(block)
    return digest.hexdigest()


def _temporary_sibling(path: Path) -> Path:
    descriptor, name = tempfile.mkstemp(prefix=f".{path.stem}.", suffix=".tmp", dir=path.parent)
    os.close(descriptor)
    return Path(name)


def _validate_request_id(request_id: str) -> None:
    if request_id in {".", ".."} or Path(request_id).name != request_id:
        raise ValueError("request_id must be a single safe path component")


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
