from enum import StrEnum


class ErrorCode(StrEnum):
    AUTH_REQUIRED = "AUTH_REQUIRED"
    HUMAN_REQUIRED = "HUMAN_REQUIRED"
    QUOTA_PAUSED = "QUOTA_PAUSED"
    PAGE_NOT_READY = "PAGE_NOT_READY"
    GENERATION_TIMEOUT = "GENERATION_TIMEOUT"
    DOWNLOAD_TIMEOUT = "DOWNLOAD_TIMEOUT"
    AUDIO_INVALID = "AUDIO_INVALID"
    PERMANENT_FAILED = "PERMANENT_FAILED"


class WorkerError(RuntimeError):
    def __init__(self, code: ErrorCode, message: str, *, retryable: bool):
        super().__init__(message)
        self.code = code
        self.retryable = retryable
