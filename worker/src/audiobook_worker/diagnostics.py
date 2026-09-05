import asyncio
from dataclasses import dataclass
from datetime import datetime, timezone
import json
import math
import os
from pathlib import Path
import re
import secrets
import stat
from typing import Any


_CONSOLE_MESSAGES_ATTRIBUTE = "_audiobook_console_messages"
_DEFAULT_OPERATION_TIMEOUT_SECONDS = 5.0
_SENSITIVE_KEYS = r"(?:cookie|token|password|authorization)"
_QUOTED_VALUE = re.compile(
    rf"(?i)(\b{_SENSITIVE_KEYS}\b[\"']?\s*[:=]\s*)([\"'])"
    r"(?:\\.|(?!\2)[\s\S])*\2"
)
_COOKIE_VALUE = re.compile(
    r"(?i)(\bcookie\b[\"']?\s*[:=]\s*)"
    r"[^\s\r\n&,<>\"'][^\r\n&,<>\"']*"
)
_AUTHORIZATION_VALUE = re.compile(
    r"(?i)(\bauthorization\b[\"']?\s*[:=]\s*)(?:(?:bearer|basic)\s+)?[^\s&;,<>\"']+"
)
_UNQUOTED_VALUE = re.compile(
    rf"(?i)(\b{_SENSITIVE_KEYS}\b[\"']?\s*[:=]\s*)[^\s&;,<>\"']+"
)


def _redact(text: str) -> str:
    text = _QUOTED_VALUE.sub(
        lambda match: (
            f"{match.group(1)}{match.group(2)}[REDACTED]{match.group(2)}"
        ),
        text,
    )
    text = _COOKIE_VALUE.sub(r"\1[REDACTED]", text)
    text = _AUTHORIZATION_VALUE.sub(r"\1[REDACTED]", text)
    return _UNQUOTED_VALUE.sub(r"\1[REDACTED]", text)


def register_console_listener(page: Any) -> None:
    if hasattr(page, _CONSOLE_MESSAGES_ATTRIBUTE):
        return

    messages: list[dict[str, str]] = []
    setattr(page, _CONSOLE_MESSAGES_ATTRIBUTE, messages)

    def record(message: Any) -> None:
        messages.append(
            {
                "type": str(message.type),
                "text": _redact(str(message.text)),
                "timestamp": datetime.now(timezone.utc).isoformat(),
            }
        )

    page.on("console", record)


@dataclass(frozen=True)
class DiagnosticBundle:
    screenshot: Path
    html: Path
    console: Path
    metadata: Path


class Diagnostics:
    def __init__(
        self,
        root: Path,
        *,
        operation_timeout_seconds: float = _DEFAULT_OPERATION_TIMEOUT_SECONDS,
    ):
        if (
            not math.isfinite(operation_timeout_seconds)
            or operation_timeout_seconds <= 0
        ):
            raise ValueError(
                "operation_timeout_seconds must be finite and strictly positive"
            )
        self.root = Path(root)
        self.operation_timeout_seconds = operation_timeout_seconds

    async def capture(self, page: Any, request_id: str, reason: str) -> DiagnosticBundle:
        request_dir = self.root / _safe_directory_name(request_id)
        screenshot = request_dir / "screenshot.png"
        html = request_dir / "page.html"
        console = request_dir / "console.jsonl"
        metadata = request_dir / "metadata.json"
        bundle = DiagnosticBundle(screenshot, html, console, metadata)
        errors: list[str] = []

        try:
            request_fd = _open_request_directory(self.root, request_dir.name)
            for filename in ("screenshot.png", "page.html", "console.jsonl"):
                _write_bytes(request_fd, filename, b"")
        except Exception:
            return bundle

        try:
            try:
                staging_name = f".screenshot-{secrets.token_hex(16)}.tmp"
                staging_fd = _open_staging_file(request_fd, staging_name)
                try:
                    staging_path = Path(f"/proc/self/fd/{staging_fd}")
                    await asyncio.wait_for(
                        page.screenshot(path=staging_path, type="png"),
                        timeout=self.operation_timeout_seconds,
                    )
                    os.lseek(staging_fd, 0, os.SEEK_SET)
                    _write_bytes(
                        request_fd,
                        "screenshot.png",
                        _read_bytes(staging_fd),
                    )
                finally:
                    os.close(staging_fd)
                    os.unlink(staging_name, dir_fd=request_fd)
            except TimeoutError:
                errors.append(
                    "screenshot: timed out after "
                    f"{self.operation_timeout_seconds:g} seconds"
                )
            except Exception as exc:
                errors.append(f"screenshot: {_redact(str(exc))}")

            try:
                content = await asyncio.wait_for(
                    page.content(),
                    timeout=self.operation_timeout_seconds,
                )
                _write_text(request_fd, "page.html", _redact(content))
            except TimeoutError:
                errors.append(
                    f"html: timed out after {self.operation_timeout_seconds:g} seconds"
                )
            except Exception as exc:
                errors.append(f"html: {_redact(str(exc))}")

            try:
                entries = getattr(page, _CONSOLE_MESSAGES_ATTRIBUTE, ())
                console_text = "".join(
                    json.dumps(
                        {
                            "type": entry["type"],
                            "text": _redact(entry["text"]),
                            "timestamp": entry["timestamp"],
                        },
                        ensure_ascii=False,
                        sort_keys=True,
                    )
                    + "\n"
                    for entry in entries
                )
                _write_text(request_fd, "console.jsonl", console_text)
            except Exception as exc:
                errors.append(f"console: {_redact(str(exc))}")

            payload = {
                "reason": _redact(reason),
                "url": _redact(str(getattr(page, "url", ""))),
                "capture_errors": errors,
            }
            try:
                _write_text(
                    request_fd,
                    "metadata.json",
                    json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
                )
            except Exception:
                pass
        finally:
            os.close(request_fd)

        return bundle


def _safe_directory_name(request_id: str) -> str:
    name = re.sub(r"[^A-Za-z0-9._-]", "_", request_id)
    if name in {"", ".", ".."}:
        return "request"
    return name


def _open_request_directory(root: Path, request_name: str) -> int:
    root.mkdir(parents=True, exist_ok=True)
    resolved_root = root.resolve(strict=True)
    directory_flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    root_fd = os.open(resolved_root, directory_flags)
    try:
        try:
            os.mkdir(request_name, mode=0o700, dir_fd=root_fd)
        except FileExistsError:
            pass
        request_fd = os.open(request_name, directory_flags, dir_fd=root_fd)
    finally:
        os.close(root_fd)

    try:
        opened_path = Path(f"/proc/self/fd/{request_fd}").resolve(strict=True)
        if opened_path.parent != resolved_root:
            raise ValueError("diagnostic request directory escaped configured root")
    except Exception:
        os.close(request_fd)
        raise
    return request_fd


def _write_bytes(directory_fd: int, filename: str, content: bytes) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW
    file_fd = os.open(filename, flags, mode=0o600, dir_fd=directory_fd)
    try:
        if not stat.S_ISREG(os.fstat(file_fd).st_mode):
            raise ValueError("diagnostic output must be a regular file")
        with os.fdopen(file_fd, "wb", closefd=False) as output:
            output.write(content)
    finally:
        os.close(file_fd)


def _write_text(directory_fd: int, filename: str, content: str) -> None:
    _write_bytes(directory_fd, filename, content.encode("utf-8"))


def _open_staging_file(directory_fd: int, filename: str) -> int:
    flags = os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW
    return os.open(filename, flags, mode=0o600, dir_fd=directory_fd)


def _read_bytes(file_fd: int) -> bytes:
    chunks = []
    while chunk := os.read(file_fd, 1024 * 1024):
        chunks.append(chunk)
    return b"".join(chunks)
