# Task 5 Implementer Report

## Status

Implemented the Google AI Studio browser provider boundary for Task 6. The
provider reuses `BrowserSession`, `Diagnostics`, `GenerationRequest`,
`TtsPreset`, `WorkerSettings`, `RuntimeStatus`, `ErrorCode`, and `WorkerError`.
It does not validate or publish downloaded audio.

## Files

- `worker/src/audiobook_worker/selectors.py`
- `worker/src/audiobook_worker/provider.py`
- `worker/src/audiobook_worker/ai_studio_provider.py`
- `worker/tests/test_ai_studio_provider.py`
- `worker/tests/fixtures/ai_studio_tts.html`

## Implementation

- Added the asynchronous `TtsProvider` protocol.
- Added `SelectorCandidates` with semantic Playwright locators before
  CSS/contenteditable fallbacks for all required AI Studio controls and page
  states.
- Added `GoogleAiStudioBrowserProvider`, which obtains pages exclusively from
  the existing CDP-attached `BrowserSession`, navigates only when needed, and
  waits for `domcontentloaded` with `generation_timeout_seconds`.
- Added pre-submission authentication and quota detection as non-retryable
  `AUTH_REQUIRED` and `QUOTA_PAUSED` outcomes.
- Added optional model/voice selection, optional style filling, required text
  filling, bounded Generate interaction, `expect_download`, and bounded
  `save_as` to the requested temporary destination.
- Added phase-specific retryable mappings for `PAGE_NOT_READY`,
  `GENERATION_TIMEOUT`, and `DOWNLOAD_TIMEOUT`.
- Added best-effort diagnostics followed by non-retryable `HUMAN_REQUIRED` for
  unknown or unexpected page states. Diagnostic failure cannot replace the
  primary typed error.
- Added deterministic HTML-backed fake-page coverage for semantic controls,
  successful download, preset selection, timeout phases, auth/quota states,
  status methods, and unknown-page diagnostics.

The repository's test dependencies do not include `pytest-asyncio`. After the
brief's exact async test produced the expected missing-module RED, the same
scenario and assertions were executed with the repository's established
`asyncio.run(...)` pattern rather than adding an out-of-scope dependency.

## TDD Evidence

Baseline:

```text
$ cd worker && python -m pytest -q
.............................................................            [100%]
61 passed in 1.77s
exit_status=0
```

Initial specified RED:

```text
$ cd worker && python -m pytest -q tests/test_ai_studio_provider.py
E   ModuleNotFoundError: No module named 'audiobook_worker.ai_studio_provider'
1 error in 0.07s
exit_status=2
```

Focused policy RED after the happy path was green:

```text
$ cd worker && python -m pytest -q tests/test_ai_studio_provider.py
6 failed, 1 passed in 0.28s
exit_status=1
```

The failures showed missing page-readiness handling, leaked generation and
download Playwright timeouts, absent auth/quota classification, and an
untyped unknown-page `AttributeError`.

Focused seam RED after typed policy handling was green:

```text
$ cd worker && python -m pytest -q tests/test_ai_studio_provider.py
3 failed, 7 passed in 0.23s
exit_status=1
```

The failures showed model/voice controls were not selected and both status
methods still returned unconditional success.

Final provider verification:

```text
$ cd worker && python -m pytest -q tests/test_ai_studio_provider.py
..........                                                               [100%]
10 passed in 0.13s
provider_exit_status=0
```

Final full worker verification:

```text
$ cd worker && python -m pytest -q
.......................................................................  [100%]
71 passed in 1.87s
full_worker_exit_status=0
```

The combined final verification command exited with status `0`.

## Limitations

Live Docker/browser runtime verification and live Google AI Studio locator
inspection were unavailable in this environment and remain parked for Task 8,
as directed. Selector candidates therefore follow the required semantic fake
fixture and documented semantic-first/fallback ordering, but have not been
confirmed against the current live AI Studio DOM. This report does not claim
overall P0 success.
