from __future__ import annotations

import asyncio
import importlib.util
import json
from pathlib import Path
import sys
import threading
import wave

from audiobook_worker.contracts import TtsJob, TtsPreset, VoiceProfile


def _qwen_engine_types():
    assert importlib.util.find_spec("audiobook_worker.qwen_engine") is not None
    from audiobook_worker.qwen_engine import (
        Qwen3TtsEngine,
        QwenModelAdapter,
        QwenModelLoader,
    )

    return Qwen3TtsEngine, QwenModelAdapter, QwenModelLoader


class _FakeModel:
    def __init__(self, owner, model_id):
        self.owner = owner
        self.model_id = model_id

    def create_voice_clone_prompt(
        self, *, ref_audio, ref_text, x_vector_only_mode=False
    ):
        self.owner.clone_prompt_calls += 1
        self.owner.last_clone_prompt_args = (
            ref_audio,
            ref_text,
            x_vector_only_mode,
        )
        return {"ref_audio": ref_audio, "ref_text": ref_text}

    def generate_voice_clone(
        self, *, text, language, voice_clone_prompt, **parameters
    ):
        self.owner.generate_calls += 1
        self.owner.last_generation = {
            "text": text,
            "language": language,
            "voice_clone_prompt": voice_clone_prompt,
            "parameters": parameters,
        }
        return [[0.0] * 240], 24_000

    def generate_voice_design(self, *, text, language, instruct, **parameters):
        self.owner.design_calls += 1
        self.owner.last_design = {
            "text": text,
            "language": language,
            "instruct": instruct,
            "parameters": parameters,
        }
        return [[0.0] * 120], 24_000


class _FakeQwen:
    def __init__(self):
        self.clone_prompt_calls = 0
        self.generate_calls = 0
        self.last_clone_prompt_args = None
        self.last_generation = None
        self.design_calls = 0
        self.last_design = None
        self.load_calls = []
        self.models = {}
        self.loader = self.load

    def load(self, model_id, device):
        self.load_calls.append((model_id, device))
        return self.models.setdefault(model_id, _FakeModel(self, model_id))


class _RecordingAdapter:
    def __init__(self):
        self.clone_prompt_calls = 0
        self.generation = None

    def create_voice_clone_prompt(self, reference_audio, reference_text):
        self.clone_prompt_calls += 1
        return "recorded-prompt"

    def generate_voice_clone(
        self, model, prompt, text, language, output_path, parameters
    ):
        self.generation = {
            "model": model,
            "prompt": prompt,
            "text": text,
            "language": language,
            "parameters": parameters,
        }
        _write_test_wav(output_path)


def _write_test_wav(path, *, frames=240, sample_rate=24_000):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(b"\x00\x00" * frames)


def make_tts_job(*, text, voice_profile):
    preset = TtsPreset(
        "qwen3-tts",
        "Qwen/Qwen3-TTS-12Hz-1.7B-Base",
        voice_profile.profile_id,
        "自然朗读。",
        "zh-CN",
        "wav",
        voice_profile_id=voice_profile.profile_id,
    )
    return TtsJob(
        "job-1",
        "book-1",
        "book-version-1",
        "chapter-1",
        1,
        1,
        text,
        preset,
        voice_profile,
    )


def run_async(awaitable):
    return asyncio.run(awaitable)


def test_qwen_engine_reuses_voice_prompt_and_writes_wav(tmp_path):
    Qwen3TtsEngine, _, _ = _qwen_engine_types()
    fake_qwen = _FakeQwen()
    engine = Qwen3TtsEngine(model_loader=fake_qwen.loader, cache_dir=tmp_path)
    reference_audio = tmp_path / "reference.wav"
    reference_audio.write_bytes(b"reference audio")
    profile = VoiceProfile(
        "voice-1",
        "旁白",
        reference_audio,
        "测试参考文本",
        None,
    )
    job = make_tts_job(text="这是一个测试片段。", voice_profile=profile)

    result = run_async(engine.synthesize(job, tmp_path / "out.wav"))

    assert result.output_path.exists()
    assert fake_qwen.clone_prompt_calls == 1
    assert fake_qwen.generate_calls == 1
    assert fake_qwen.last_generation["language"] == "Chinese"
    assert result.sample_rate == 24_000
    assert result.channels == 1
    assert result.duration_seconds == 0.01
    assert len(result.sha256) == 64


def test_qwen_engine_reuses_cached_prompt_for_same_reference(tmp_path):
    Qwen3TtsEngine, _, _ = _qwen_engine_types()
    fake_qwen = _FakeQwen()
    engine = Qwen3TtsEngine(model_loader=fake_qwen.loader, cache_dir=tmp_path)
    reference_audio = tmp_path / "reference.wav"
    reference_audio.write_bytes(b"reference audio")
    profile = VoiceProfile("voice-1", "旁白", reference_audio, "参考文本")

    run_async(engine.synthesize(make_tts_job(text="第一段。", voice_profile=profile), tmp_path / "one.wav"))
    run_async(engine.synthesize(make_tts_job(text="第二段。", voice_profile=profile), tmp_path / "two.wav"))

    assert fake_qwen.clone_prompt_calls == 1
    assert fake_qwen.generate_calls == 2
    assert (tmp_path / "one.wav").exists()
    assert (tmp_path / "two.wav").exists()


def test_qwen_engine_forwards_parameters_and_normalizes_text(tmp_path):
    Qwen3TtsEngine, _, _ = _qwen_engine_types()
    fake_qwen = _FakeQwen()
    adapter = _RecordingAdapter()
    engine = Qwen3TtsEngine(
        model_loader=fake_qwen.loader,
        model_adapter=adapter,
        cache_dir=tmp_path,
    )
    reference_audio = tmp_path / "reference.wav"
    reference_audio.write_bytes(b"reference audio")
    profile = VoiceProfile("voice-1", "旁白", reference_audio, "参考文本")
    job = make_tts_job(
        text="  这是\n 一段。 ",
        voice_profile=profile,
    )
    job = TtsJob(
        job.job_id,
        job.book_id,
        job.book_version_id,
        job.chapter_id,
        job.chapter_index,
        job.segment_index,
        job.text,
        TtsPreset(
            job.preset.provider,
            job.preset.model,
            job.preset.voice,
            job.preset.style_prompt,
            job.preset.language,
            job.preset.output_format,
            voice_profile_id=profile.profile_id,
            parameters_json=json.dumps(
                {
                    "temperature": 0.4,
                    "top_k": 7,
                    "top_p": 0.85,
                    "speed": 1.2,
                }
            ),
        ),
        profile,
    )

    run_async(engine.synthesize(job, tmp_path / "parameters.wav"))

    assert adapter.generation["text"] == "这是 一段。"
    assert adapter.generation["language"] == "Chinese"
    assert adapter.generation["parameters"] == {
        "temperature": 0.4,
        "top_k": 7,
        "top_p": 0.85,
        "speed": 1.2,
    }


def test_qwen_engine_runs_blocking_loader_and_generation_off_event_loop_thread(
    tmp_path,
):
    Qwen3TtsEngine, _, _ = _qwen_engine_types()
    main_thread = threading.get_ident()
    observed_threads = []

    class ThreadRecordingQwen(_FakeQwen):
        def load(self, model_id, device):
            observed_threads.append(threading.get_ident())
            return super().load(model_id, device)

    fake_qwen = ThreadRecordingQwen()
    engine = Qwen3TtsEngine(model_loader=fake_qwen.loader, cache_dir=tmp_path)
    reference_audio = tmp_path / "reference.wav"
    reference_audio.write_bytes(b"reference audio")
    profile = VoiceProfile("voice-1", "旁白", reference_audio, "参考文本")

    run_async(engine.synthesize(make_tts_job(text="片段。", voice_profile=profile), tmp_path / "thread.wav"))

    assert observed_threads
    assert all(thread_id != main_thread for thread_id in observed_threads)


def test_qwen_engine_materializes_voice_design_before_clone(tmp_path):
    Qwen3TtsEngine, _, _ = _qwen_engine_types()
    fake_qwen = _FakeQwen()
    engine = Qwen3TtsEngine(model_loader=fake_qwen.loader, cache_dir=tmp_path)
    profile = VoiceProfile(
        "voice-1",
        "旁白",
        None,
        "夜已经很深了。",
        "成熟、冷静、克制的中国男性声音。",
    )

    result = run_async(
        engine.synthesize(
            make_tts_job(text="这是设计音色。", voice_profile=profile),
            tmp_path / "design.wav",
        )
    )

    assert result.output_path.exists()
    assert fake_qwen.design_calls == 1
    assert fake_qwen.clone_prompt_calls == 1
    assert [model_id for model_id, _ in fake_qwen.load_calls] == [
        "Qwen/Qwen3-TTS-12Hz-1.7B-VoiceDesign",
        "Qwen/Qwen3-TTS-12Hz-1.7B-Base",
    ]
    assert fake_qwen.last_design["language"] == "Chinese"
    assert fake_qwen.last_design["instruct"] == profile.design_prompt


def test_qwen_engine_loads_the_model_selected_by_the_job_preset(tmp_path):
    Qwen3TtsEngine, _, _ = _qwen_engine_types()
    fake_qwen = _FakeQwen()
    engine = Qwen3TtsEngine(model_loader=fake_qwen.loader, cache_dir=tmp_path)
    reference_audio = tmp_path / "reference.wav"
    reference_audio.write_bytes(b"reference audio")
    profile = VoiceProfile("voice-1", "旁白", reference_audio, "参考文本")
    base_job = make_tts_job(text="片段。", voice_profile=profile)
    selected_model = "Qwen/Qwen3-TTS-12Hz-0.6B-Base"
    job = TtsJob(
        base_job.job_id,
        base_job.book_id,
        base_job.book_version_id,
        base_job.chapter_id,
        base_job.chapter_index,
        base_job.segment_index,
        base_job.text,
        TtsPreset(
            base_job.preset.provider,
            selected_model,
            base_job.preset.voice,
            base_job.preset.style_prompt,
            base_job.preset.language,
            base_job.preset.output_format,
            voice_profile_id=profile.profile_id,
        ),
        profile,
    )

    run_async(engine.synthesize(job, tmp_path / "selected-model.wav"))

    assert [model_id for model_id, _ in fake_qwen.load_calls] == [selected_model]


def test_qwen_engine_module_does_not_import_qwen_at_module_import_time():
    module = importlib.util.find_spec("audiobook_worker.qwen_engine")
    assert module is not None
    assert "qwen_tts" not in sys.modules


def test_colab_requirements_pin_qwen_and_audio_dependencies():
    requirements = Path(__file__).parents[1].joinpath("requirements-colab.txt")
    assert requirements.exists()
    content = requirements.read_text(encoding="utf-8")
    assert "qwen-tts==0.1.1" in content
    assert "soundfile" in content
    assert "numpy" in content
    assert "httpx" in content
    assert "torch" in content
