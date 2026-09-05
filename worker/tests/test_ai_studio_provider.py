import asyncio
from html.parser import HTMLParser
from pathlib import Path

import pytest
from playwright.async_api import TimeoutError as PlaywrightTimeoutError

from audiobook_worker.ai_studio_provider import GoogleAiStudioBrowserProvider
from audiobook_worker.config import WorkerSettings
from audiobook_worker.contracts import GenerationRequest, RuntimeStatus, TtsPreset
from audiobook_worker.errors import ErrorCode, WorkerError
from audiobook_worker.selectors import AI_STUDIO_SELECTORS


class _AccessibleControlParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.labels: set[str] = set()

    def handle_starttag(self, tag, attrs):
        attributes = dict(attrs)
        if "aria-label" in attributes:
            self.labels.add(attributes["aria-label"])


class _FakeDownload:
    async def save_as(self, destination):
        Path(destination).write_bytes(b"fake wav")


class _FakeDownloadInfo:
    value = _FakeDownload()


class _FakeDownloadContext:
    def __init__(self, page):
        self.page = page

    async def __aenter__(self):
        return _FakeDownloadInfo()

    async def __aexit__(self, exc_type, exc, traceback):
        if exc_type is None and self.page.timeout_phase == "download":
            raise PlaywrightTimeoutError("download timed out")
        return False


class _FakeLocator:
    def __init__(self, page, label):
        self.page = page
        self.label = label

    async def count(self):
        return int(self.page.has_control(self.label))

    async def is_visible(self, **kwargs):
        return self.page.has_control(self.label)

    async def fill(self, value, **kwargs):
        if self.label == "Text to speech input":
            self.page.text_value = value
        elif self.label == "Style instructions":
            self.page.style_value = value

    async def click(self, **kwargs):
        if self.label == "Generate speech":
            if self.page.timeout_phase == "generation":
                raise PlaywrightTimeoutError("generation timed out")
            self.page.generate_clicks += 1
        elif self.label in {"Model", "Voice"}:
            self.page.open_control = self.label
        elif self.label in self.page.options:
            if self.page.open_control == "Model":
                self.page.selected_model = self.label
            elif self.page.open_control == "Voice":
                self.page.selected_voice = self.label
            self.page.open_control = None

    async def inner_text(self, **kwargs):
        if self.label == "Model":
            return self.page.selected_model
        if self.label == "Voice":
            return self.page.selected_voice
        return self.label


class FakeAiStudioPage:
    def __init__(self, fixture_path):
        parser = _AccessibleControlParser()
        parser.feed(fixture_path.read_text(encoding="utf-8"))
        self.labels = parser.labels
        self.text_value = ""
        self.style_value = ""
        self.selected_model = "gemini-tts"
        self.selected_voice = "Kore"
        self.options = {"gemini-tts", "Kore"}
        self.open_control = None
        self.generate_clicks = 0
        self.logged_out = False
        self.quota_paused = False
        self.timeout_phase = None
        self.closed = False
        self.wait_for_calls = 0
        self.ready_after_waits = None
        self.url = "https://aistudio.google.com/generate-speech"

    def has_control(self, label):
        if label == "Sign in":
            return self.logged_out
        if label == "quota exceeded":
            return self.quota_paused
        if label in self.options:
            return True
        return label in self.labels

    def get_by_label(self, label, **kwargs):
        return _FakeLocator(self, label)

    def get_by_role(self, role, name=None, **kwargs):
        if role == "option" and isinstance(name, str) and name in self.options:
            return _FakeLocator(self, name)
        labels = set(self.labels)
        if self.logged_out:
            labels.add("Sign in")
        for label in labels:
            if name is None or (
                hasattr(name, "search") and name.search(label)
            ) or name == label:
                return _FakeLocator(self, label)
        return _FakeLocator(self, "missing")

    def get_by_text(self, text, **kwargs):
        if self.quota_paused and text.search("quota exceeded"):
            return _FakeLocator(self, "quota exceeded")
        return _FakeLocator(self, "missing")

    def locator(self, selector):
        return _FakeLocator(self, "missing")

    def expect_download(self, **kwargs):
        return _FakeDownloadContext(self)

    async def wait_for_load_state(self, state, **kwargs):
        if self.timeout_phase == "page":
            raise PlaywrightTimeoutError("page timed out")

    async def wait_for_timeout(self, milliseconds):
        self.wait_for_calls += 1
        if (
            self.ready_after_waits is not None
            and self.wait_for_calls >= self.ready_after_waits
        ):
            self.labels.update({"Text to speech input", "Generate speech"})

    def is_closed(self):
        return self.closed

    async def close(self, **kwargs):
        self.closed = True


class _FakeSession:
    def __init__(self, page):
        self.page = page

    async def page_for(self, url):
        return self.page


class _CountingFakeSession(_FakeSession):
    def __init__(self, page):
        super().__init__(page)
        self.page_for_calls = 0

    async def page_for(self, url):
        self.page_for_calls += 1
        return await super().page_for(url)


class _RecordingDiagnostics:
    def __init__(self, *, fail=False):
        self.fail = fail
        self.captures = []

    async def capture(self, page, request_id, reason):
        self.captures.append((page, request_id, reason))
        if self.fail:
            raise OSError("diagnostics unavailable")


class _MultipleMatchLocator:
    def __init__(self, visible_indexes):
        self.visible_indexes = set(visible_indexes)
        self.index = None

    async def count(self):
        return 2

    def nth(self, index):
        locator = _SingleMatchLocator(index in self.visible_indexes)
        locator.index = index
        return locator

    async def is_visible(self, **kwargs):
        raise RuntimeError("strict mode violation")


class _SingleMatchLocator:
    def __init__(self, visible):
        self.visible = visible
        self.index = None

    async def is_visible(self, **kwargs):
        return self.visible


class _PatternRecordingPage:
    def __init__(self):
        self.pattern = None

    def get_by_text(self, pattern, **kwargs):
        self.pattern = pattern
        return object()


@pytest.fixture
def fake_ai_studio_page():
    fixture = Path(__file__).parent / "fixtures" / "ai_studio_tts.html"
    return FakeAiStudioPage(fixture)


def _request(tmp_path):
    return GenerationRequest(
        "p0-001",
        "这是一个测试片段。",
        TtsPreset(
            "google-ai-studio-browser",
            "gemini-tts",
            "Kore",
            "自然朗读。",
            "zh-CN",
            "wav",
        ),
        tmp_path,
    )


def _provider(tmp_path, page, diagnostics=None):
    settings = WorkerSettings(
        AI_STUDIO_URL="https://aistudio.google.com/generate-speech",
        OUTPUT_DIR=tmp_path,
        DIAGNOSTICS_DIR=tmp_path / "diagnostics",
        GENERATION_TIMEOUT_SECONDS=1,
        DOWNLOAD_TIMEOUT_SECONDS=1,
    )
    return GoogleAiStudioBrowserProvider(
        _FakeSession(page), settings, diagnostics or _RecordingDiagnostics()
    )


def test_generate_fills_text_preset_and_saves_download(tmp_path, fake_ai_studio_page):
    async def exercise():
        provider = GoogleAiStudioBrowserProvider.from_page(fake_ai_studio_page)
        request = GenerationRequest(
            "p0-001",
            "这是一个测试片段。",
            TtsPreset(
                "google-ai-studio-browser",
                "gemini-tts",
                "Kore",
                "自然朗读。",
                "zh-CN",
                "wav",
            ),
            tmp_path,
        )

        result = await provider.generate(request, tmp_path / "download.wav")

        assert result == tmp_path / "download.wav"
        assert fake_ai_studio_page.text_value == "这是一个测试片段。"
        assert fake_ai_studio_page.selected_voice == "Kore"
        assert fake_ai_studio_page.generate_clicks == 1

    asyncio.run(exercise())


@pytest.mark.parametrize(
    ("phase", "expected_code"),
    [
        ("page", ErrorCode.PAGE_NOT_READY),
        ("generation", ErrorCode.GENERATION_TIMEOUT),
        ("download", ErrorCode.DOWNLOAD_TIMEOUT),
    ],
)
def test_generate_maps_playwright_timeout_by_phase(
    tmp_path, fake_ai_studio_page, phase, expected_code
):
    async def exercise():
        fake_ai_studio_page.timeout_phase = phase
        provider = _provider(tmp_path, fake_ai_studio_page)

        with pytest.raises(WorkerError) as caught:
            await provider.generate(_request(tmp_path), tmp_path / "download.wav")

        assert caught.value.code is expected_code
        assert caught.value.retryable is True

    asyncio.run(exercise())


@pytest.mark.parametrize(
    ("page_state", "expected_code"),
    [
        ("logged_out", ErrorCode.AUTH_REQUIRED),
        ("quota_paused", ErrorCode.QUOTA_PAUSED),
    ],
)
def test_generate_detects_human_action_states_before_submission(
    tmp_path, fake_ai_studio_page, page_state, expected_code
):
    async def exercise():
        setattr(fake_ai_studio_page, page_state, True)
        provider = _provider(tmp_path, fake_ai_studio_page)

        with pytest.raises(WorkerError) as caught:
            await provider.generate(_request(tmp_path), tmp_path / "download.wav")

        assert caught.value.code is expected_code
        assert caught.value.retryable is False
        assert fake_ai_studio_page.generate_clicks == 0

    asyncio.run(exercise())


def test_unknown_page_captures_diagnostics_without_replacing_worker_error(
    tmp_path, fake_ai_studio_page
):
    async def exercise():
        fake_ai_studio_page.labels.clear()
        diagnostics = _RecordingDiagnostics(fail=True)
        provider = _provider(tmp_path, fake_ai_studio_page, diagnostics)

        with pytest.raises(WorkerError) as caught:
            await provider.generate(_request(tmp_path), tmp_path / "download.wav")

        assert caught.value.code is ErrorCode.HUMAN_REQUIRED
        assert caught.value.retryable is False
        assert diagnostics.captures[0][1] == "p0-001"

    asyncio.run(exercise())


def test_generate_selects_present_model_and_voice_controls(
    tmp_path, fake_ai_studio_page
):
    async def exercise():
        fake_ai_studio_page.selected_model = "legacy-model"
        fake_ai_studio_page.selected_voice = "Puck"
        provider = _provider(tmp_path, fake_ai_studio_page)

        await provider.generate(_request(tmp_path), tmp_path / "download.wav")

        assert fake_ai_studio_page.selected_model == "gemini-tts"
        assert fake_ai_studio_page.selected_voice == "Kore"

    asyncio.run(exercise())


def test_check_auth_reports_logged_out_status(tmp_path, fake_ai_studio_page):
    async def exercise():
        fake_ai_studio_page.logged_out = True
        provider = _provider(tmp_path, fake_ai_studio_page)

        status = await provider.check_auth()

        assert status == RuntimeStatus(
            False, ErrorCode.AUTH_REQUIRED, "Google AI Studio requires manual sign-in"
        )

    asyncio.run(exercise())


def test_generate_waits_for_spa_controls_after_navigation(
    tmp_path, fake_ai_studio_page
):
    async def exercise():
        fake_ai_studio_page.labels.clear()
        fake_ai_studio_page.ready_after_waits = 2
        provider = _provider(tmp_path, fake_ai_studio_page)

        result = await provider.generate(
            _request(tmp_path), tmp_path / "download.wav"
        )

        assert result == tmp_path / "download.wav"
        assert fake_ai_studio_page.wait_for_calls >= 2
        assert fake_ai_studio_page.generate_clicks == 1

    asyncio.run(exercise())


def test_first_visible_checks_each_match_without_strict_mode_failure():
    async def exercise():
        locator = await GoogleAiStudioBrowserProvider._first_visible(
            object(), (lambda _page: _MultipleMatchLocator({1}),)
        )

        assert locator is not None
        assert locator.index == 1

    asyncio.run(exercise())


def test_quota_indicator_does_not_match_rate_limits_navigation_link():
    page = _PatternRecordingPage()

    AI_STUDIO_SELECTORS.quota_indicators[0](page)

    assert page.pattern.search("Rate limits") is None
    assert page.pattern.search("Quota exceeded") is not None
    assert page.pattern.search("Rate limit exceeded") is not None


def test_capture_diagnostics_reuses_page_after_navigation_timeout(
    tmp_path, fake_ai_studio_page
):
    async def exercise():
        fake_ai_studio_page.timeout_phase = "page"
        session = _CountingFakeSession(fake_ai_studio_page)
        diagnostics = _RecordingDiagnostics()
        settings = WorkerSettings(
            AI_STUDIO_URL="https://aistudio.google.com/generate-speech",
            OUTPUT_DIR=tmp_path,
            DIAGNOSTICS_DIR=tmp_path / "diagnostics",
            GENERATION_TIMEOUT_SECONDS=1,
        )
        provider = GoogleAiStudioBrowserProvider(session, settings, diagnostics)

        status = await provider.check_auth()
        assert status.code is ErrorCode.PAGE_NOT_READY

        await provider.capture_diagnostics("p0-001", "auth:PAGE_NOT_READY")

        assert session.page_for_calls == 1
        assert diagnostics.captures == []

    asyncio.run(exercise())


def test_navigation_timeout_closes_stuck_page(tmp_path, fake_ai_studio_page):
    async def exercise():
        fake_ai_studio_page.timeout_phase = "page"
        provider = _provider(tmp_path, fake_ai_studio_page)

        status = await provider.check_auth()

        assert status.code is ErrorCode.PAGE_NOT_READY
        assert fake_ai_studio_page.closed is True

    asyncio.run(exercise())


def test_health_check_reports_unknown_page_and_captures_diagnostics(
    tmp_path, fake_ai_studio_page
):
    async def exercise():
        fake_ai_studio_page.labels.clear()
        diagnostics = _RecordingDiagnostics()
        provider = _provider(tmp_path, fake_ai_studio_page, diagnostics)

        status = await provider.health_check()

        assert status.code is ErrorCode.HUMAN_REQUIRED
        assert status.ok is False
        assert diagnostics.captures[0][1] == "health-check"

    asyncio.run(exercise())
