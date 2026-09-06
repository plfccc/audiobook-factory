from __future__ import annotations

import re
import unicodedata


_LANGUAGE_ALIASES = {
    "zh": "Chinese",
    "zh-cn": "Chinese",
    "zh_cn": "Chinese",
    "中文": "Chinese",
    "en": "English",
    "en-us": "English",
    "en_us": "English",
    "英语": "English",
    "ja": "Japanese",
    "ja-jp": "Japanese",
    "日语": "Japanese",
    "ko": "Korean",
    "ko-kr": "Korean",
    "韩语": "Korean",
}


def normalize_text(text: str) -> str:
    if not isinstance(text, str):
        raise TypeError("text must be a string")
    normalized = unicodedata.normalize("NFKC", text)
    normalized = re.sub(r"\s+", " ", normalized).strip()
    if not normalized:
        raise ValueError("text must not be blank")
    return normalized


def normalize_language(language: str) -> str:
    if not isinstance(language, str):
        raise TypeError("language must be a string")
    stripped = language.strip()
    if not stripped:
        raise ValueError("language must not be blank")
    return _LANGUAGE_ALIASES.get(stripped.lower(), stripped)

normalize_tts_text = normalize_text
