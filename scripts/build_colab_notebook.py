from __future__ import annotations

import argparse
import json
from pathlib import Path

try:
    import nbformat as nbf
except ModuleNotFoundError:
    nbf = None


ROOT = Path(__file__).resolve().parents[1]
TARGET = ROOT / "notebooks" / "audiobook_factory_colab.ipynb"


def _sources() -> list[str]:
    return [
        '''from pathlib import Path
import os
import subprocess
import sys

REPO_ROOT = Path("/content/audiobook-factory")
if not REPO_ROOT.exists():
    subprocess.run([
        "git", "clone", "-q", "--depth", "1",
        "https://github.com/plfccc/audiobook-factory.git", str(REPO_ROOT)
    ], check=True)

# 权重必须由用户提前放到本地目录；候选适配器不会联网下载权重。
LOCAL_MODEL_ROOT = Path(os.environ.get("AUDIOBOOK_LOCAL_MODEL_ROOT", "/content/audiobook-models"))
LOCAL_MODEL_ROOT.mkdir(parents=True, exist_ok=True)
candidate_requirements = {
    "FunAudioLLM/Fun-CosyVoice3-0.5B-2512": REPO_ROOT / "worker" / "requirements-cosyvoice.txt",
    "IndexTeam/IndexTTS-2.5": REPO_ROOT / "worker" / "requirements-indextts.txt",
    "SWivid/F5-TTS": REPO_ROOT / "worker" / "requirements-f5.txt",
}
requirements = [REPO_ROOT / "worker" / "requirements-colab.txt"]
selected = os.environ.get("AUDIOBOOK_MODEL_ID", "Qwen/Qwen3-TTS-12Hz-1.7B-Base")
benchmark_ids = tuple(x.strip() for x in os.environ.get("BENCHMARK_MODEL_IDS", "").split(",") if x.strip())
benchmark_mode = os.environ.get("BENCHMARK_MODE", "offline-contract")
if benchmark_mode == "real-candidate" and not benchmark_ids:
    benchmark_ids = tuple(candidate_requirements)
for model_id, requirement in candidate_requirements.items():
    if model_id in {selected, *benchmark_ids}:
        if not requirement.is_file():
            raise RuntimeError(f"missing candidate dependency declaration: {requirement}")
        requirements.append(requirement)
for requirement in requirements:
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-r", str(requirement)], check=True)
subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-e", str(REPO_ROOT / "worker")], check=True)
print({"repo": str(REPO_ROOT), "local_model_root": str(LOCAL_MODEL_ROOT), "requirements": [str(x) for x in requirements]})
''',
        '''from google.colab import userdata
import os

def _required_secret(name: str) -> str:
    value = userdata.get(name)
    if not value:
        raise RuntimeError(f"Colab Secret {name} is required")
    return value

os.environ["AUDIOBOOK_CONTROL_URL"] = _required_secret("AUDIOBOOK_CONTROL_URL")
os.environ["AUDIOBOOK_WORKER_TOKEN"] = _required_secret("AUDIOBOOK_WORKER_TOKEN")
''',
        '''import asyncio
import json
import os
from pathlib import Path

from audiobook_worker.benchmark import run_from_json
from audiobook_worker.model_registry import ModelRegistry
from audiobook_worker.runtime_probe import RuntimeProbe

runtime = RuntimeProbe.detect()
print({"gpu": runtime.gpu_name, "vram_bytes": runtime.gpu_memory_bytes, "cuda": runtime.cuda_version})
registry = ModelRegistry.default()
profiles = {profile.model_id: profile for profile in registry.profiles}
candidate_ids = tuple(profile.model_id for profile in registry.profiles if profile.engine_id != "qwen3-tts")
SELECTED_MODEL_ID = os.environ.get("AUDIOBOOK_MODEL_ID", "Qwen/Qwen3-TTS-12Hz-1.7B-Base")
BENCHMARK_MODEL_IDS = tuple(x.strip() for x in os.environ.get("BENCHMARK_MODEL_IDS", "").split(",") if x.strip())
if not BENCHMARK_MODEL_IDS and SELECTED_MODEL_ID in candidate_ids:
    BENCHMARK_MODEL_IDS = (SELECTED_MODEL_ID,)
selected_model_id = SELECTED_MODEL_ID
benchmark_ids = BENCHMARK_MODEL_IDS
mode = os.environ.get("BENCHMARK_MODE", "offline-contract")

def select_candidate_profile(model_id: str | None = None):
    requested = model_id or SELECTED_MODEL_ID
    try:
        return profiles[requested]
    except KeyError as error:
        raise ValueError(f"unknown model_id: {requested}") from error

for model_id in (selected_model_id, *benchmark_ids):
    select_candidate_profile(model_id)
if mode == "real-candidate" and benchmark_ids and not os.environ.get("AUDIOBOOK_REFERENCE_AUDIO"):
    raise RuntimeError("AUDIOBOOK_REFERENCE_AUDIO is required for real-candidate benchmark")
samples = Path("/content/audiobook-factory/examples/tts-benchmark.json")
RESULT_FIELDS = ("engine", "model", "version", "sample", "language", "category", "duration", "sampleRate", "channels", "size", "elapsed", "rtf", "gpu", "vram", "startup", "failure")

def run_candidate_benchmark(model_ids=None, benchmark_mode=mode):
    requested = tuple(model_ids or BENCHMARK_MODEL_IDS)
    if not requested:
        return []
    for model_id in requested:
        select_candidate_profile(model_id)
    return run_from_json(
        samples,
        Path("/content/audiobook-factory/artifacts/benchmark-audio"),
        Path("/content/audiobook-factory/artifacts/benchmark-results.json"),
        mode=benchmark_mode,
        model_ids=requested,
        model_root=Path(os.environ.get("AUDIOBOOK_LOCAL_MODEL_ROOT", "/content/audiobook-models")),
        reference_audio=Path(os.environ["AUDIOBOOK_REFERENCE_AUDIO"]) if benchmark_mode == "real-candidate" else None,
        reference_text=os.environ.get("AUDIOBOOK_REFERENCE_TEXT", ""),
        runtime=runtime,
    )

results = run_candidate_benchmark(benchmark_ids)
print({"selected_model_id": selected_model_id, "benchmark_mode": mode, "rows": len(results)})
''',
        '''from audiobook_worker.colab_worker import ColabWorker

worker = ColabWorker.from_environment(
    model_id=selected_model_id,
    cache_dir=LOCAL_MODEL_ROOT,
)
print({"selected_model_id": selected_model_id, "run_worker": True})
await worker.run_forever()
''',
    ]


def build() -> dict:
    sources = _sources()
    if nbf is None:
        return {
            "cells": [
                {"cell_type": "code", "execution_count": None, "metadata": {}, "outputs": [], "source": source}
                for source in sources
            ],
            "metadata": {"kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"}},
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
    generated = nbf.writes(build()) if nbf is not None else json.dumps(build(), ensure_ascii=False, indent=1) + "\n"
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
