import asyncio
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock, patch

import pytest

from audiobook_worker.browser_session import BrowserSession
from audiobook_worker.errors import ErrorCode, WorkerError


def test_connect_uses_cdp_and_close_only_stops_playwright_transport():
    async def exercise():
        playwright = SimpleNamespace(
            chromium=SimpleNamespace(
                connect_over_cdp=AsyncMock(return_value="browser"),
                launch=AsyncMock(),
            ),
            stop=AsyncMock(),
        )
        with patch("audiobook_worker.browser_session.async_playwright") as factory:
            factory.return_value.start = AsyncMock(return_value=playwright)
            session = await BrowserSession.connect("http://browser:9222")
            await session.close()

        playwright.chromium.connect_over_cdp.assert_awaited_once_with(
            "http://browser:9222"
        )
        playwright.chromium.launch.assert_not_awaited()
        playwright.stop.assert_awaited_once_with()
        assert session.browser == "browser"

    asyncio.run(exercise())


def test_connect_failure_stops_transport_and_uses_worker_error_contract():
    async def exercise():
        playwright = SimpleNamespace(
            chromium=SimpleNamespace(
                connect_over_cdp=AsyncMock(side_effect=OSError("CDP unavailable")),
                launch=AsyncMock(),
            ),
            stop=AsyncMock(),
        )
        with patch("audiobook_worker.browser_session.async_playwright") as factory:
            factory.return_value.start = AsyncMock(return_value=playwright)
            with pytest.raises(WorkerError) as caught:
                await BrowserSession.connect("http://browser:9222")

        assert caught.value.code is ErrorCode.PAGE_NOT_READY
        assert caught.value.retryable is True
        playwright.stop.assert_awaited_once_with()

    asyncio.run(exercise())


def test_connect_failure_preserves_worker_error_when_cleanup_also_fails():
    async def exercise():
        connect_error = OSError("CDP unavailable")
        playwright = SimpleNamespace(
            chromium=SimpleNamespace(
                connect_over_cdp=AsyncMock(side_effect=connect_error),
                launch=AsyncMock(),
            ),
            stop=AsyncMock(side_effect=RuntimeError("cleanup failed")),
        )
        with patch("audiobook_worker.browser_session.async_playwright") as factory:
            factory.return_value.start = AsyncMock(return_value=playwright)
            with pytest.raises(WorkerError) as caught:
                await BrowserSession.connect("http://browser:9222")

        assert caught.value.code is ErrorCode.PAGE_NOT_READY
        assert caught.value.retryable is True
        assert caught.value.__cause__ is connect_error
        playwright.stop.assert_awaited_once_with()

    asyncio.run(exercise())


class FakePage:
    def __init__(self, url: str):
        self.url = url
        self.listeners = {}

    def on(self, event, callback):
        self.listeners[event] = callback


class FakeContext:
    def __init__(self, pages, new_page):
        self.pages = pages
        self._new_page = new_page
        self.new_page = AsyncMock(side_effect=self._create_page)

    async def _create_page(self):
        self.pages.append(self._new_page)
        return self._new_page


def test_page_for_reuses_same_hostname_and_does_not_create_a_context():
    async def exercise():
        matching = FakePage("https://aistudio.google.com/old")
        context = FakeContext(
            [FakePage("https://example.com"), matching], FakePage("about:blank")
        )
        browser = SimpleNamespace(contexts=[context], new_context=AsyncMock())
        session = BrowserSession(Mock(), browser)

        result = await session.page_for("https://aistudio.google.com/generate-speech")

        assert result is matching
        context.new_page.assert_not_awaited()
        browser.new_context.assert_not_awaited()
        assert "console" in matching.listeners

    asyncio.run(exercise())


def test_page_for_creates_one_page_in_existing_context():
    async def exercise():
        created = FakePage("about:blank")
        context = FakeContext([FakePage("https://example.com")], created)
        browser = SimpleNamespace(contexts=[context], new_context=AsyncMock())
        session = BrowserSession(Mock(), browser)

        result = await session.page_for("https://aistudio.google.com/generate-speech")

        assert result is created
        context.new_page.assert_awaited_once_with()
        browser.new_context.assert_not_awaited()
        assert "console" in created.listeners

    asyncio.run(exercise())


def test_page_for_fails_when_cdp_browser_has_no_existing_context():
    async def exercise():
        browser = SimpleNamespace(contexts=[], new_context=AsyncMock())
        session = BrowserSession(Mock(), browser)

        with pytest.raises(WorkerError) as caught:
            await session.page_for("https://aistudio.google.com/generate-speech")

        assert caught.value.code is ErrorCode.PAGE_NOT_READY
        assert caught.value.retryable is True

    asyncio.run(exercise())


def test_page_for_rejects_target_without_hostname():
    async def exercise():
        blank = FakePage("about:blank")
        context = FakeContext([blank], FakePage("about:blank"))
        session = BrowserSession(Mock(), SimpleNamespace(contexts=[context]))

        with pytest.raises(WorkerError) as caught:
            await session.page_for("about:blank")

        assert caught.value.code is ErrorCode.PAGE_NOT_READY
        assert caught.value.retryable is False
        assert "console" not in blank.listeners
        context.new_page.assert_not_awaited()

    asyncio.run(exercise())
