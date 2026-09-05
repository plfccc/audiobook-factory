# Task 4 Implementation Report

## Status

DONE_WITH_CONCERNS

Task 4 implements ffprobe metadata extraction, ffmpeg full-decode validation, streamed SHA-256 calculation, same-filesystem atomic WAV/manifest replacement, conflict-safe publication, and request-hash idempotency. No Browser Runtime, Provider, pipeline retry, CLI, database, book parsing, or Audiobookshelf files were changed.

## Files changed

- `worker/src/audiobook_worker/audio.py`
- `worker/src/audiobook_worker/artifacts.py`
- `worker/tests/test_audio.py`
- `worker/tests/test_artifacts.py`
- `.superpowers/sdd/2026-09-04-audiobook-factory-p0/task-4-report.md`

## Implementation decisions

- Reused Task 1's `GenerationRequest`, `ErrorCode`, and `WorkerError`; no contracts were duplicated.
- `AudioMetadata` is an immutable dataclass with the exact requested fields: codec, sample rate, channels, duration, size, and SHA-256.
- `AudioValidator` rejects missing/non-file inputs, files smaller than 1 KiB, failed or malformed ffprobe results, non-positive/non-finite duration, missing audio fields, and failed full decode.
- ffprobe and ffmpeg are invoked only through argument lists with `shell` left disabled. Tool startup failures are mapped to retryable `AUDIO_INVALID` errors.
- SHA-256 and publication copies stream in 1 MiB blocks.
- `ArtifactStore` validates that the source still matches its supplied validation metadata before publication, preventing a changed download from being published.
- WAV and manifest bytes are each written and fsynced through hidden temporary siblings and installed with `os.replace`; the containing directory is fsynced before success is returned.
- The source download is removed only after both final files are installed. In-process manifest publication failure rolls back the newly installed WAV when it still matches the candidate metadata.
- Existing output is returned idempotently only when request ID, `request.text_sha256`, and `request.preset_snapshot_sha256` match and the existing WAV revalidates against its recorded manifest metadata.
- Existing partial, malformed, tampered, or differently keyed artifacts are refused with `FileExistsError`; unsafe path-like request IDs are rejected.

## TDD evidence

### Clean baseline

Command:

```console
$ cd worker && "$CODEX_PRIMARY_RUNTIME_PYTHON" -m pytest -q
```

Output and exit status:

```text
.......................................                                  [100%]
39 passed in 0.15s
exit 0
```

### Initial RED: Task 4 modules absent

Command:

```console
$ cd worker && "$CODEX_PRIMARY_RUNTIME_PYTHON" -m pytest -q tests/test_audio.py tests/test_artifacts.py
```

Output and exit status:

```text
==================================== ERRORS ====================================
_____________________ ERROR collecting tests/test_audio.py _____________________
E   ModuleNotFoundError: No module named 'audiobook_worker.audio'
___________________ ERROR collecting tests/test_artifacts.py ___________________
E   ModuleNotFoundError: No module named 'audiobook_worker.artifacts'
=========================== short test summary info ============================
ERROR tests/test_audio.py
ERROR tests/test_artifacts.py
!!!!!!!!!!!!!!!!!!! Interrupted: 2 errors during collection !!!!!!!!!!!!!!!!!!!!
2 errors in 0.08s
exit 2
```

The failures were at the intended absent-module boundary.

### First GREEN

Command:

```console
$ cd worker && "$CODEX_PRIMARY_RUNTIME_PYTHON" -m pytest -q tests/test_audio.py tests/test_artifacts.py
```

Output and exit status:

```text
...................                                                      [100%]
19 passed in 1.47s
exit 0
```

### Idempotency self-review RED

Self-review identified that the first implementation incorrectly compared the new candidate's audio hash to the existing artifact. A second valid WAV with different bytes but the same request/text/preset identity must return the already valid artifact.

Command:

```console
$ cd worker && "$CODEX_PRIMARY_RUNTIME_PYTHON" -m pytest -q tests/test_artifacts.py::test_publish_returns_matching_existing_artifact_idempotently
```

Output and exit status:

```text
F                                                                        [100%]
=================================== FAILURES ===================================
_________ test_publish_returns_matching_existing_artifact_idempotently _________
E   FileExistsError: unrelated or invalid artifact already exists: .../output/p0-001.wav
=========================== short test summary info ============================
FAILED tests/test_artifacts.py::test_publish_returns_matching_existing_artifact_idempotently
1 failed in 0.25s
exit 1
```

The idempotency check was corrected to key on request ID and the two required request hashes while independently revalidating the recorded artifact.

### Idempotency GREEN and focused regression GREEN

Commands:

```console
$ cd worker && "$CODEX_PRIMARY_RUNTIME_PYTHON" -m pytest -q tests/test_artifacts.py::test_publish_returns_matching_existing_artifact_idempotently
$ cd worker && "$CODEX_PRIMARY_RUNTIME_PYTHON" -m pytest -q tests/test_audio.py tests/test_artifacts.py
```

Output and exit status:

```text
.                                                                        [100%]
1 passed in 0.28s
...................                                                      [100%]
19 passed in 1.56s
exit 0
```

## Final verification

Commands:

```console
$ git diff --cached --check
$ command -v ffprobe
$ command -v ffmpeg
$ "$CODEX_PRIMARY_RUNTIME_PYTHON" -m compileall -q worker/src worker/tests
$ "$CODEX_PRIMARY_RUNTIME_PYTHON" -m pytest -q worker/tests/test_audio.py worker/tests/test_artifacts.py
$ "$CODEX_PRIMARY_RUNTIME_PYTHON" -m pytest -q worker/tests
```

Output and exit status:

```text
/usr/bin/ffprobe
/usr/bin/ffmpeg
...................                                                      [100%]
19 passed in 1.67s
..........................................................               [100%]
58 passed in 1.75s
exit 0
```

`git diff --cached --check` and `compileall` produced no output and exited 0. An earlier cached-diff check reported `worker/src/audiobook_worker/audio.py:117: new blank line at EOF.`; that formatting defect was removed before this fresh final verification.

## Self-review

- Confirmed every brief rejection case is implemented and covered: missing, under 1 KiB, corrupt/nonzero probe, invalid duration, absent/incomplete stream metadata, and failed decode.
- Confirmed all invalid-audio paths use Task 1's `WorkerError(ErrorCode.AUDIO_INVALID, ..., retryable=True)`.
- Confirmed ffprobe and ffmpeg receive argument lists and there is no shell interpolation or alternate subprocess API.
- Confirmed actual generated WAV fixtures exercise the installed `/usr/bin/ffprobe` and `/usr/bin/ffmpeg`; mocks are limited to deterministic malformed-metadata and decode-failure boundaries.
- Confirmed manifest identity uses the request's existing `text_sha256` and `preset_snapshot_sha256` properties.
- Confirmed valid idempotent reuse preserves the new candidate, while successful first publication removes it.
- Confirmed unrelated, partial, malformed, and tampered artifacts are not overwritten.
- Confirmed publication staging occurs inside the output directory so final replacements remain on one filesystem.
- Confirmed the diff contains only the four Task 4 implementation/test files plus this report and does not touch Browser Runtime files.

## Commit

- `e0fd79d9464df059c2b648b9c6621be599b9766b` — `feat: validate and publish audio artifacts`

## Concerns

- A WAV and its JSON manifest are two directory entries and cannot be committed as one filesystem transaction. Each file is atomically replaced and ordinary exceptions roll back the WAV, but a hard process/host crash between the two replacements can leave a WAV without a manifest. A later publication refuses that partial state instead of overwriting user data, so manual reconciliation would be required.
- Task 2's Docker/live-runtime verification remains deferred to the Task 8 gate exactly as directed. This task verified the host-installed ffprobe/ffmpeg binaries and did not modify Browser Runtime files.

## Fix round 1

### Status

DONE

### Changes

- Added a shared finite 60-second timeout to the argument-array `subprocess.run` boundary used by both ffprobe and ffmpeg; shell execution remains disabled.
- Mapped `subprocess.TimeoutExpired` to retryable `WorkerError(ErrorCode.AUDIO_INVALID, ...)` with operation-specific context.
- Mapped `OSError`/`IOError` from the final streamed SHA-256 read to retryable `AUDIO_INVALID`. The catch is limited to `OSError`, so an existing typed `WorkerError` is not masked.
- Added focused public-behavior regressions for ffprobe/ffmpeg timeout mapping and post-decode hash-read failure mapping.

### TDD evidence

Timeout RED command:

```console
$ cd worker && uv run --with 'pytest>=8,<9' --no-project env PYTHONPATH=src pytest -q tests/test_audio.py::test_validate_maps_subprocess_timeout_to_retryable_audio_invalid
```

Result and exit status:

```text
FF                                                                       [100%]
2 failed in 0.04s
exit 1
```

Both cases failed because `subprocess.run` received no `timeout` keyword.

Timeout GREEN command:

```console
$ cd worker && uv run --with 'pytest>=8,<9' --no-project env PYTHONPATH=src pytest -q tests/test_audio.py::test_validate_maps_subprocess_timeout_to_retryable_audio_invalid
```

Result and exit status:

```text
..                                                                       [100%]
2 passed in 0.02s
exit 0
```

Hash/read RED command:

```console
$ cd worker && uv run --with 'pytest>=8,<9' --no-project env PYTHONPATH=src pytest -q tests/test_audio.py::test_validate_maps_hash_read_failure_to_retryable_audio_invalid
```

Result and exit status:

```text
F                                                                        [100%]
1 failed in 0.03s
exit 1
```

The test failed because the simulated final `Path.open` failure escaped as raw `OSError: hash read failed`.

Combined regression GREEN command:

```console
$ cd worker && uv run --with 'pytest>=8,<9' --no-project env PYTHONPATH=src pytest -q tests/test_audio.py::test_validate_maps_hash_read_failure_to_retryable_audio_invalid tests/test_audio.py::test_validate_maps_subprocess_timeout_to_retryable_audio_invalid
```

Result and exit status:

```text
...                                                                      [100%]
3 passed in 0.02s
exit 0
```

### Required test runs

Focused audio/artifact command:

```console
$ cd worker && uv run --with 'pytest>=8,<9' --no-project env PYTHONPATH=src pytest -q tests/test_audio.py tests/test_artifacts.py
```

Result and exit status:

```text
......................                                                   [100%]
22 passed in 1.67s
exit 0
```

The first full-suite command used only the test dependency:

```console
$ cd worker && uv run --with 'pytest>=8,<9' --no-project env PYTHONPATH=src pytest -q tests
```

Result and exit status:

```text
ERROR tests/test_config.py
ModuleNotFoundError: No module named 'pydantic_settings'
1 error in 0.16s
exit 2
```

This was an ephemeral test-environment dependency omission. The full suite was rerun with the worker's declared runtime dependencies:

```console
$ cd worker && uv run --with 'pytest>=8,<9' --with 'pydantic-settings>=2,<3' --with 'playwright>=1.49,<2' --no-project env PYTHONPATH=src pytest -q tests
```

Result and exit status:

```text
.............................................................            [100%]
61 passed in 2.09s
exit 0
```
