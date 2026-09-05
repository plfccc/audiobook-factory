# Audiobook Factory P0 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a testable P0 pipeline that connects one manually authenticated persistent Chromium session to Google AI Studio, generates one WAV from fixed text, validates it, and records enough diagnostics to operate it safely.

**Architecture:** P0 is intentionally a standalone Python Worker plus a Dockerized Browser Runtime. The Worker connects to Chromium over CDP, delegates page-specific behavior to `GoogleAiStudioBrowserProvider`, validates the downloaded audio with `ffprobe`, and atomically writes an artifact manifest. Spring Boot, PostgreSQL leases, book parsing, and Audiobookshelf publishing remain separate P1–P4 plans after this browser-generation boundary is proven.

**Tech Stack:** Python 3.11+, Playwright Python, Pydantic Settings, pytest/pytest-asyncio, Debian Chromium, Xvfb, x11vnc, noVNC, Supervisor, Docker Compose, FFmpeg/ffprobe.

**Spec:** `docs/superpowers/specs/2026-09-04-audiobook-factory-design.md`

## Global Constraints

- Use one manually authenticated Google account and one Browser Worker in P0.
- Never automate Google passwords, recovery information, 2FA, CAPTCHA solving, account rotation, proxy rotation, anti-detection, or quota bypass.
- Bind host ports `6080` and `9222` to `127.0.0.1` only; access noVNC through an SSH tunnel.
- Keep the Chrome Profile, source text, WAV output, and diagnostics under explicit mounted directories.
- Treat Google AI Studio as an unstable web boundary; all selectors stay inside `GoogleAiStudioBrowserProvider`.
- Prefer Playwright semantic locators (`get_by_role`, `get_by_label`, `get_by_text`) before CSS fallbacks.
- Use bounded timeouts and bounded retries; quota and authentication failures pause for human action.
- Do not introduce Redis, Temporal, object storage, or multi-Worker coordination in P0.
- Do not claim P0 success until the live manual smoke test has produced and validated one WAV.
- Create a worktree before implementation execution if isolation is requested; do not write implementation code while only reviewing this plan.

## File Map

| File | Responsibility |
|---|---|
| `worker/pyproject.toml` | Worker package metadata and pinned dependency ranges |
| `worker/src/audiobook_worker/contracts.py` | Immutable request, preset, result, and status contracts |
| `worker/src/audiobook_worker/errors.py` | Stable error codes and typed exceptions |
| `worker/src/audiobook_worker/config.py` | Environment-backed runtime settings |
| `worker/src/audiobook_worker/browser_session.py` | CDP connection and page selection |
| `worker/src/audiobook_worker/diagnostics.py` | Screenshot, DOM, console, and redacted diagnostic files |
| `worker/src/audiobook_worker/selectors.py` | AI Studio semantic locator candidates |
| `worker/src/audiobook_worker/provider.py` | Provider protocol and capability/auth results |
| `worker/src/audiobook_worker/ai_studio_provider.py` | Google AI Studio page interaction and download |
| `worker/src/audiobook_worker/audio.py` | ffprobe metadata extraction and audio validation |
| `worker/src/audiobook_worker/artifacts.py` | Atomic artifact move and JSON manifest writing |
| `worker/src/audiobook_worker/pipeline.py` | P0 orchestration and error-to-status mapping |
| `worker/src/audiobook_worker/cli.py` | Command-line entry point for one generation request |
| `worker/Dockerfile` | Reproducible Worker image with FFmpeg and Playwright package |
| `infra/browser/Dockerfile` | Chromium, display, VNC, noVNC, and Supervisor image |
| `infra/browser/supervisord.conf` | Browser Runtime process supervision |
| `infra/browser/entrypoint.sh` | Runtime directory setup and Supervisor startup |
| `infra/p0/docker-compose.yml` | P0 Browser + Worker services and localhost-only ports |
| `infra/browser/tests/test_runtime.sh` | Container and port-binding smoke test |
| `worker/tests/` | Unit tests using fake pages/providers and generated WAV fixtures |
| `examples/p0-sample.txt` | Small deterministic input for the live smoke test |
| `examples/p0-preset.json` | Safe, reviewable P0 preset example |
| `scripts/p0-smoke.sh` | Repeatable operator command for the live one-segment test |
| `docs/operations/p0-ubuntu-deployment.md` | Ubuntu deployment, SSH tunnel, login, recovery, and diagnostics guide |

---

### Task 1: Bootstrap Worker Contracts and Test Harness

**Files:**
- Create: `worker/pyproject.toml`
- Create: `worker/src/audiobook_worker/__init__.py`
- Create: `worker/src/audiobook_worker/contracts.py`
- Create: `worker/src/audiobook_worker/errors.py`
- Create: `worker/src/audiobook_worker/config.py`
- Create: `worker/tests/conftest.py`
- Create: `worker/tests/test_contracts.py`
- Create: `worker/tests/test_config.py`

**Interfaces:**
- Produces `TtsPreset`, `GenerationRequest`, `GenerationResult`, `RuntimeStatus`, and `ErrorCode` for all later Worker modules.
- `GenerationRequest` fields: `request_id: str`, `text: str`, `preset: TtsPreset`, `output_dir: pathlib.Path`.
- `TtsPreset` fields: `provider: str`, `model: str`, `voice: str`, `style_prompt: str`, `language: str`, `output_format: str`.
- `GenerationResult` fields: `request_id: str`, `output_path: pathlib.Path`, `sha256: str`, `size_bytes: int`, `duration_seconds: float`, `sample_rate: int`, `channels: int`.
- `RuntimeStatus` fields: `ok: bool`, `code: ErrorCode | None`, `message: str`.
- `ErrorCode` values: `AUTH_REQUIRED`, `HUMAN_REQUIRED`, `QUOTA_PAUSED`, `PAGE_NOT_READY`, `GENERATION_TIMEOUT`, `DOWNLOAD_TIMEOUT`, `AUDIO_INVALID`, `PERMANENT_FAILED`.

- [ ] **Step 1: Write the failing contract tests**

```python
# worker/tests/test_contracts.py
import pytest
from pathlib import Path

from audiobook_worker.contracts import GenerationRequest, TtsPreset


def test_request_keeps_preset_snapshot_and_rejects_blank_text(tmp_path: Path):
    preset = TtsPreset(
        provider="google-ai-studio-browser",
        model="gemini-tts",
        voice="Kore",
        style_prompt="自然、克制地朗读。",
        language="zh-CN",
        output_format="wav",
    )

    request = GenerationRequest("p0-001", "第一句话。", preset, tmp_path)

    assert request.request_id == "p0-001"
    assert request.preset.voice == "Kore"
    with pytest.raises(ValueError, match="text"):
        GenerationRequest("p0-002", "   ", preset, tmp_path)
```

```python
# worker/tests/test_config.py
from audiobook_worker.config import WorkerSettings


def test_default_settings_use_internal_cdp_and_bounded_timeouts(monkeypatch):
    monkeypatch.delenv("CDP_URL", raising=False)
    settings = WorkerSettings()
    assert str(settings.cdp_url) == "http://browser:9222"
    assert settings.generation_timeout_seconds == 180
    assert settings.max_attempts == 3
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd worker && python -m pytest -q tests/test_contracts.py tests/test_config.py`

Expected: FAIL because the package, contracts, and settings do not exist.

- [ ] **Step 3: Add the minimal package and contracts**

Implement immutable dataclasses with `frozen=True`. Reject blank `request_id`, blank `text`, unsupported `output_format`, and non-positive `max_attempts`. Use `BaseSettings` with environment aliases `CDP_URL`, `AI_STUDIO_URL`, `OUTPUT_DIR`, `DIAGNOSTICS_DIR`, `GENERATION_TIMEOUT_SECONDS`, `DOWNLOAD_TIMEOUT_SECONDS`, and `MAX_ATTEMPTS`. Default `AI_STUDIO_URL` to `https://aistudio.google.com/generate-speech`, and keep all timeout defaults finite.

Use this error contract so later modules do not inspect arbitrary exception strings:

```python
class WorkerError(RuntimeError):
    def __init__(self, code: ErrorCode, message: str, *, retryable: bool):
        super().__init__(message)
        self.code = code
        self.retryable = retryable
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd worker && python -m pytest -q tests/test_contracts.py tests/test_config.py`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add worker/pyproject.toml worker/src/audiobook_worker worker/tests
git commit -m "feat: add worker contracts and settings"
```

### Task 2: Build the Persistent Browser Runtime

**Files:**
- Create: `infra/browser/Dockerfile`
- Create: `infra/browser/supervisord.conf`
- Create: `infra/browser/entrypoint.sh`
- Create: `infra/browser/.dockerignore`
- Create: `infra/p0/docker-compose.yml`
- Create: `infra/browser/tests/test_runtime.sh`

**Interfaces:**
- Produces an internal CDP endpoint at `http://browser:9222` and a host-only noVNC endpoint at `http://127.0.0.1:6080`.
- Persists Chromium state at `/data/chrome-profile` inside the container, mounted from `./data/chrome-profile` on the host.
- Keeps downloads at `/data/downloads` and diagnostics at `/data/diagnostics`.

- [ ] **Step 1: Write the failing runtime smoke test**

```bash
#!/usr/bin/env bash
set -euo pipefail

compose=(docker compose -f infra/p0/docker-compose.yml)
"${compose[@]}" up -d --build browser
trap '"${compose[@]}" down' EXIT

for attempt in $(seq 1 30); do
  if curl --silent --fail http://127.0.0.1:9222/json/version >/dev/null \
    && curl --silent --fail http://127.0.0.1:6080/vnc.html >/dev/null; then
    break
  fi
  sleep 2
done

curl --silent --fail http://127.0.0.1:9222/json/version >/dev/null
curl --silent --fail http://127.0.0.1:6080/vnc.html >/dev/null
test "$(docker compose -f infra/p0/docker-compose.yml port browser 6080)" = "127.0.0.1:6080"
test "$(docker compose -f infra/p0/docker-compose.yml port browser 9222)" = "127.0.0.1:9222"
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bash infra/browser/tests/test_runtime.sh`

Expected: FAIL because the Browser service and image are absent.

- [ ] **Step 3: Implement the runtime**

Build from `debian:bookworm-slim` and install pinned-at-build-time Debian packages: Chromium, Xvfb, x11vnc, noVNC, Supervisor, curl, ca-certificates, and Noto CJK fonts. Supervisor must run four processes on display `:99`: Xvfb, Chromium, x11vnc, and noVNC.

Launch Chromium with a persistent profile and CDP, using these mandatory flags:

```text
--user-data-dir=/data/chrome-profile
--remote-debugging-address=0.0.0.0
--remote-debugging-port=9222
--no-first-run
--no-default-browser-check
--disable-dev-shm-usage
```

The Compose service must bind exactly:

```yaml
ports:
  - "127.0.0.1:6080:6080"
  - "127.0.0.1:9222:9222"
```

Mount the profile, downloads, and diagnostics as named host directories. Add a health check for `/json/version`. Do not use `docker compose down -v` in operator scripts so a test cannot remove the profile volume.

- [ ] **Step 4: Run the runtime test to verify it passes**

Run: `bash infra/browser/tests/test_runtime.sh`

Expected: PASS; both endpoints respond, both host bindings report `127.0.0.1`, and the Browser container exits cleanly after the test.

- [ ] **Step 5: Commit**

```bash
git add infra/browser infra/p0/docker-compose.yml
git commit -m "feat: add persistent chromium browser runtime"
```

### Task 3: Add CDP Session Management and Diagnostics

**Files:**
- Create: `worker/src/audiobook_worker/browser_session.py`
- Create: `worker/src/audiobook_worker/diagnostics.py`
- Create: `worker/tests/test_browser_session.py`
- Create: `worker/tests/test_diagnostics.py`

**Interfaces:**
- `BrowserSession.connect(cdp_url: str) -> BrowserSession` connects with `chromium.connect_over_cdp(cdp_url)` and never launches a second browser.
- `BrowserSession.page_for(url: str) -> Page` reuses an existing page whose URL has the same hostname, otherwise creates one page.
- `BrowserSession.close() -> None` closes the Playwright transport without deleting the Chrome Profile.
- `Diagnostics.capture(page, request_id, reason) -> DiagnosticBundle` writes `screenshot.png`, `page.html`, and `console.jsonl` under the request directory.

- [ ] **Step 1: Write failing session and diagnostic tests**

```python
# worker/tests/test_browser_session.py
from unittest.mock import AsyncMock, patch

import pytest

from audiobook_worker.browser_session import BrowserSession


@pytest.mark.asyncio
async def test_connect_uses_cdp_and_does_not_launch_browser():
    playwright = AsyncMock()
    playwright.chromium.connect_over_cdp = AsyncMock(return_value="browser")
    with patch("audiobook_worker.browser_session.async_playwright") as factory:
        factory.return_value.start = AsyncMock(return_value=playwright)
        session = await BrowserSession.connect("http://browser:9222")

    playwright.chromium.connect_over_cdp.assert_awaited_once_with("http://browser:9222")
    playwright.chromium.launch.assert_not_called()
    assert session.browser == "browser"
```

```python
# worker/tests/test_diagnostics.py
import json
from unittest.mock import AsyncMock

import pytest

from audiobook_worker.diagnostics import Diagnostics


@pytest.mark.asyncio
async def test_capture_writes_redacted_diagnostic_bundle(tmp_path):
    page = type("Page", (), {})()
    page.screenshot = AsyncMock()
    page.content = AsyncMock(return_value="<html>safe</html>")
    result = await Diagnostics(tmp_path).capture(page, "p0-001", "page-not-ready")
    assert result.screenshot.exists()
    assert result.html.read_text() == "<html>safe</html>"
    assert json.loads(result.metadata.read_text())["reason"] == "page-not-ready"
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd worker && python -m pytest -q tests/test_browser_session.py tests/test_diagnostics.py`

Expected: FAIL because the session and diagnostic classes do not exist.

- [ ] **Step 3: Implement the minimal session and diagnostic classes**

Use `async_playwright()` as an async context owned by `BrowserSession`. Register a console listener before page interaction and keep only message type, text, and timestamp. `Diagnostics.capture` must create the request directory, call `page.screenshot(path=...)`, write `await page.content()`, and write JSON metadata. Redact `cookie`, `token`, `password`, and `authorization` key/value patterns before writing any URL or log text. On capture failure, write a metadata file containing the failure reason rather than masking the original Worker error.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd worker && python -m pytest -q tests/test_browser_session.py tests/test_diagnostics.py`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add worker/src/audiobook_worker/browser_session.py worker/src/audiobook_worker/diagnostics.py worker/tests/test_browser_session.py worker/tests/test_diagnostics.py
git commit -m "feat: add cdp sessions and diagnostics"
```

### Task 4: Implement Audio Validation and Atomic Artifacts

**Files:**
- Create: `worker/src/audiobook_worker/audio.py`
- Create: `worker/src/audiobook_worker/artifacts.py`
- Create: `worker/tests/test_audio.py`
- Create: `worker/tests/test_artifacts.py`

**Interfaces:**
- `AudioValidator.validate(path: Path) -> AudioMetadata` uses `ffprobe` and a decode check.
- `AudioMetadata` fields: `codec: str`, `sample_rate: int`, `channels: int`, `duration_seconds: float`, `size_bytes: int`, `sha256: str`.
- `ArtifactStore.publish(temp_path: Path, request: GenerationRequest, metadata: AudioMetadata) -> Path` atomically moves the WAV and writes a same-stem JSON manifest.
- Invalid audio raises `WorkerError(ErrorCode.AUDIO_INVALID, ..., retryable=True)`; a valid existing artifact with the same request and text/preset hashes is returned idempotently.

- [ ] **Step 1: Write failing validation and artifact tests**

```python
# worker/tests/test_audio.py
import wave

import pytest

from audiobook_worker.audio import AudioValidator
from audiobook_worker.errors import WorkerError


def write_wav(path):
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(24_000)
        wav.writeframes(b"\x00\x00" * 24_000)


def test_validate_wav_returns_metadata(tmp_path):
    path = tmp_path / "voice.wav"
    write_wav(path)
    metadata = AudioValidator().validate(path)
    assert metadata.channels == 1
    assert metadata.sample_rate == 24_000
    assert metadata.duration_seconds == pytest.approx(1.0, abs=0.05)
    assert len(metadata.sha256) == 64


def test_validate_rejects_corrupt_file(tmp_path):
    path = tmp_path / "broken.wav"
    path.write_bytes(b"not-a-wav")
    with pytest.raises(WorkerError, match="AUDIO_INVALID"):
        AudioValidator().validate(path)
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd worker && python -m pytest -q tests/test_audio.py tests/test_artifacts.py`

Expected: FAIL because the validator and artifact store do not exist.

- [ ] **Step 3: Implement validation and atomic publication**

Call subprocesses with argument arrays, never shell strings:

```python
["ffprobe", "-v", "error", "-show_entries",
 "format=duration:stream=codec_name,sample_rate,channels",
 "-of", "json", str(path)]
```

Reject missing files, files smaller than 1 KiB, non-positive duration, missing audio stream fields, nonzero `ffprobe` exit, and nonzero `ffmpeg -v error -i <path> -f null -` exit. Calculate SHA-256 by streaming 1 MiB blocks. Publish by writing to a temporary sibling, `os.replace` to the final path, then writing the manifest with request ID, text SHA-256, preset snapshot SHA-256, and metadata.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd worker && python -m pytest -q tests/test_audio.py tests/test_artifacts.py`

Expected: PASS with the system `ffprobe` and `ffmpeg` installed.

- [ ] **Step 5: Commit**

```bash
git add worker/src/audiobook_worker/audio.py worker/src/audiobook_worker/artifacts.py worker/tests/test_audio.py worker/tests/test_artifacts.py
git commit -m "feat: validate and publish audio artifacts"
```

### Task 5: Add the Google AI Studio Browser Provider

**Files:**
- Create: `worker/src/audiobook_worker/selectors.py`
- Create: `worker/src/audiobook_worker/provider.py`
- Create: `worker/src/audiobook_worker/ai_studio_provider.py`
- Create: `worker/tests/test_ai_studio_provider.py`
- Create: `worker/tests/fixtures/ai_studio_tts.html`

**Interfaces:**
- `TtsProvider` protocol: `health_check() -> RuntimeStatus`, `check_auth() -> RuntimeStatus`, and `generate(request: GenerationRequest, destination: Path) -> Path`.
- `GoogleAiStudioBrowserProvider(session: BrowserSession, settings: WorkerSettings, diagnostics: Diagnostics)` implements that protocol.
- `SelectorCandidates` contains candidate locator factories for text input, model control, voice control, style prompt, generate action, and logged-out/quota indicators.
- Provider returns the downloaded temporary path; validation and final publication remain in Tasks 4 and 6.

- [ ] **Step 1: Create a deterministic fake-page test and make it fail**

```python
# worker/tests/test_ai_studio_provider.py
import pytest

from audiobook_worker.ai_studio_provider import GoogleAiStudioBrowserProvider
from audiobook_worker.contracts import GenerationRequest, TtsPreset


@pytest.mark.asyncio
async def test_generate_fills_text_preset_and_saves_download(tmp_path, fake_ai_studio_page):
    provider = GoogleAiStudioBrowserProvider.from_page(fake_ai_studio_page)
    request = GenerationRequest(
        "p0-001",
        "这是一个测试片段。",
        TtsPreset("google-ai-studio-browser", "gemini-tts", "Kore", "自然朗读。", "zh-CN", "wav"),
        tmp_path,
    )

    result = await provider.generate(request, tmp_path / "download.wav")

    assert result == tmp_path / "download.wav"
    assert fake_ai_studio_page.text_value == "这是一个测试片段。"
    assert fake_ai_studio_page.selected_voice == "Kore"
    assert fake_ai_studio_page.generate_clicks == 1
```

The fixture must model accessible controls rather than private CSS class names:

```html
<textarea aria-label="Text to speech input"></textarea>
<button aria-label="Model">gemini-tts</button>
<button aria-label="Voice">Kore</button>
<textarea aria-label="Style instructions"></textarea>
<button aria-label="Generate speech">Generate</button>
```

- [ ] **Step 2: Run the provider test to verify it fails**

Run: `cd worker && python -m pytest -q tests/test_ai_studio_provider.py`

Expected: FAIL because the provider and selector candidates do not exist.

- [ ] **Step 3: Inspect the live AI Studio page and record locator candidates**

After Task 2 is running, open noVNC through an SSH tunnel, manually authenticate, and navigate to `WorkerSettings.ai_studio_url`. Use Playwright inspection in a one-off diagnostic command to record accessible roles/names for the six controls. Put the observed candidates, in priority order, in `selectors.py`; keep semantic locators first and CSS/contenteditable fallbacks second. Do not commit cookies, screenshots containing account details, or copied private page content.

- [ ] **Step 4: Implement bounded provider interaction**

Implement this sequence:

1. Reuse or create the AI Studio page and wait for DOM content with the configured navigation timeout.
2. Detect logged-out text or a sign-in control and raise `AUTH_REQUIRED` with `retryable=False`.
3. Detect quota/rate-limit text before submission and raise `QUOTA_PAUSED` with `retryable=False`.
4. Select the configured model and voice only when controls are present; fill style instructions and the text field.
5. Use `page.expect_download(timeout=download_timeout_seconds * 1000)` around the Generate action.
6. Save the download to the requested temporary destination.
7. On a Playwright timeout, classify by phase as `PAGE_NOT_READY`, `GENERATION_TIMEOUT`, or `DOWNLOAD_TIMEOUT`.
8. On unknown page state, capture diagnostics and raise `HUMAN_REQUIRED` instead of retrying internally.

The provider must never call `browser.new_context()`, launch another browser, alter proxy settings, or read authentication files.

- [ ] **Step 5: Run unit tests and commit**

Run: `cd worker && python -m pytest -q tests/test_ai_studio_provider.py`

Expected: PASS with the fake page; live page differences are verified only by the later P0 smoke test.

```bash
git add worker/src/audiobook_worker/selectors.py worker/src/audiobook_worker/provider.py worker/src/audiobook_worker/ai_studio_provider.py worker/tests/test_ai_studio_provider.py worker/tests/fixtures/ai_studio_tts.html
git commit -m "feat: add google ai studio browser provider"
```

### Task 6: Compose the P0 Pipeline and CLI

**Files:**
- Create: `worker/src/audiobook_worker/pipeline.py`
- Create: `worker/src/audiobook_worker/cli.py`
- Create: `worker/src/audiobook_worker/logging.py`
- Create: `worker/tests/test_pipeline.py`
- Create: `worker/tests/test_cli.py`
- Create: `worker/Dockerfile`
- Modify: `infra/p0/docker-compose.yml`
- Create: `examples/p0-sample.txt`
- Create: `examples/p0-preset.json`

**Interfaces:**
- `P0Pipeline(provider, validator, artifacts, diagnostics, settings).run(request) -> GenerationResult`.
- `cli` command: `python -m audiobook_worker.cli --text-file PATH --preset PATH --request-id ID --output-dir PATH`.
- Exit code `0` means `SUCCESS`; `2` means `AUTH_REQUIRED`, `QUOTA_PAUSED`, or `HUMAN_REQUIRED`; `3` means permanent or audio validation failure; `4` means an exhausted retryable failure.

- [ ] **Step 1: Write failing orchestration tests**

```python
# worker/tests/test_pipeline.py
import pytest

from audiobook_worker.errors import WorkerError
from audiobook_worker.pipeline import P0Pipeline


@pytest.mark.asyncio
async def test_pipeline_publishes_valid_audio_and_manifest(fake_provider, validator, artifacts, request):
    result = await P0Pipeline(fake_provider, validator, artifacts).run(request)
    assert result.output_path.exists()
    fake_provider.generate.assert_awaited_once()


@pytest.mark.asyncio
async def test_pipeline_does_not_publish_invalid_audio(fake_provider, invalid_validator, artifacts, request):
    with pytest.raises(WorkerError, match="AUDIO_INVALID"):
        await P0Pipeline(fake_provider, invalid_validator, artifacts).run(request)
    assert not list(request.output_dir.glob("*.wav"))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd worker && python -m pytest -q tests/test_pipeline.py tests/test_cli.py`

Expected: FAIL because the pipeline, CLI, and test fixtures do not exist.

- [ ] **Step 3: Implement the pipeline and CLI**

The pipeline must call `provider.check_auth()`, `provider.generate()`, `validator.validate()`, and `ArtifactStore.publish()` in that order. Capture diagnostics for `HUMAN_REQUIRED`, `AUTH_REQUIRED`, `QUOTA_PAUSED`, and exhausted retryable failures. Use structured JSON logs with `request_id`, `phase`, `error_code`, and `output_path`; never log the full source text or browser storage state.

The CLI must load the preset JSON, read the text as UTF-8, construct a `GenerationRequest`, and return the documented exit code. It must create output and diagnostics directories with mode `0700` when absent.

Build the Worker image from `python:3.11-slim`, install FFmpeg and the package, and set `PYTHONUNBUFFERED=1`. The Compose Worker service must connect to the Browser service using `CDP_URL=http://browser:9222`, share `/data/books` and `/data/diagnostics`, and not expose any host port.

- [ ] **Step 4: Run the unit suite**

Run: `cd worker && python -m pytest -q`

Expected: PASS for all Worker unit tests.

- [ ] **Step 5: Commit**

```bash
git add worker/src/audiobook_worker/pipeline.py worker/src/audiobook_worker/cli.py worker/src/audiobook_worker/logging.py worker/tests worker/Dockerfile infra/p0/docker-compose.yml examples
git commit -m "feat: add p0 generation pipeline and cli"
```

### Task 7: Add the Operator Smoke Test and Ubuntu Runbook

**Files:**
- Create: `scripts/p0-smoke.sh`
- Create: `docs/operations/p0-ubuntu-deployment.md`
- Create: `worker/tests/test_smoke_contract.py`

**Interfaces:**
- `scripts/p0-smoke.sh` is the only documented command for running one live P0 generation.
- Runbook documents directory permissions, SSH tunnel, manual login, restart persistence, quota/auth recovery, diagnostics, and cleanup of temporary artifacts.

- [ ] **Step 1: Write the smoke contract test**

```python
# worker/tests/test_smoke_contract.py
from pathlib import Path

ROOT = Path(__file__).parents[2]


def test_smoke_script_uses_fixed_sample_and_does_not_publish_debug_ports():
    script = (ROOT / "scripts/p0-smoke.sh").read_text()
    assert "examples/p0-sample.txt" in script
    assert "examples/p0-preset.json" in script
    compose = (ROOT / "infra/p0/docker-compose.yml").read_text()
    assert "127.0.0.1:6080" in compose
    assert "127.0.0.1:9222" in compose
```

- [ ] **Step 2: Run it to verify it fails**

Run: `python -m pytest -q worker/tests/test_smoke_contract.py`

Expected: FAIL because the smoke script and runbook do not exist yet.

- [ ] **Step 3: Implement the operator flow**

The script must:

1. Check that Docker Compose is available.
2. Create `data/chrome-profile`, `data/books`, and `data/diagnostics` with owner-only permissions.
3. Start the Browser and Worker services.
4. Print the SSH tunnel command `ssh -L 6080:127.0.0.1:6080 root@SERVER` without trying to log in.
5. Refuse to invoke generation until the operator sets `P0_LOGIN_CONFIRMED=1`, making manual login an explicit step.
6. Run the fixed sample with request ID `p0-manual-001`.
7. Check that the WAV and JSON manifest exist and run `ffprobe` against the WAV.
8. Print the diagnostic directory on failure and leave the Chrome Profile intact.

The runbook must include the exact commands for `docker compose -f infra/p0/docker-compose.yml up -d --build`, SSH tunneling, checking `/json/version`, running the smoke script, viewing logs, and restarting without deleting `data/chrome-profile`. It must state that noVNC and CDP are not public services.

- [ ] **Step 4: Run static and unit verification**

Run:

```bash
cd worker && python -m pytest -q
bash -n scripts/p0-smoke.sh
git diff --check
```

Expected: PASS with no whitespace errors.

- [ ] **Step 5: Commit**

```bash
git add scripts/p0-smoke.sh docs/operations/p0-ubuntu-deployment.md worker/tests/test_smoke_contract.py
git commit -m "docs: add p0 ubuntu smoke runbook"
```

### Task 8: Execute and Verify the Live P0 Boundary

**Files:**
- Modify only files required by the live verification failures; keep changes inside `worker/src/audiobook_worker/`, `infra/`, `scripts/`, or the runbook.
- Create no credentials or browser Profile files in Git.

**Interfaces:**
- Live input: `examples/p0-sample.txt` and `examples/p0-preset.json`.
- Live output: `data/books/p0-manual-001.wav`, its JSON manifest, and request-scoped diagnostics.

- [ ] **Step 1: Run the container test and unit suite from a clean checkout**

Run:

```bash
bash infra/browser/tests/test_runtime.sh
cd worker && python -m pytest -q
```

Expected: PASS before using a Google account.

- [ ] **Step 2: Manually authenticate through the SSH tunnel**

Run from a local machine:

```bash
ssh -L 6080:127.0.0.1:6080 root@SERVER
```

Open `http://127.0.0.1:6080/vnc.html`, complete Google authentication manually, navigate to the configured AI Studio speech page, and confirm the configured model and voice are available. Do not save authentication data in the repository or runbook.

- [ ] **Step 3: Run exactly one live generation**

Run: `P0_LOGIN_CONFIRMED=1 bash scripts/p0-smoke.sh`

Expected: one validated WAV, one manifest, structured logs containing `p0-manual-001`, and no more than the configured bounded attempts.

- [ ] **Step 4: Verify restart persistence and failure boundaries**

Restart with `docker compose -f infra/p0/docker-compose.yml restart browser worker`, verify the Browser Profile remains mounted, and check that the CDP endpoint is still reachable. If authentication is invalid, verify the CLI returns the documented `AUTH_REQUIRED` exit code. If quota text is present, verify it returns `QUOTA_PAUSED` and does not loop.

- [ ] **Step 5: Review and commit only verified fixes**

Run:

```bash
git diff --check
git status --short
git log --oneline --decorate -8
```

Commit only source/config/runbook fixes. Never commit `data/`, screenshots, downloaded audio, logs, `.env`, or the Chrome Profile.

## P1+ Plan Boundary

After P0 passes, create separate implementation plans so each later subsystem stays independently testable:

1. P1 Control Center: Spring Boot 3, PostgreSQL schema, segment lifecycle, `FOR UPDATE SKIP LOCKED`, lease/heartbeat, and API tests.
2. P2 Book Processing: TXT/Markdown/EPUB parsing, immutable Book Version, semantic segmentation, and preview UI/API.
3. P3 Audio Pipeline: chapter merge, MP3/M4B metadata, disk cleanup, and ffmpeg integration tests.
4. P4 Audiobookshelf: library path conventions, scan API, publish state, and retry tests.
5. P5 UI: progress, logs, preview confirmation, pause/resume, and manual retry.
6. P6 Additional Providers and diagnostics assistant, subject to the same authentication, quota, and safety boundaries.

## Plan Self-Review

- P0 requirements covered: persistent Profile, SSH-tunneled noVNC, internal CDP, one AI Studio generation, semantic selectors, diagnostics, audio validation, bounded error handling, and restart verification.
- Intentionally deferred: database lease, EPUB import, chapter merging, M4B, Audiobookshelf scan, management UI, and Agent repair; each has a separate plan boundary above.
- Placeholder scan completed: no step depends on an unnamed component or an unbounded “handle errors” instruction; all P0 interfaces, paths, commands, statuses, and expected outcomes are named.
- Type consistency checked: `GenerationRequest` feeds Provider, Pipeline, and ArtifactStore; `AudioMetadata` feeds ArtifactStore and `GenerationResult`; `ErrorCode` is shared by Provider, Pipeline, CLI, and diagnostics.
