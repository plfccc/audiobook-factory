from ._candidate_engine import LazyCandidateEngine
from .contracts import EngineCapabilities


class CosyVoice3Engine(LazyCandidateEngine):
    engine_id = "cosyvoice3"
    model_id = "FunAudioLLM/Fun-CosyVoice3-0.5B-2512"
    model_version = "3.0"
    dependency_name = "cosyvoice"
    capabilities = EngineCapabilities(("zh-CN", "en-US"), False, True, True, False)


__all__ = ["CosyVoice3Engine"]
