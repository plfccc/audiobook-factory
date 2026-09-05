from dataclasses import dataclass
import re
from typing import Any, Callable


LocatorFactory = Callable[[Any], Any]


@dataclass(frozen=True)
class SelectorCandidates:
    text_input: tuple[LocatorFactory, ...]
    model_control: tuple[LocatorFactory, ...]
    voice_control: tuple[LocatorFactory, ...]
    style_prompt: tuple[LocatorFactory, ...]
    generate_action: tuple[LocatorFactory, ...]
    logged_out_indicators: tuple[LocatorFactory, ...]
    quota_indicators: tuple[LocatorFactory, ...]


AI_STUDIO_SELECTORS = SelectorCandidates(
    text_input=(
        lambda page: page.get_by_label("Text to speech input", exact=True),
        lambda page: page.get_by_role(
            "textbox", name=re.compile(r"text to speech", re.IGNORECASE)
        ),
        lambda page: page.locator('textarea[aria-label="Text to speech input"]'),
        lambda page: page.locator('[contenteditable="true"][aria-label*="Text"]'),
    ),
    model_control=(
        lambda page: page.get_by_label("Model", exact=True),
        lambda page: page.get_by_role("button", name=re.compile("model", re.IGNORECASE)),
        lambda page: page.locator('[aria-label="Model"]'),
    ),
    voice_control=(
        lambda page: page.get_by_label("Voice", exact=True),
        lambda page: page.get_by_role("button", name=re.compile("voice", re.IGNORECASE)),
        lambda page: page.locator('[aria-label="Voice"]'),
    ),
    style_prompt=(
        lambda page: page.get_by_label("Style instructions", exact=True),
        lambda page: page.get_by_role(
            "textbox", name=re.compile("style", re.IGNORECASE)
        ),
        lambda page: page.locator('textarea[aria-label="Style instructions"]'),
    ),
    generate_action=(
        lambda page: page.get_by_role(
            "button", name=re.compile(r"generate speech", re.IGNORECASE)
        ),
        lambda page: page.get_by_label("Generate speech", exact=True),
        lambda page: page.locator('button[aria-label="Generate speech"]'),
    ),
    logged_out_indicators=(
        lambda page: page.get_by_role(
            "button", name=re.compile(r"sign in", re.IGNORECASE)
        ),
        lambda page: page.get_by_text(re.compile(r"sign in|log in", re.IGNORECASE)),
        lambda page: page.locator('a[href*="accounts.google.com"]'),
    ),
    quota_indicators=(
        lambda page: page.get_by_text(
            re.compile(r"quota|rate limit|too many requests", re.IGNORECASE)
        ),
        lambda page: page.locator(
            '[aria-label*="quota" i], [data-testid*="quota" i]'
        ),
    ),
)
