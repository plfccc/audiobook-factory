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
        "!git clone -q --depth 1 https://github.com/plfccc/audiobook-factory.git /content/audiobook-factory\n!pip install -q -r /content/audiobook-factory/worker/requirements-colab.txt\n!pip install -q -e /content/audiobook-factory/worker\n",
        """from google.colab import userdata
import os

os.environ[\"AUDIOBOOK_CONTROL_URL\"] = userdata.get(\"AUDIOBOOK_CONTROL_URL\")
os.environ[\"AUDIOBOOK_WORKER_TOKEN\"] = userdata.get(\"AUDIOBOOK_WORKER_TOKEN\")
""",
        """from audiobook_worker.runtime_probe import RuntimeProbe

runtime = RuntimeProbe.detect()
if not runtime.cuda_available:
    raise RuntimeError(\"Colab GPU is unavailable; select a GPU runtime before starting the worker\")
print({\"gpu\": runtime.gpu_name, \"vram_bytes\": runtime.gpu_memory_bytes, \"cuda\": runtime.cuda_version})

import json
from pathlib import Path
samples = json.loads(Path(\"/content/audiobook-factory/examples/tts-benchmark.json\").read_text(encoding=\"utf-8\"))
print({\"benchmark_samples\": len(samples), \"mode\": \"offline-contract-ready\"})
""",
        """from audiobook_worker.colab_worker import ColabWorker

worker = ColabWorker.from_environment()
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
