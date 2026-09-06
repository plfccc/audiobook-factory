from __future__ import annotations

import asyncio
import hashlib
import importlib
import json
from pathlib import Path
import sys
import wave

import pytest

from audiobook_worker.contracts import (
    EngineCapabilities,
    GenerationResult,
    TtsJob,
    TtsPreset,
    VoiceProfile,
)
from audiobook_worker.runtime_probe import RuntimeProbe
from audiobook_worker.tts_engine import TtsEngine
from audiobook_worker.colab_worker import _default_engine_factory
from audiobook_worker.model_registry import ModelRegistry


def _write_wav(path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as output:
        output.setnchannels(1)
        output.setsampwidth(2)
        output.setframerate(24_000)
        output.writeframes(b"\x00\x00" * 240)


class _FakeLoader:
    def __init__(self) -> None:
        self.calls: list[tuple[str, str]] = []

    def load(self, model_id: str, device: str):
        self.calls.append((model_id, device))
        return object()


class _FakeAdapter:
    def __init__(self) -> None:
        self.calls = []

    def synthesize(self, model, job, prepared, destination):
        self.calls.append((model, job, prepared))
        _write_wav(Path(destination))
        return Path(destination)


def _job(tmp_path: Path, engine_id: str, model_id: str) -> TtsJob:
    reference = tmp_path / "reference.wav"
    reference.write_bytes(b"reference")
    profile = VoiceProfile("voice-1", "统一参考音色", reference, "参考文本。")
    return TtsJob(
        "job-1",
        "book-1",
        "version-1",
        "chapter-1",
        1,
        1,
        "这是一个候选引擎契约测试片段。",
        TtsPreset(
            engine_id,
            model_id,
            "voice-1",
            "自然朗读",
            "zh-CN",
            "wav",
            voice_profile_id="voice-1",
        ),
        profile,
    )


@pytest.mark.parametrize(
    ("module_name", "class_name", "engine_id"),
    [
        ("cosyvoice_engine", "CosyVoice3Engine", "cosyvoice3"),
        ("indextts_engine", "IndexTts25Engine", "indextts-2.5"),
        ("f5_engine", "F5TtsEngine", "f5-tts"),
    ],
)
def test_candidate_engine_implements_shared_contract(
    tmp_path: Path, module_name: str, class_name: str, engine_id: str
):
    module = importlib.import_module(f"audiobook_worker.{module_name}")
    engine_class = getattr(module, class_name)
    loader = _FakeLoader()
    adapter = _FakeAdapter()
    engine = engine_class(
        model_loader=loader,
        model_adapter=adapter,
        probe=RuntimeProbe(True, "Test GPU", 16 * 1024**3, "12.4", "2.7.0", "3.11"),
    )

    assert isinstance(engine, TtsEngine)
    assert engine.engine_id == engine_id
    assert isinstance(engine.capabilities, EngineCapabilities)
    assert engine.model_id
    assert engine.model_version
    assert engine.model_identity == f"{engine.model_id}@{engine.model_version}"

    result = asyncio.run(
        engine.synthesize(
            _job(tmp_path, engine.engine_id, engine.model_id),
            tmp_path / f"{engine_id}.wav",
        )
    )

    assert isinstance(result, GenerationResult)
    assert result.output_path.exists()
    assert len(result.sha256) == 64
    assert loader.calls == [(engine.model_id, "cuda:0")]
    assert len(adapter.calls) == 1
    assert result.sample_rate == 24_000
    assert result.channels == 1
    assert result.duration_seconds == pytest.approx(0.01)


def test_candidate_rejects_invalid_wav_and_mismatched_preset(tmp_path: Path):
    from audiobook_worker.cosyvoice_engine import CosyVoice3Engine

    class BadAdapter(_FakeAdapter):
        def synthesize(self, model, job, prepared, destination):
            Path(destination).write_bytes(b"not wav")
            return Path(destination)

    engine = CosyVoice3Engine(model_loader=_FakeLoader(), model_adapter=BadAdapter())
    with pytest.raises(RuntimeError, match="invalid WAV"):
        asyncio.run(engine.synthesize(_job(tmp_path, engine.engine_id, engine.model_id), tmp_path / "bad.wav"))

    mismatched = _job(tmp_path, engine.engine_id, "wrong/model")
    with pytest.raises(ValueError, match="model"):
        asyncio.run(engine.synthesize(mismatched, tmp_path / "wrong.wav"))


@pytest.mark.parametrize("engine_id", ["cosyvoice3", "indextts-2.5", "f5-tts"])
def test_default_colab_factory_registers_candidate_models(engine_id: str):
    profile = next(p for p in ModelRegistry.default().profiles if p.engine_id == engine_id)
    engine = _default_engine_factory(profile, RuntimeProbe(True, "GPU", 16 * 1024**3, "12", "2", "3"), Path("/tmp/cache"))
    assert engine.engine_id == profile.engine_id
    assert engine.model_id == profile.model_id
    assert engine.model_version == profile.model_version


@pytest.mark.parametrize(
    ("module_name", "class_name", "dependency"),
    [
        ("cosyvoice_engine", "CosyVoice3Engine", "cosyvoice"),
        ("indextts_engine", "IndexTts25Engine", "indextts"),
        ("f5_engine", "F5TtsEngine", "f5_tts"),
    ],
)
def test_candidate_dependency_is_loaded_lazily_and_error_is_clear(
    tmp_path: Path, module_name: str, class_name: str, dependency: str, monkeypatch
):
    module = importlib.import_module(f"audiobook_worker.{module_name}")
    engine = getattr(module, class_name)(
        probe=RuntimeProbe(True, "Test GPU", 16 * 1024**3, "12.4", "2.7.0", "3.11")
    )
    assert dependency not in sys.modules

    profile = VoiceProfile("voice-1", "音色", tmp_path / "reference.wav", "参考文本。")
    (tmp_path / "reference.wav").write_bytes(b"reference")
    prepared = asyncio.run(engine.prepare_voice(profile))
    assert prepared.profile_id == "voice-1"
    assert dependency not in sys.modules

    with pytest.raises(RuntimeError, match=dependency):
        asyncio.run(
            engine.synthesize(
                _job(tmp_path, engine.engine_id, engine.model_id),
                tmp_path / "out.wav",
            )
        )


def test_benchmark_schema_has_four_reproducible_samples():
    samples = json.loads(Path("examples/tts-benchmark.json").read_text(encoding="utf-8"))
    assert 3 <= len(samples) <= 5
    assert all({"id", "text", "language", "category"} <= set(sample) for sample in samples)
    assert len({sample["id"] for sample in samples}) == len(samples)


def test_notebook_builder_check_and_secret_safety():
    notebook = json.loads(
        Path("notebooks/audiobook_factory_colab.ipynb").read_text(encoding="utf-8")
    )
    source = "\n".join("".join(cell["source"]) for cell in notebook["cells"])
    assert len(notebook["cells"]) == 4
    assert source.count("run_forever") == 1
    assert "AUDIOBOOK_CONTROL_URL" in source
    assert "AUDIOBOOK_WORKER_TOKEN" in source
    assert "google.colab.drive" not in source
    assert "print(token" not in source.lower()
    assert "input(" not in source.lower()
    assert "getpass" not in source.lower()


def test_notebook_exposes_explicit_candidate_selection_and_benchmark_schema():
    notebook = json.loads(
        Path("notebooks/audiobook_factory_colab.ipynb").read_text(encoding="utf-8")
    )
    source = "\n".join("".join(cell["source"]) for cell in notebook["cells"])
    assert "SELECTED_MODEL_ID" in source
    assert "BENCHMARK_MODEL_IDS" in source
    assert "select_candidate_profile" in source
    assert "run_candidate_benchmark" in source
    assert "LOCAL_MODEL_ROOT" in source
    assert "requirements-colab.txt" in source
    assert "requirements-cosyvoice.txt" in source
    for field in (
        '"engine"', '"model"', '"version"', '"sample"', '"language"',
        '"category"', '"duration"', '"sampleRate"', '"channels"', '"size"',
        '"elapsed"', '"rtf"', '"gpu"', '"vram"', '"startup"', '"failure"',
    ):
        assert field in source


def test_offline_benchmark_runs_each_candidate_and_emits_metrics(tmp_path: Path):
    from audiobook_worker.benchmark import run_offline_benchmark

    samples = [{"id": "one", "text": "测试。", "language": "zh-CN", "category": "普通叙述"}]
    rows = asyncio.run(run_offline_benchmark(samples, tmp_path / "audio"))
    assert {row["engineId"] for row in rows} == {"cosyvoice3", "indextts-2.5", "f5-tts"}
    assert len(rows) == 3
    assert all(row["durationSeconds"] > 0 for row in rows)
    assert all(row["sampleRate"] == 24_000 and row["channels"] == 1 for row in rows)
    assert all(row["mode"] == "offline-contract" for row in rows)
