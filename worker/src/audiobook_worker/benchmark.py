"""离线候选模型契约 benchmark；不导入模型包，也不下载权重。"""
from __future__ import annotations

import asyncio
import argparse
import json
import time
import wave
from pathlib import Path
from typing import Any

from .contracts import TtsJob, TtsPreset, VoiceProfile
from .cosyvoice_engine import CosyVoice3Engine
from .f5_engine import F5TtsEngine
from .indextts_engine import IndexTts25Engine


class _OfflineLoader:
    def load(self, model_id: str, device: str) -> object:
        return {"model_id": model_id, "device": device}


class _OfflineAdapter:
    def synthesize(self, model: Any, job: TtsJob, prepared: Any, destination: Path) -> Path:
        with wave.open(str(destination), "wb") as output:
            output.setnchannels(1)
            output.setsampwidth(2)
            output.setframerate(24_000)
            output.writeframes(b"\x00\x00" * max(240, len(job.text) * 24))
        return destination


def candidate_factories() -> dict[str, type]:
    return {"cosyvoice3": CosyVoice3Engine, "indextts-2.5": IndexTts25Engine, "f5-tts": F5TtsEngine}


async def run_offline_benchmark(samples: list[dict[str, str]], output_dir: Path) -> list[dict[str, Any]]:
    output_dir.mkdir(parents=True, exist_ok=True)
    rows: list[dict[str, Any]] = []
    for engine_id, engine_class in candidate_factories().items():
        engine = engine_class(model_loader=_OfflineLoader(), model_adapter=_OfflineAdapter())
        for sample in samples:
            job = TtsJob(
                f"benchmark-{engine_id}-{sample['id']}", "benchmark", "v1", "sample", 0, 0,
                sample["text"], TtsPreset(engine_id, engine.model_id, "default", "", sample["language"], "wav"),
                VoiceProfile("default", "offline"),
            )
            started = time.perf_counter()
            result = await engine.synthesize(job, output_dir / f"{engine_id}-{sample['id']}.wav")
            rows.append({
                "engineId": engine_id, "modelId": engine.model_id, "modelVersion": engine.model_version,
                "sampleId": sample["id"], "language": sample["language"], "category": sample["category"],
                "durationSeconds": result.duration_seconds, "sampleRate": result.sample_rate,
                "channels": result.channels, "sizeBytes": result.size_bytes,
                "elapsedSeconds": round(time.perf_counter() - started, 6), "mode": "offline-contract",
            })
    return rows


def run_from_json(source: Path, output_dir: Path, result_path: Path) -> list[dict[str, Any]]:
    samples = json.loads(source.read_text(encoding="utf-8"))
    rows = asyncio.run(run_offline_benchmark(samples, output_dir))
    result_path.parent.mkdir(parents=True, exist_ok=True)
    result_path.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return rows


def main() -> int:
    parser = argparse.ArgumentParser(description="Run the offline candidate TTS contract benchmark")
    parser.add_argument("--samples", type=Path, default=Path("examples/tts-benchmark.json"))
    parser.add_argument("--output-dir", type=Path, default=Path("artifacts/benchmark-audio"))
    parser.add_argument("--results", type=Path, default=Path("artifacts/benchmark-results.json"))
    args = parser.parse_args()
    rows = run_from_json(args.samples, args.output_dir, args.results)
    print(json.dumps({"rows": len(rows), "results": str(args.results), "mode": "offline-contract"}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
