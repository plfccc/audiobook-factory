import json
import os
import stat
from pathlib import Path

from audiobook_worker.cli import main
from audiobook_worker.contracts import GenerationResult
from audiobook_worker.errors import ErrorCode, WorkerError


class FakeSession:
    def __init__(self):
        self.closed = False

    async def close(self):
        self.closed = True


def write_inputs(tmp_path: Path) -> tuple[Path, Path]:
    text_file = tmp_path / "sample.txt"
    text_file.write_text("这是 UTF-8 测试文本。\n", encoding="utf-8")
    preset_file = tmp_path / "preset.json"
    preset_file.write_text(
        json.dumps(
            {
                "provider": "google-ai-studio-browser",
                "model": "gemini-tts",
                "voice": "Kore",
                "style_prompt": "自然朗读。",
                "language": "zh-CN",
                "output_format": "wav",
            },
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    return text_file, preset_file


def test_cli_loads_utf8_input_builds_request_and_creates_private_dirs(tmp_path, monkeypatch):
    import audiobook_worker.cli as cli

    text_file, preset_file = write_inputs(tmp_path)
    session = FakeSession()
    captured = {}

    async def connect(cdp_url):
        captured["cdp_url"] = cdp_url
        return session

    class FakePipeline:
        def __init__(self, provider, validator, artifacts, diagnostics, settings):
            captured["pipeline"] = (provider, validator, artifacts, diagnostics, settings)

        async def run(self, request):
            captured["request"] = request
            return GenerationResult(
                request.request_id,
                request.output_dir / "p0-cli-001.wav",
                "b" * 64,
                2_048,
                1.0,
                24_000,
                1,
            )

    monkeypatch.setattr(cli.BrowserSession, "connect", connect)
    monkeypatch.setattr(cli, "P0Pipeline", FakePipeline)

    output_dir = tmp_path / "books"
    diagnostics_dir = tmp_path / "diagnostics"
    exit_code = main(
        [
            "--text-file",
            str(text_file),
            "--preset",
            str(preset_file),
            "--request-id",
            "p0-cli-001",
            "--output-dir",
            str(output_dir),
            "--diagnostics-dir",
            str(diagnostics_dir),
        ]
    )

    assert exit_code == 0
    assert session.closed is True
    assert captured["request"].text == "这是 UTF-8 测试文本。\n"
    assert captured["request"].preset.voice == "Kore"
    if os.name == "posix":
        assert stat.S_IMODE(os.stat(output_dir).st_mode) == 0o700
        assert stat.S_IMODE(os.stat(diagnostics_dir).st_mode) == 0o700


def test_cli_maps_manual_authentication_failure_to_exit_code_two(tmp_path, monkeypatch):
    import audiobook_worker.cli as cli

    text_file, preset_file = write_inputs(tmp_path)
    session = FakeSession()

    async def connect(cdp_url):
        return session

    class FailingPipeline:
        def __init__(self, *args, **kwargs):
            pass

        async def run(self, request):
            raise WorkerError(
                ErrorCode.AUTH_REQUIRED,
                "manual sign-in required",
                retryable=False,
            )

    monkeypatch.setattr(cli.BrowserSession, "connect", connect)
    monkeypatch.setattr(cli, "P0Pipeline", FailingPipeline)

    assert main(
        [
            "--text-file",
            str(text_file),
            "--preset",
            str(preset_file),
            "--request-id",
            "p0-cli-002",
            "--output-dir",
            str(tmp_path / "books"),
        ]
    ) == 2
    assert session.closed is True
