from __future__ import annotations

from hashlib import sha256
from pathlib import Path
import shutil

from .contracts import VoiceProfile


_HASH_BLOCK_SIZE = 1024 * 1024


def sha256_file(path: Path | str) -> str:
    """返回 Colab 临时音频缓存使用的内容哈希。"""

    digest = sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(_HASH_BLOCK_SIZE), b""):
            digest.update(block)
    return digest.hexdigest()


class VoiceProfileStore:
    """将参考音频物化到按内容寻址的本地目录。"""

    def materialize(self, profile: VoiceProfile, target_dir: Path | str) -> Path:
        if profile.reference_audio_path is None:
            raise ValueError(
                "reference_audio_path is required to materialize a voice profile"
            )
        return self.materialize_path(profile.reference_audio_path, target_dir)

    def materialize_path(
        self, source_path: Path | str, target_dir: Path | str
    ) -> Path:
        source = Path(source_path)
        if not source.exists():
            raise FileNotFoundError(source)
        if not source.is_file():
            raise ValueError(f"reference audio is not a file: {source}")

        content_hash = sha256_file(source)
        suffix = source.suffix.lower() or ".wav"
        target = Path(target_dir) / f"{content_hash}{suffix}"
        target.parent.mkdir(parents=True, exist_ok=True)

        if source.resolve() != target.resolve():
            if not target.exists() or sha256_file(target) != content_hash:
                shutil.copy2(source, target)
        return target
