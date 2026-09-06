from ._candidate_engine import LazyCandidateEngine
from .contracts import EngineCapabilities


class F5TtsEngine(LazyCandidateEngine):
    engine_id = "f5-tts"
    model_id = "SWivid/F5-TTS"
    model_version = "1.0"
    dependency_name = "f5_tts"
    capabilities = EngineCapabilities(("zh-CN", "en-US"), False, True, False, False)


__all__ = ["F5TtsEngine"]
