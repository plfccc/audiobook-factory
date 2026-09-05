from typing import TYPE_CHECKING
from urllib.parse import urlsplit

from .diagnostics import register_console_listener
from .errors import ErrorCode, WorkerError

if TYPE_CHECKING:
    from playwright.async_api import Browser, Page, Playwright


def async_playwright():
    from playwright.async_api import async_playwright as playwright_factory

    return playwright_factory()


class BrowserSession:
    def __init__(self, playwright: "Playwright", browser: "Browser"):
        self._playwright = playwright
        self.browser = browser

    @classmethod
    async def connect(cls, cdp_url: str) -> "BrowserSession":
        try:
            playwright = await async_playwright().start()
        except Exception as exc:
            raise WorkerError(
                ErrorCode.PAGE_NOT_READY,
                "could not start the Playwright transport",
                retryable=True,
            ) from exc

        try:
            browser = await playwright.chromium.connect_over_cdp(cdp_url)
        except Exception as exc:
            try:
                await playwright.stop()
            except Exception:
                pass
            raise WorkerError(
                ErrorCode.PAGE_NOT_READY,
                "could not connect to the browser runtime over CDP",
                retryable=True,
            ) from exc

        return cls(playwright, browser)

    async def page_for(self, url: str) -> "Page":
        target_hostname = urlsplit(url).hostname
        if target_hostname is None:
            raise WorkerError(
                ErrorCode.PAGE_NOT_READY,
                "target URL must include a hostname",
                retryable=False,
            )
        for context in self.browser.contexts:
            for page in context.pages:
                if urlsplit(page.url).hostname == target_hostname:
                    register_console_listener(page)
                    return page

        if not self.browser.contexts:
            raise WorkerError(
                ErrorCode.PAGE_NOT_READY,
                "the CDP browser has no reusable context",
                retryable=True,
            )

        page = await self.browser.contexts[0].new_page()
        register_console_listener(page)
        return page

    async def close(self) -> None:
        await self._playwright.stop()
