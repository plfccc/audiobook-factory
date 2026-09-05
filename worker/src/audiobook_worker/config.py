from pathlib import Path

from pydantic import AnyHttpUrl, AliasChoices, Field, SecretStr, TypeAdapter, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class WorkerSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", case_sensitive=False)

    cdp_url: str = Field(default="http://browser:9222", validation_alias="CDP_URL")
    ai_studio_url: AnyHttpUrl = Field(default="https://aistudio.google.com/generate-speech", validation_alias="AI_STUDIO_URL")
    control_plane_url: AnyHttpUrl = Field(
        default="http://control-center:8080",
        validation_alias=AliasChoices("AUDIOBOOK_CONTROL_URL", "CONTROL_PLANE_URL"),
    )
    worker_token: SecretStr | None = Field(
        default=None,
        validation_alias=AliasChoices("AUDIOBOOK_WORKER_TOKEN", "WORKER_TOKEN"),
    )
    worker_name: str = Field(default="colab-worker", validation_alias="WORKER_NAME")
    cache_dir: Path = Field(
        default=Path("/content/audiobook-cache"),
        validation_alias=AliasChoices("WORKER_CACHE_DIR", "CACHE_DIR"),
    )
    output_dir: Path = Field(default=Path("/data/output"), validation_alias="OUTPUT_DIR")
    diagnostics_dir: Path = Field(default=Path("/data/diagnostics"), validation_alias="DIAGNOSTICS_DIR")
    generation_timeout_seconds: int = Field(default=180, gt=0, validation_alias="GENERATION_TIMEOUT_SECONDS")
    download_timeout_seconds: int = Field(default=60, gt=0, validation_alias="DOWNLOAD_TIMEOUT_SECONDS")
    control_connect_timeout_seconds: float = Field(
        default=10.0, gt=0, validation_alias="CONTROL_CONNECT_TIMEOUT_SECONDS"
    )
    control_read_timeout_seconds: float = Field(
        default=60.0, gt=0, validation_alias="CONTROL_READ_TIMEOUT_SECONDS"
    )
    control_write_timeout_seconds: float = Field(
        default=120.0, gt=0, validation_alias="CONTROL_WRITE_TIMEOUT_SECONDS"
    )
    heartbeat_interval_seconds: float = Field(
        default=30.0, gt=0, validation_alias="HEARTBEAT_INTERVAL_SECONDS"
    )
    claim_wait_seconds: float = Field(
        default=15.0,
        gt=0,
        validation_alias=AliasChoices(
            "CLAIM_WAIT_SECONDS", "WORKER_POLL_INTERVAL_SECONDS"
        ),
    )
    network_backoff_base_seconds: float = Field(
        default=15.0, gt=0, validation_alias="NETWORK_BACKOFF_BASE_SECONDS"
    )
    max_attempts: int = Field(default=3, gt=0, validation_alias="MAX_ATTEMPTS")

    @field_validator("cdp_url", mode="before")
    @classmethod
    def normalize_cdp_url(cls, value: str) -> str:
        validated = TypeAdapter(AnyHttpUrl).validate_python(value)
        return str(validated).rstrip("/")

    @field_validator("control_plane_url", mode="before")
    @classmethod
    def normalize_control_plane_url(cls, value: str) -> str:
        validated = TypeAdapter(AnyHttpUrl).validate_python(value)
        return str(validated).rstrip("/")

    @property
    def worker_token_value(self) -> str | None:
        """Return the secret only for explicitly passing it to the HTTP client."""

        return self.worker_token.get_secret_value() if self.worker_token else None
