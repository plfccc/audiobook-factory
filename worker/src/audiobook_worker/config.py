from pathlib import Path

from pydantic import AnyHttpUrl, Field, TypeAdapter, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class WorkerSettings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", case_sensitive=False)

    cdp_url: str = Field(default="http://browser:9222", validation_alias="CDP_URL")
    ai_studio_url: AnyHttpUrl = Field(default="https://aistudio.google.com/generate-speech", validation_alias="AI_STUDIO_URL")
    output_dir: Path = Field(default=Path("/data/output"), validation_alias="OUTPUT_DIR")
    diagnostics_dir: Path = Field(default=Path("/data/diagnostics"), validation_alias="DIAGNOSTICS_DIR")
    generation_timeout_seconds: int = Field(default=180, gt=0, validation_alias="GENERATION_TIMEOUT_SECONDS")
    download_timeout_seconds: int = Field(default=60, gt=0, validation_alias="DOWNLOAD_TIMEOUT_SECONDS")
    max_attempts: int = Field(default=3, gt=0, validation_alias="MAX_ATTEMPTS")

    @field_validator("cdp_url", mode="before")
    @classmethod
    def normalize_cdp_url(cls, value: str) -> str:
        validated = TypeAdapter(AnyHttpUrl).validate_python(value)
        return str(validated).rstrip("/")
