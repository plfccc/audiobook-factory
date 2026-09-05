import argparse
import asyncio
import json
import os
from pathlib import Path
import sys

from .ai_studio_provider import GoogleAiStudioBrowserProvider
from .artifacts import ArtifactStore
from .audio import AudioValidator
from .browser_session import BrowserSession
from .config import WorkerSettings
from .contracts import GenerationRequest, GenerationResult, TtsPreset
from .diagnostics import Diagnostics
from .errors import ErrorCode, WorkerError
from .logging import emit_event
from .pipeline import P0Pipeline


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Run one P0 audiobook generation")
    parser.add_argument("--text-file", required=True, type=Path)
    parser.add_argument("--preset", required=True, type=Path)
    parser.add_argument("--request-id", required=True)
    parser.add_argument("--output-dir", required=True, type=Path)
    parser.add_argument("--diagnostics-dir", type=Path)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        result = asyncio.run(_run(args))
    except WorkerError as error:
        emit_event(
            "failed",
            request_id=args.request_id,
            phase="cli",
            error_code=error.code,
        )
        print(f"{error.code.value}: {error}", file=sys.stderr)
        return _exit_code(error.code)
    except (OSError, UnicodeError, TypeError, ValueError, json.JSONDecodeError) as error:
        print(f"input or runtime error: {error}", file=sys.stderr)
        return 3

    print(
        json.dumps(
            {
                "status": "SUCCESS",
                "request_id": result.request_id,
                "output_path": str(result.output_path),
                "sha256": result.sha256,
                "size_bytes": result.size_bytes,
                "duration_seconds": result.duration_seconds,
                "sample_rate": result.sample_rate,
                "channels": result.channels,
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0


async def _run(args: argparse.Namespace) -> GenerationResult:
    text = args.text_file.read_text(encoding="utf-8")
    preset = _load_preset(args.preset)
    base_settings = WorkerSettings()
    diagnostics_dir = args.diagnostics_dir or base_settings.diagnostics_dir
    _ensure_private_directory(args.output_dir)
    _ensure_private_directory(diagnostics_dir)
    settings = WorkerSettings(
        OUTPUT_DIR=args.output_dir,
        DIAGNOSTICS_DIR=diagnostics_dir,
    )
    request = GenerationRequest(
        request_id=args.request_id,
        text=text,
        preset=preset,
        output_dir=args.output_dir,
    )

    session = await BrowserSession.connect(settings.cdp_url)
    try:
        diagnostics = Diagnostics(settings.diagnostics_dir)
        provider = GoogleAiStudioBrowserProvider(session, settings, diagnostics)
        pipeline = P0Pipeline(
            provider,
            AudioValidator(),
            ArtifactStore(),
            diagnostics,
            settings,
        )
        return await pipeline.run(request)
    finally:
        await session.close()


def _load_preset(path: Path) -> TtsPreset:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, dict):
        raise ValueError("preset JSON must contain an object")
    return TtsPreset(**payload)


def _ensure_private_directory(path: Path) -> None:
    path = Path(path)
    path.mkdir(parents=True, exist_ok=True)
    os.chmod(path, 0o700)


def _exit_code(code: ErrorCode) -> int:
    if code in {
        ErrorCode.AUTH_REQUIRED,
        ErrorCode.QUOTA_PAUSED,
        ErrorCode.HUMAN_REQUIRED,
    }:
        return 2
    if code in {ErrorCode.AUDIO_INVALID, ErrorCode.PERMANENT_FAILED}:
        return 3
    return 4


if __name__ == "__main__":
    raise SystemExit(main())
