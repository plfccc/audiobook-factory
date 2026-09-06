from __future__ import annotations

import argparse
import json
from pathlib import Path

try:
    import nbformat as nbf
except ModuleNotFoundError:  # 本地最小环境仍可构建合法 nbformat JSON。
    nbf = None


ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "notebooks" / "audiobook_factory_colab.ipynb"


def build() -> dict:
    sources = [
        """from pathlib import Path
import os
import subprocess
import sys

REPO_ROOT = Path("/content/audiobook-factory")
if not REPO_ROOT.exists():
    subprocess.run(
        ["git", "clone", "-q", "--depth", "1", "https://github.com/plfccc/audiobook-factory.git", str(REPO_ROOT)],
        check=True,
    )

# Place model weights here before selecting a real candidate. Adapters never download weights.
LOCAL_MODEL_ROOT = Path(os.environ.get("AUDIOBOOK_LOCAL_MODEL_ROOT", "/content/audiobook-models"))
LOCAL_MODEL_ROOT.mkdir(parents=True, exist_ok=True)
CANDIDATE_REQUIREMENTS = {
    "cosyvoice3": REPO_ROOT / "worker" / "requirements-cosyvoice.txt",
    "indextts-2.5": REPO_ROOT / "worker" / "requirements-indextts.txt",
    "f5-tts": REPO_ROOT / "worker" / "requirements-f5.txt",
}

requirements = [REPO_ROOT / "worker" / "requirements-colab.txt"]
requirements.extend(path for path in CANDIDATE_REQUIREMENTS.values() if path.is_file())
for requirement in requirements:
    subprocess.run(
        [sys.executable, "-m", "pip", "install", "-q", "-r", str(requirement)],
        check=True,
    )
subprocess.run(
    [sys.executable, "-m", "pip", "install", "-q", "-e", str(REPO_ROOT / "worker")],
    check=True,
)
print({"repo": str(REPO_ROOT), "local_model_root": str(LOCAL_MODEL_ROOT), "candidate_requirements": [str(path) for path in requirements[1:]]})
""",
        """from google.colab import userdata
import os

def _required_secret(name: str) -> str:
    value = userdata.get(name)
    if not value:
        raise RuntimeError(f"Colab Secret {name} is required")
    return value

os.environ["AUDIOBOOK_CONTROL_URL"] = _required_secret("AUDIOBOOK_CONTROL_URL")
os.environ["AUDIOBOOK_WORKER_TOKEN"] = _required_secret("AUDIOBOOK_WORKER_TOKEN")
""",
        """from audiobook_worker.runtime_probe import RuntimeProbe
from audiobook_worker.benchmark import run_offline_benchmark
from audiobook_worker.model_registry import ModelRegistry

runtime = RuntimeProbe.detect()
print({\"gpu\": runtime.gpu_name, \"vram_bytes\": runtime.gpu_memory_bytes, \"cuda\": runtime.cuda_version})

import json
import time
from pathlib import Path

samples = json.loads(Path(\"/content/audiobook-factory/examples/tts-benchmark.json\").read_text(encoding=\"utf-8\"))
registry = ModelRegistry.default()
candidate_profiles = tuple(
    profile for profile in registry.profiles
    if profile.engine_id in {\"cosyvoice3\", \"indextts-2.5\", \"f5-tts\"}
)
candidate_by_model_id = {profile.model_id: profile for profile in candidate_profiles}
CANDIDATE_MODEL_IDS = tuple(candidate_by_model_id)
SELECTED_MODEL_ID = os.environ.get(\"AUDIOBOOK_MODEL_ID\", CANDIDATE_MODEL_IDS[0])
BENCHMARK_MODEL_IDS = tuple(
    model_id.strip()
    for model_id in os.environ.get(\"BENCHMARK_MODEL_IDS\", ",".join(CANDIDATE_MODEL_IDS)).split(",")
    if model_id.strip()
)
BENCHMARK_MODE = os.environ.get(\"BENCHMARK_MODE\", \"offline-contract\")
RUN_WORKER = os.environ.get(\"RUN_WORKER\", \"1\") == \"1\"

def select_candidate_profile(model_id: str | None = None):
    requested = model_id or SELECTED_MODEL_ID
    try:
        return candidate_by_model_id[requested]
    except KeyError as error:
        choices = ", ".join(CANDIDATE_MODEL_IDS)
        raise ValueError(f\"Unknown candidate model_id {requested!r}; choose one of: {choices}\") from error

def _benchmark_row(row: dict, *, model_id: str, elapsed: float) -> dict:
    duration = row.get(\"durationSeconds\")
    return {
        \"engine\": row.get(\"engineId\"),
        \"model\": row.get(\"modelId\", model_id),
        \"version\": row.get(\"modelVersion\"),
        \"sample\": row.get(\"sampleId\"),
        \"language\": row.get(\"language\"),
        \"category\": row.get(\"category\"),
        \"duration\": duration,
        \"sampleRate\": row.get(\"sampleRate\"),
        \"channels\": row.get(\"channels\"),
        \"size\": row.get(\"sizeBytes\"),
        \"elapsed\": row.get(\"elapsedSeconds\", elapsed),
        \"rtf\": (row.get(\"elapsedSeconds\", elapsed) / duration) if duration else None,
        \"gpu\": runtime.gpu_name,
        \"vram\": runtime.gpu_memory_bytes,
        \"startup\": None,
        \"failure\": None,
        \"mode\": BENCHMARK_MODE,
    }

async def run_candidate_benchmark(model_ids=None, mode=BENCHMARK_MODE):
    requested = tuple(model_ids or BENCHMARK_MODEL_IDS)
    for model_id in requested:
        select_candidate_profile(model_id)
    if mode != \"offline-contract\":
        raise ValueError(\"Only offline-contract is enabled in this notebook; provide local models before using a real route\")

    started = time.perf_counter()
    raw_rows = await run_offline_benchmark(
        samples,
        Path(\"/content/audiobook-factory/artifacts/benchmark-audio\"),
    )
    rows_by_model_id = {}
    for row in raw_rows:
        rows_by_model_id.setdefault(row[\"modelId\"], []).append(row)
    results = []
    for model_id in requested:
        candidate_rows = rows_by_model_id.get(model_id, [])
        candidate_elapsed = time.perf_counter() - started
        results.extend(
            _benchmark_row(row, model_id=model_id, elapsed=candidate_elapsed)
            for row in candidate_rows
        )
        print({\"model_id\": model_id, \"rows\": len(candidate_rows), \"mode\": mode})
    return results

print({\"benchmark_samples\": len(samples), \"candidate_model_ids\": CANDIDATE_MODEL_IDS, \"selected_model_id\": SELECTED_MODEL_ID, \"benchmark_mode\": BENCHMARK_MODE})
benchmark_results = await run_candidate_benchmark()
print({\"benchmark_rows\": len(benchmark_results), \"schema\": sorted(benchmark_results[0]) if benchmark_results else []})
""",
        """from audiobook_worker.colab_worker import ColabWorker

selected_profile = select_candidate_profile(SELECTED_MODEL_ID)
worker = ColabWorker.from_environment(
    selected_model=selected_profile,
    cache_dir=LOCAL_MODEL_ROOT,
)
print({\"selected_model_id\": selected_profile.model_id, \"engine\": selected_profile.engine_id, \"run_worker\": RUN_WORKER})
if RUN_WORKER:
    await worker.run_forever()
""",
    ]
    if nbf is None:
        return {
            "cells": [{"cell_type": "code", "execution_count": None, "metadata": {}, "outputs": [], "source": source} for source in sources],
            "metadata": {"kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"}, "language_info": {"name": "python"}},
            "nbformat": 4,
            "nbformat_minor": 5,
        }
    notebook = nbf.v4.new_notebook()
    notebook["metadata"] = {"kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"}}
    notebook["cells"] = [nbf.v4.new_code_cell(source) for source in sources]
    return notebook


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    generated = json.dumps(build(), ensure_ascii=False, indent=1) + "\n"
    if nbf is not None:
        generated = nbf.writes(build())
    if args.check:
        if not TARGET.is_file() or TARGET.read_text(encoding="utf-8") != generated:
            print(f"Notebook is stale: {TARGET}")
            return 1
        print(f"Notebook is up to date: {TARGET}")
        return 0
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    TARGET.write_text(generated, encoding="utf-8")
    print(f"Wrote {TARGET}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
