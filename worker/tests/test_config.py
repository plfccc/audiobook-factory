import pytest
from pydantic import ValidationError

from audiobook_worker.config import WorkerSettings


def test_default_settings_use_internal_cdp_and_bounded_timeouts(monkeypatch):
    monkeypatch.delenv("CDP_URL", raising=False)
    settings = WorkerSettings()
    assert str(settings.cdp_url) == "http://browser:9222"
    assert settings.generation_timeout_seconds == 180
    assert settings.max_attempts == 3


def test_cdp_url_is_validated_and_exposed_without_trailing_slash(monkeypatch):
    monkeypatch.setenv("CDP_URL", "http://browser:9222/")
    assert WorkerSettings().cdp_url == "http://browser:9222"


def test_settings_load_all_environment_aliases(monkeypatch, tmp_path):
    values = {
        "CDP_URL": "http://custom:9222",
        "AI_STUDIO_URL": "https://example.test/tts",
        "OUTPUT_DIR": str(tmp_path / "output"),
        "DIAGNOSTICS_DIR": str(tmp_path / "diagnostics"),
        "GENERATION_TIMEOUT_SECONDS": "90",
        "DOWNLOAD_TIMEOUT_SECONDS": "45",
        "MAX_ATTEMPTS": "4",
    }
    for name, value in values.items():
        monkeypatch.setenv(name, value)
    settings = WorkerSettings()
    assert str(settings.cdp_url) == values["CDP_URL"]
    assert str(settings.ai_studio_url) == values["AI_STUDIO_URL"]
    assert settings.output_dir == tmp_path / "output"
    assert settings.diagnostics_dir == tmp_path / "diagnostics"
    assert (settings.generation_timeout_seconds, settings.download_timeout_seconds, settings.max_attempts) == (90, 45, 4)


@pytest.mark.parametrize("name", ["GENERATION_TIMEOUT_SECONDS", "DOWNLOAD_TIMEOUT_SECONDS", "MAX_ATTEMPTS"])
def test_settings_reject_non_positive_bounds(monkeypatch, name):
    monkeypatch.setenv(name, "0")
    with pytest.raises(ValidationError):
        WorkerSettings()
