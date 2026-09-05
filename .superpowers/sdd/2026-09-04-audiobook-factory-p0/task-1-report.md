# Task 1 implementation report

## Files changed

- `worker/pyproject.toml`
- `worker/src/audiobook_worker/__init__.py`
- `worker/src/audiobook_worker/contracts.py`
- `worker/src/audiobook_worker/errors.py`
- `worker/src/audiobook_worker/config.py`
- `worker/tests/conftest.py`
- `worker/tests/test_contracts.py`
- `worker/tests/test_config.py`

## Design decisions

- Used frozen dataclasses for the public request, preset, result, and runtime status contracts.
- Added deterministic UTF-8 SHA-256 properties for request text and canonical JSON preset snapshots.
- Validated non-blank request identifiers/text and supported `wav`, `mp3`, and `m4b` output formats.
- Kept `RuntimeStatus.code` optional so successful statuses can omit an error code.
- Added the stable `ErrorCode` enum and `WorkerError` contract with explicit retryability.
- Used `pydantic-settings` environment aliases and finite positive timeout/attempt bounds.
- Normalized the CDP URL by removing trailing slashes at the settings boundary.
- No browser, database, orchestration, uploaded-file, runtime-data, credential, or generated-artifact changes were made.

## Tests and verification

TDD RED command (required command):

```text
$ cd worker && python -m pytest -q tests/test_contracts.py tests/test_config.py
/opt/codex/runtimes/codex-primary-runtime/dependencies/python/bin/python: No module named pytest
exit code 1
```

The intended missing-package failure was not observable because this runtime lacks pytest before collection.

Additional checks run:

```text
$ python -m compileall -q src tests
exit code 0

$ git diff --check --no-index /dev/null worker/src/audiobook_worker/contracts.py
exit code 0

$ python manual contract checks
manual contract checks: PASS
exit code 0
```

The declared test extras install was attempted with `python -m pip install -e '.[test]'`, but did not complete before the environment command returned; pytest remained unavailable. Therefore the covering pytest suite could not be run.

## Commits

This report is included in the task commit:

- `f4e524f88e8342a24f6fe430b677beea234c5018` — `feat: add worker contracts and settings`

## Concerns

- Pytest and `pydantic-settings` are not installed in the provided runtime, so the requested pytest PASS output could not be independently verified here. The dependencies are declared in `worker/pyproject.toml`.

## Round 1 fix report

Root cause: Pydantic's `AnyHttpUrl` string representation canonicalizes a host-only URL with a trailing slash. The settings boundary now validates the value as `AnyHttpUrl`, then exposes the normalized value as a string with trailing slashes removed.

Added a focused regression test for an environment-provided `CDP_URL` with a trailing slash.

Exact covering command and output:

```text
$ "$CODEX_PRIMARY_RUNTIME_PYTHON" -m pytest -q tests/test_contracts.py tests/test_config.py
...                                                                      [100%]
3 passed in 0.08s

$ "$CODEX_PRIMARY_RUNTIME_PYTHON" -m compileall -q src tests
exit code 0
```

Fix commit:

- `30f7a4e6d5f53b5a0c00c9658bb27f3f52b26e20` — `fix: normalize worker cdp url`

The report correction and this fix report are committed separately after the implementation fix.

## Fix round 1: review coverage

Added focused regression coverage for the review findings: blank request IDs, unsupported formats, frozen dataclasses, all exact error codes, `WorkerError` fields, successful status with no code, every settings alias, positive bounds, and deterministic/change-sensitive request hashes. No production correction was needed.

Exact commands and output:

```text
$ "$CODEX_PRIMARY_RUNTIME_PYTHON" -m pytest -q tests/test_contracts.py tests/test_config.py
............                                                             [100%]
12 passed in 0.10s

$ "$CODEX_PRIMARY_RUNTIME_PYTHON" -m compileall -q src tests
exit code 0
```

The final test-only commit is recorded after committing this report.
