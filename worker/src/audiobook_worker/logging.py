import json
import sys
from pathlib import Path
from typing import TextIO

from .errors import ErrorCode


def emit_event(
    event: str,
    *,
    request_id: str,
    phase: str,
    error_code: ErrorCode | None = None,
    output_path: Path | None = None,
    attempt: int | None = None,
    max_attempts: int | None = None,
    stream: TextIO | None = None,
) -> None:
    payload: dict[str, object] = {
        "event": event,
        "request_id": request_id,
        "phase": phase,
    }
    if error_code is not None:
        payload["error_code"] = error_code.value
    if output_path is not None:
        payload["output_path"] = str(output_path)
    if attempt is not None:
        payload["attempt"] = attempt
    if max_attempts is not None:
        payload["max_attempts"] = max_attempts
    print(
        json.dumps(payload, ensure_ascii=False, sort_keys=True),
        file=stream or sys.stderr,
        flush=True,
    )
