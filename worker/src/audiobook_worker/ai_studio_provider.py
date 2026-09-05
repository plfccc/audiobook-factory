import asyncio
import inspect
from pathlib import Path
from typing import Any, NoReturn

from playwright.async_api import TimeoutError as PlaywrightTimeoutError

from .browser_session import BrowserSession
from .config import WorkerSettings
from .contracts import GenerationRequest, RuntimeStatus
from .diagnostics import Diagnostics
from .errors import ErrorCode, WorkerError
from .selectors import AI_STUDIO_SELECTORS, LocatorFactory, SelectorCandidates


class _StaticPageSession:
    def __init__(self, page: Any):
        self.page = page

    async def page_for(self, url: str) -> Any:
        return self.page


class GoogleAiStudioBrowserProvider:
    def __init__(
        self,
        session: BrowserSession,
        settings: WorkerSettings,
        diagnostics: Diagnostics,
        *,
        selectors: SelectorCandidates = AI_STUDIO_SELECTORS,
    ):
        self.session = session
        self.settings = settings
        self.diagnostics = diagnostics
        self.selectors = selectors

    @classmethod
    def from_page(cls, page: Any) -> "GoogleAiStudioBrowserProvider":
        settings = WorkerSettings()
        return cls(
            _StaticPageSession(page),
            settings,
            Diagnostics(settings.diagnostics_dir),
        )

    async def health_check(self) -> RuntimeStatus:
        try:
            page = await self._prepare_page()
            if await self._first_visible(page, self.selectors.logged_out_indicators):
                return RuntimeStatus(
                    False,
                    ErrorCode.AUTH_REQUIRED,
                    "Google AI Studio requires manual sign-in",
                )
            if await self._first_visible(page, self.selectors.quota_indicators):
                return RuntimeStatus(
                    False,
                    ErrorCode.QUOTA_PAUSED,
                    "Google AI Studio quota or rate limit requires human action",
                )
            text_input = await self._first_visible(page, self.selectors.text_input)
            generate_action = await self._first_visible(
                page, self.selectors.generate_action
            )
            if text_input is None or generate_action is None:
                reason = "required Google AI Studio controls were not found"
                await self._capture_diagnostics(page, "health-check", reason)
                return RuntimeStatus(False, ErrorCode.HUMAN_REQUIRED, reason)
            return RuntimeStatus(True, None, "Google AI Studio page is available")
        except WorkerError as exc:
            return RuntimeStatus(False, exc.code, str(exc))

    async def check_auth(self) -> RuntimeStatus:
        try:
            page = await self._prepare_page()
            if await self._first_visible(page, self.selectors.logged_out_indicators):
                return RuntimeStatus(
                    False,
                    ErrorCode.AUTH_REQUIRED,
                    "Google AI Studio requires manual sign-in",
                )
            return RuntimeStatus(
                True, None, "Google AI Studio session is authenticated"
            )
        except WorkerError as exc:
            return RuntimeStatus(False, exc.code, str(exc))

    async def generate(
        self, request: GenerationRequest, destination: Path
    ) -> Path:
        page = await self._prepare_page()
        if await self._first_visible(page, self.selectors.logged_out_indicators):
            raise WorkerError(
                ErrorCode.AUTH_REQUIRED,
                "Google AI Studio requires manual sign-in",
                retryable=False,
            )
        if await self._first_visible(page, self.selectors.quota_indicators):
            raise WorkerError(
                ErrorCode.QUOTA_PAUSED,
                "Google AI Studio quota or rate limit requires human action",
                retryable=False,
            )

        text_input = await self._first_visible(page, self.selectors.text_input)
        style_prompt = await self._first_visible(page, self.selectors.style_prompt)
        generate_action = await self._first_visible(page, self.selectors.generate_action)
        if text_input is None or generate_action is None:
            await self._human_required(
                page,
                request.request_id,
                "required Google AI Studio controls were not found",
            )

        generation_timeout_ms = self.settings.generation_timeout_seconds * 1000
        try:
            await self._select_if_present(
                page,
                self.selectors.model_control,
                request.preset.model,
                generation_timeout_ms,
            )
            await self._select_if_present(
                page,
                self.selectors.voice_control,
                request.preset.voice,
                generation_timeout_ms,
            )
            if style_prompt is not None:
                await style_prompt.fill(
                    request.preset.style_prompt,
                    timeout=generation_timeout_ms,
                )
            await text_input.fill(request.text, timeout=generation_timeout_ms)
        except PlaywrightTimeoutError as exc:
            raise WorkerError(
                ErrorCode.GENERATION_TIMEOUT,
                "Google AI Studio generation controls timed out",
                retryable=True,
            ) from exc
        except Exception as exc:
            await self._human_required(
                page,
                request.request_id,
                f"could not configure Google AI Studio controls: {exc}",
                cause=exc,
            )

        click_completed = False
        try:
            async with page.expect_download(
                timeout=self.settings.download_timeout_seconds * 1000
            ) as download_info:
                await generate_action.click(timeout=generation_timeout_ms)
                click_completed = True
        except PlaywrightTimeoutError as exc:
            code = (
                ErrorCode.DOWNLOAD_TIMEOUT
                if click_completed
                else ErrorCode.GENERATION_TIMEOUT
            )
            raise WorkerError(
                code,
                "Google AI Studio download timed out"
                if click_completed
                else "Google AI Studio generation action timed out",
                retryable=True,
            ) from exc
        except Exception as exc:
            await self._human_required(
                page,
                request.request_id,
                f"Google AI Studio generation failed unexpectedly: {exc}",
                cause=exc,
            )

        download = download_info.value
        if inspect.isawaitable(download):
            download = await download
        try:
            await asyncio.wait_for(
                download.save_as(str(destination)),
                timeout=self.settings.download_timeout_seconds,
            )
        except (TimeoutError, PlaywrightTimeoutError) as exc:
            raise WorkerError(
                ErrorCode.DOWNLOAD_TIMEOUT,
                "Google AI Studio download save timed out",
                retryable=True,
            ) from exc
        except Exception as exc:
            await self._human_required(
                page,
                request.request_id,
                f"Google AI Studio download could not be saved: {exc}",
                cause=exc,
            )
        return destination

    async def _prepare_page(self) -> Any:
        timeout_ms = self.settings.generation_timeout_seconds * 1000
        target_url = str(self.settings.ai_studio_url)
        try:
            page = await self.session.page_for(target_url)
            if str(getattr(page, "url", "")) != target_url:
                await page.goto(
                    target_url,
                    wait_until="domcontentloaded",
                    timeout=timeout_ms,
                )
            else:
                await page.wait_for_load_state("domcontentloaded", timeout=timeout_ms)
            return page
        except WorkerError:
            raise
        except PlaywrightTimeoutError as exc:
            raise WorkerError(
                ErrorCode.PAGE_NOT_READY,
                "Google AI Studio page did not become ready",
                retryable=True,
            ) from exc
        except Exception as exc:
            raise WorkerError(
                ErrorCode.PAGE_NOT_READY,
                "Google AI Studio page could not be prepared",
                retryable=True,
            ) from exc

    async def _human_required(
        self,
        page: Any,
        request_id: str,
        reason: str,
        *,
        cause: Exception | None = None,
    ) -> NoReturn:
        await self._capture_diagnostics(page, request_id, reason)
        error = WorkerError(ErrorCode.HUMAN_REQUIRED, reason, retryable=False)
        if cause is None:
            raise error
        raise error from cause

    async def _capture_diagnostics(
        self, page: Any, request_id: str, reason: str
    ) -> None:
        try:
            await self.diagnostics.capture(page, request_id, reason)
        except Exception:
            pass

    async def _select_if_present(
        self,
        page: Any,
        candidates: tuple[LocatorFactory, ...],
        value: str,
        timeout_ms: int,
    ) -> None:
        control = await self._first_visible(page, candidates)
        if control is None:
            return
        current_value = (await control.inner_text(timeout=timeout_ms)).strip()
        if current_value == value:
            return

        await control.click(timeout=timeout_ms)
        option = await self._first_visible(
            page,
            (
                lambda candidate_page: candidate_page.get_by_role(
                    "option", name=value, exact=True
                ),
                lambda candidate_page: candidate_page.get_by_role(
                    "button", name=value, exact=True
                ),
                lambda candidate_page: candidate_page.get_by_text(value, exact=True),
                lambda candidate_page: candidate_page.locator(
                    '[role="option"]'
                ).filter(has_text=value),
            ),
        )
        if option is None:
            raise RuntimeError(f"option {value!r} was not found")
        await option.click(timeout=timeout_ms)

    @staticmethod
    async def _first_visible(
        page: Any, candidates: tuple[LocatorFactory, ...]
    ) -> Any | None:
        for factory in candidates:
            try:
                locator = factory(page)
                if await locator.count() and await locator.is_visible():
                    return locator
            except (AttributeError, TypeError):
                continue
        return None
