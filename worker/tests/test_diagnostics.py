import asyncio
import json
from types import SimpleNamespace
from unittest.mock import Mock

import pytest

from audiobook_worker.browser_session import BrowserSession
from audiobook_worker.diagnostics import Diagnostics


class DiagnosticPage:
    def __init__(self, *, html="<html>safe</html>", content_error=None):
        self.url = (
            "https://aistudio.google.com/generate-speech?"
            "token=url-secret&cookie=session-secret"
        )
        self.html = html
        self.content_error = content_error
        self.listeners = {}

    def on(self, event, callback):
        self.listeners[event] = callback

    async def screenshot(self, *, path, **kwargs):
        path.write_bytes(b"png")

    async def content(self):
        if self.content_error:
            raise self.content_error
        return self.html


class DiagnosticContext:
    def __init__(self, page):
        self.pages = [page]


class HangingContentPage(DiagnosticPage):
    async def content(self):
        await asyncio.Event().wait()


class BundleSwapPage(DiagnosticPage):
    def __init__(self, bundle_path, outside_path):
        super().__init__()
        self.bundle_path = bundle_path
        self.outside_path = outside_path

    async def screenshot(self, *, path, **kwargs):
        self.bundle_path.unlink()
        self.bundle_path.symlink_to(self.outside_path)
        path.write_bytes(b"png")


def test_capture_writes_redacted_diagnostic_bundle(tmp_path):
    async def exercise():
        page = DiagnosticPage(
            html='<html data-password="html-secret">safe</html>',
        )
        browser = SimpleNamespace(contexts=[DiagnosticContext(page)])
        session = BrowserSession(Mock(), browser)
        await session.page_for("https://aistudio.google.com/generate-speech")
        page.listeners["console"](
            SimpleNamespace(
                type="warning",
                text="Authorization: Bearer log-secret token=abc",
            )
        )
        assert "log-secret" not in repr(vars(page))

        result = await Diagnostics(tmp_path).capture(page, "p0-001", "page-not-ready")

        assert result.screenshot.read_bytes() == b"png"
        assert result.html.read_text() == '<html data-password="[REDACTED]">safe</html>'
        assert result.console.name == "console.jsonl"
        console_entry = json.loads(result.console.read_text())
        assert console_entry["type"] == "warning"
        assert console_entry["text"] == "Authorization: [REDACTED] token=[REDACTED]"
        assert set(console_entry) == {"type", "text", "timestamp"}
        metadata = json.loads(result.metadata.read_text())
        assert metadata == {
            "reason": "page-not-ready",
            "url": (
                "https://aistudio.google.com/generate-speech?"
                "token=[REDACTED]&cookie=[REDACTED]"
            ),
            "capture_errors": [],
        }

    asyncio.run(exercise())


def test_capture_failure_is_recorded_without_raising_or_persisting_secret(tmp_path):
    async def exercise():
        page = DiagnosticPage(content_error=RuntimeError("password=content-secret"))

        result = await Diagnostics(tmp_path).capture(page, "p0-002", "generation-timeout")

        assert result.screenshot.exists()
        assert result.console.read_text() == ""
        metadata_text = result.metadata.read_text()
        assert "content-secret" not in metadata_text
        metadata = json.loads(metadata_text)
        assert metadata["reason"] == "generation-timeout"
        assert metadata["capture_errors"] == ["html: password=[REDACTED]"]

    asyncio.run(exercise())


def test_capture_bounds_hanging_page_content_and_records_timeout(tmp_path):
    async def exercise():
        diagnostics = Diagnostics(tmp_path, operation_timeout_seconds=0.01)

        result = await asyncio.wait_for(
            diagnostics.capture(HangingContentPage(), "p0-timeout", "token=reason"),
            timeout=0.2,
        )

        metadata = json.loads(result.metadata.read_text())
        assert metadata["reason"] == "token=[REDACTED]"
        assert metadata["capture_errors"] == [
            "html: timed out after 0.01 seconds"
        ]

    asyncio.run(exercise())


@pytest.mark.parametrize(
    "timeout",
    [0.0, -1.0, float("inf"), float("-inf"), float("nan")],
    ids=["zero", "negative", "infinity", "negative-infinity", "nan"],
)
def test_diagnostics_rejects_non_finite_or_non_positive_timeout(tmp_path, timeout):
    with pytest.raises(ValueError, match="finite and strictly positive"):
        Diagnostics(tmp_path, operation_timeout_seconds=timeout)


def test_capture_redacts_complete_cookie_and_authorization_header_values(tmp_path):
    async def exercise():
        page = DiagnosticPage()
        browser = SimpleNamespace(contexts=[DiagnosticContext(page)])
        session = BrowserSession(Mock(), browser)
        await session.page_for("https://aistudio.google.com/generate-speech")
        page.listeners["console"](
            SimpleNamespace(type="log", text="Cookie: session=abc; csrf=def")
        )
        page.listeners["console"](
            SimpleNamespace(type="log", text="Authorization: Basic dXNlcjpwYXNz")
        )

        result = await Diagnostics(tmp_path).capture(page, "p0-003", "page-not-ready")

        entries = [json.loads(line) for line in result.console.read_text().splitlines()]
        assert [entry["text"] for entry in entries] == [
            "Cookie: [REDACTED]",
            "Authorization: [REDACTED]",
        ]

    asyncio.run(exercise())


@pytest.mark.parametrize("key", ["cookie", "token", "password", "authorization"])
def test_capture_redacts_json_value_with_escaped_quote(tmp_path, key):
    async def exercise():
        page = DiagnosticPage()
        browser = SimpleNamespace(contexts=[DiagnosticContext(page)])
        session = BrowserSession(Mock(), browser)
        await session.page_for("https://aistudio.google.com/generate-speech")
        page.listeners["console"](
            SimpleNamespace(
                type="log",
                text=f'{{"{key}": "prefix\\\"tail-secret"}}',
            )
        )

        result = await Diagnostics(tmp_path).capture(page, f"p0-{key}", "failure")

        entry = json.loads(result.console.read_text())
        assert entry["text"] == f'{{"{key}": "[REDACTED]"}}'
        assert "tail-secret" not in result.console.read_text()

    asyncio.run(exercise())


def test_capture_keeps_untrusted_request_id_inside_diagnostics_root(tmp_path):
    async def exercise():
        result = await Diagnostics(tmp_path).capture(
            DiagnosticPage(), "../outside", "page-not-ready"
        )

        assert result.metadata.parent.parent == tmp_path
        assert result.metadata.exists()
        assert not (tmp_path.parent / "outside" / "metadata.json").exists()

    asyncio.run(exercise())


def test_capture_rejects_symlinked_request_directory(tmp_path):
    async def exercise():
        root = tmp_path / "diagnostics"
        outside = tmp_path / "outside"
        root.mkdir()
        outside.mkdir()
        (root / "p0-004").symlink_to(outside, target_is_directory=True)

        await Diagnostics(root).capture(DiagnosticPage(), "p0-004", "page-not-ready")

        assert list(outside.iterdir()) == []

    asyncio.run(exercise())


@pytest.mark.parametrize(
    "filename",
    ["screenshot.png", "page.html", "console.jsonl", "metadata.json"],
)
def test_capture_rejects_symlinked_bundle_file(tmp_path, filename):
    async def exercise():
        root = tmp_path / "diagnostics"
        request_dir = root / "p0-005"
        request_dir.mkdir(parents=True)
        outside = tmp_path / "outside"
        outside.write_text("unchanged")
        (request_dir / filename).symlink_to(outside)

        await Diagnostics(root).capture(DiagnosticPage(), "p0-005", "page-not-ready")

        assert outside.read_text() == "unchanged"

    asyncio.run(exercise())


def test_capture_write_rejects_bundle_symlink_swapped_after_preflight(tmp_path):
    async def exercise():
        root = tmp_path / "diagnostics"
        request_dir = root / "p0-swap"
        outside = tmp_path / "outside"
        outside.write_text("unchanged")
        page = BundleSwapPage(request_dir / "page.html", outside)

        await Diagnostics(root).capture(page, "p0-swap", "page-not-ready")

        assert outside.read_text() == "unchanged"

    asyncio.run(exercise())
