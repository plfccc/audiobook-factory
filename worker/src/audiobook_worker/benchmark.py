"""离线契约和本地候选模型 benchmark；不自动下载模型权重。"""
from __future__ import annotations

import argparse
import asyncio
import json
import time
import wave
from pathlib import Path
from typing import Any, Iterable

from ._candidate_engine import CandidateInferenceRequest
from .contracts import TtsJob, TtsPreset, VoiceProfile
from .cosyvoice_engine import CosyVoice3Engine
from .f5_engine import F5TtsEngine
from .indextts_engine import IndexTts25Engine
from .model_registry import ModelProfile, ModelRegistry
from .runtime_probe import RuntimeProbe


class _OfflineLoader:
    def load(self, model_id: str, device: str) -> object:
        return {"model_id": model_id, "device": device}


class _OfflineAdapter:
    def synthesize(
        self, model: Any, request: CandidateInferenceRequest, destination: Path
    ) -> Path:
        with wave.open(str(destination), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(24_000)
            output.writeframes(b"\x00\x00" * max(240, len(request.text) * 24))
        return destination


_CANDIDATE_FACTORIES: dict[str, type] = {
    "cosyvoice3": CosyVoice3Engine,
    "indextts-2.5": IndexTts25Engine,
    "f5-tts": F5TtsEngine,
}
_OFFLINE_RUNTIME = RuntimeProbe(False, None, 0, None, "offline", "3.11")


def _candidate_profiles(model_ids: Iterable[str] | None = None) -> tuple[ModelProfile, ...]:
    profiles = tuple(
        profile
        for profile in ModelRegistry.default().profiles
        if profile.engine_id in _CANDIDATE_FACTORIES
    )
    if model_ids is None:
        return profiles
    requested = tuple(model_ids)
    by_model_id = {profile.model_id: profile for profile in profiles}
    unknown = [model_id for model_id in requested if model_id not in by_model_id]
    if unknown:
        raise ValueError(
            "unknown candidate model_id(s): "
            + ", ".join(unknown)
            + "; choose from: "
            + ", ".join(by_model_id)
        )
    return tuple(by_model_id[model_id] for model_id in requested)


def candidate_factories(model_ids: Iterable[str] | None = None) -> dict[str, type]:
    """返回严格按 model_id 选择的候选 factory。"""

    return {
        profile.engine_id: _CANDIDATE_FACTORIES[profile.engine_id]
        for profile in _candidate_profiles(model_ids)
    }


def _parameters_json(sample: dict[str, Any]) -> str:
    value = sample.get("parameters_json", sample.get("parametersJson", "{}"))
    if isinstance(value, str):
        return value
    return json.dumps(value, ensure_ascii=False)


def _job(
    profile: ModelProfile,
    sample: dict[str, Any],
    *,
    reference_audio: Path | None = None,
    reference_text: str | None = None,
) -> TtsJob:
    sample_id = str(sample["id"])
    voice_profile = VoiceProfile(
        profile_id=str(sample.get("voice_profile_id", "benchmark-default")),
        name="benchmark",
        reference_audio_path=reference_audio,
        reference_text=reference_text or sample.get("reference_text"),
        design_prompt=sample.get("design_prompt"),
    )
    preset = TtsPreset(
        profile.engine_id,
        profile.model_id,
        str(sample.get("voice", "default")),
        str(sample.get("style_prompt", "")),
        str(sample["language"]),
        "wav",
        model_version=profile.model_version,
        voice_profile_id=voice_profile.profile_id,
        parameters_json=_parameters_json(sample),
    )
    return TtsJob(
        f"benchmark-{profile.engine_id}-{sample_id}",
        "benchmark",
        "v1",
        "sample",
        0,
        0,
        str(sample["text"]),
        preset,
        voice_profile,
    )


def _metrics_row(
    profile: ModelProfile,
    sample: dict[str, Any],
    *,
    elapsed: float,
    duration: float | None,
    sample_rate: int | None,
    channels: int | None,
    size: int | None,
    runtime: RuntimeProbe,
    mode: str,
    startup: float | None,
    failure: str | None,
) -> dict[str, Any]:
    elapsed_value = round(elapsed, 6)
    canonical = {
        "engine": profile.engine_id,
        "model": profile.model_id,
        "version": profile.model_version,
        "sample": str(sample["id"]),
        "language": str(sample["language"]),
        "category": str(sample.get("category", "")),
        "duration": duration,
        "sampleRate": sample_rate,
        "channels": channels,
        "size": size,
        "elapsed": elapsed_value,
        "rtf": round(elapsed / duration, 6) if duration and duration > 0 else None,
        "gpu": runtime.gpu_name,
        "vram": runtime.gpu_memory_bytes,
        "startup": round(startup, 6) if startup is not None else None,
        "failure": failure,
        "mode": mode,
    }
    canonical.update(
        {
            "engineId": canonical["engine"],
            "modelId": canonical["model"],
            "modelVersion": canonical["version"],
            "sampleId": canonical["sample"],
            "durationSeconds": canonical["duration"],
            "sizeBytes": canonical["size"],
            "elapsedSeconds": canonical["elapsed"],
        }
    )
    return canonical


async def run_offline_benchmark(
    samples: list[dict[str, Any]],
    output_dir: Path,
    model_ids: Iterable[str] | None = None,
    *,
    runtime: RuntimeProbe | None = None,
) -> list[dict[str, Any]]:
    """只验证统一契约，不加载候选依赖或模型权重。"""

    output_dir.mkdir(parents=True, exist_ok=True)
    runtime = runtime or _OFFLINE_RUNTIME
    rows: list[dict[str, Any]] = []
    for profile in _candidate_profiles(model_ids):
        engine = _CANDIDATE_FACTORIES[profile.engine_id](
            model_loader=_OfflineLoader(),
            model_adapter=_OfflineAdapter(),
            probe=runtime,
        )
        for index, sample in enumerate(samples):
            job = _job(profile, sample)
            destination = output_dir / f"{profile.engine_id}-{sample['id']}.wav"
            started = time.perf_counter()
            try:
                result = await engine.synthesize(job, destination)
                elapsed = time.perf_counter() - started
                rows.append(
                    _metrics_row(
                        profile,
                        sample,
                        elapsed=elapsed,
                        duration=result.duration_seconds,
                        sample_rate=result.sample_rate,
                        channels=result.channels,
                        size=result.size_bytes,
                        runtime=runtime,
                        mode="offline-contract",
                        startup=elapsed if index == 0 else None,
                        failure=None,
                    )
                )
            except Exception as error:
                elapsed = time.perf_counter() - started
                rows.append(
                    _metrics_row(
                        profile,
                        sample,
                        elapsed=elapsed,
                        duration=None,
                        sample_rate=None,
                        channels=None,
                        size=None,
                        runtime=runtime,
                        mode="offline-contract",
                        startup=elapsed if index == 0 else None,
                        failure=f"{type(error).__name__}: {error}",
                    )
                )
    return rows


def _validate_reference_audio(reference_audio: Path) -> None:
    if reference_audio.is_symlink() or not reference_audio.is_file():
        raise RuntimeError("real-candidate benchmark reference_audio must be a regular local file")
    if reference_audio.stat().st_size <= 0:
        raise RuntimeError("real-candidate benchmark reference_audio must not be empty")


def _validate_local_model_path(profile: ModelProfile, model_path: Path) -> None:
    if model_path.is_symlink() or not model_path.is_dir():
        raise RuntimeError(
            f"real-candidate benchmark requires existing local model_path for "
            f"{profile.model_id}: {model_path}"
        )
    if profile.engine_id == "cosyvoice3":
        if not (model_path / "cosyvoice3.yaml").is_file():
            raise RuntimeError(
                f"cosyvoice model_path must contain a CosyVoice config: {model_path}"
            )
    elif profile.engine_id == "indextts-2.5":
        if not (model_path / "config.yaml").is_file():
            raise RuntimeError(
                f"indextts model_path must contain config.yaml: {model_path}"
            )
        aux_root = model_path / "aux"
        required_aux = (
            aux_root / "w2v-bert-2.0",
            aux_root / "semantic_codec_model.safetensors",
            aux_root / "campplus_cn_common.bin",
            aux_root / "bigvgan",
        )
        if not (
            required_aux[0].is_dir()
            and required_aux[1].is_file()
            and required_aux[2].is_file()
            and required_aux[3].is_dir()
        ):
            raise RuntimeError(
                f"indextts model_path must contain all local aux assets under aux/: {model_path}"
            )
    elif profile.engine_id == "f5-tts":
        if not any(
            path.is_file()
            and path.suffix.lower() in {".safetensors", ".ckpt", ".bin", ".pth"}
            for path in model_path.iterdir()
        ):
            raise RuntimeError(
                f"f5_tts model_path must contain a local checkpoint: {model_path}"
            )
        if not (model_path / "vocos").is_dir():
            raise RuntimeError(
                f"f5_tts model_path must contain a local vocoder directory at vocos/: {model_path}"
            )


async def run_real_benchmark(
    samples: list[dict[str, Any]],
    output_dir: Path,
    *,
    model_root: Path,
    model_ids: Iterable[str] | None = None,
    reference_audio: Path,
    reference_text: str = "",
    runtime: RuntimeProbe | None = None,
    device: str = "cuda:0",
) -> list[dict[str, Any]]:
    """在已准备好的本地模型上运行真实候选推理；缺资源直接失败。"""

    runtime = runtime or RuntimeProbe.detect()
    if runtime is None or not runtime.cuda_available:
        raise RuntimeError("real-candidate benchmark requires a CUDA GPU runtime")
    model_root = Path(model_root)
    if model_root.is_symlink() or not model_root.is_dir():
        raise RuntimeError(
            f"real-candidate benchmark requires an existing local model root: {model_root}"
        )
    reference_audio = Path(reference_audio)
    _validate_reference_audio(reference_audio)
    profiles = _candidate_profiles(model_ids)
    for profile in profiles:
        _validate_local_model_path(profile, model_root / profile.engine_id)

    output_dir.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, Any]] = []
    for profile in profiles:
        model_path = model_root / profile.engine_id
        engine = _CANDIDATE_FACTORIES[profile.engine_id](
            probe=runtime,
            device=device,
            model_path=model_path,
        )
        startup: float | None = None
        for sample in samples:
            job = _job(
                profile,
                sample,
                reference_audio=reference_audio,
                reference_text=reference_text,
            )
            destination = output_dir / f"{profile.engine_id}-{sample['id']}.wav"
            started = time.perf_counter()
            try:
                result = await engine.synthesize(job, destination)
                elapsed = time.perf_counter() - started
                if startup is None:
                    startup = elapsed
                rows.append(
                    _metrics_row(
                        profile,
                        sample,
                        elapsed=elapsed,
                        duration=result.duration_seconds,
                        sample_rate=result.sample_rate,
                        channels=result.channels,
                        size=result.size_bytes,
                        runtime=runtime,
                        mode="real-candidate",
                        startup=startup,
                        failure=None,
                    )
                )
            except Exception as error:
                elapsed = time.perf_counter() - started
                if startup is None:
                    startup = elapsed
                rows.append(
                    _metrics_row(
                        profile,
                        sample,
                        elapsed=elapsed,
                        duration=None,
                        sample_rate=None,
                        channels=None,
                        size=None,
                        runtime=runtime,
                        mode="real-candidate",
                        startup=startup,
                        failure=f"{type(error).__name__}: {error}",
                    )
                )
    return rows


async def run_benchmark(
    samples: list[dict[str, Any]],
    output_dir: Path,
    *,
    mode: str = "offline-contract",
    model_ids: Iterable[str] | None = None,
    model_root: Path | None = None,
    reference_audio: Path | None = None,
    reference_text: str = "",
    runtime: RuntimeProbe | None = None,
    device: str = "cuda:0",
) -> list[dict[str, Any]]:
    if mode == "offline-contract":
        return await run_offline_benchmark(
            samples, output_dir, model_ids=model_ids, runtime=runtime
        )
    if mode == "real-candidate":
        if model_root is None:
            raise RuntimeError("real-candidate benchmark requires --model-root")
        if reference_audio is None:
            raise RuntimeError("real-candidate benchmark requires --reference-audio")
        return await run_real_benchmark(
            samples,
            output_dir,
            model_root=model_root,
            model_ids=model_ids,
            reference_audio=reference_audio,
            reference_text=reference_text,
            runtime=runtime,
            device=device,
        )
    raise ValueError(f"unsupported benchmark mode: {mode}")


def run_from_json(
    source: Path,
    output_dir: Path,
    result_path: Path,
    *,
    mode: str = "offline-contract",
    model_ids: Iterable[str] | None = None,
    model_root: Path | None = None,
    reference_audio: Path | None = None,
    reference_text: str = "",
    runtime: RuntimeProbe | None = None,
    device: str = "cuda:0",
) -> list[dict[str, Any]]:
    samples = json.loads(source.read_text(encoding="utf-8"))
    rows = asyncio.run(
        run_benchmark(
            samples,
            output_dir,
            mode=mode,
            model_ids=model_ids,
            model_root=model_root,
            reference_audio=reference_audio,
            reference_text=reference_text,
            runtime=runtime,
            device=device,
        )
    )
    result_path.parent.mkdir(parents=True, exist_ok=True)
    result_path.write_text(
        json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return rows


def _csv_model_ids(value: str | None) -> tuple[str, ...] | None:
    if value is None:
        return None
    model_ids = tuple(part.strip() for part in value.split(",") if part.strip())
    if not model_ids:
        raise argparse.ArgumentTypeError("--model-ids must contain at least one model_id")
    return model_ids


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the candidate TTS benchmark")
    parser.add_argument(
        "--mode",
        choices=("offline-contract", "real-candidate"),
        default="offline-contract",
    )
    parser.add_argument("--model-ids", type=_csv_model_ids)
    parser.add_argument("--samples", type=Path, default=Path("examples/tts-benchmark.json"))
    parser.add_argument("--output-dir", type=Path, default=Path("artifacts/benchmark-audio"))
    parser.add_argument("--results", type=Path, default=Path("artifacts/benchmark-results.json"))
    parser.add_argument("--model-root", type=Path, default=Path("/content/audiobook-models"))
    parser.add_argument("--reference-audio", type=Path)
    parser.add_argument("--reference-text", default="")
    parser.add_argument("--device", default="cuda:0")
    args = parser.parse_args()
    rows = run_from_json(
        args.samples,
        args.output_dir,
        args.results,
        mode=args.mode,
        model_ids=args.model_ids,
        model_root=args.model_root,
        reference_audio=args.reference_audio,
        reference_text=args.reference_text,
        device=args.device,
    )
    print(json.dumps({"rows": len(rows), "results": str(args.results), "mode": args.mode}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
