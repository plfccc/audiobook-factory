from ._candidate_engine import LazyCandidateEngine
from .contracts import EngineCapabilities


class IndexTts25Engine(LazyCandidateEngine):
    engine_id = "indextts-2.5"
    model_id = "IndexTeam/IndexTTS-2.5"
    model_version = "2.5"
    dependency_name = "indextts"
    capabilities = EngineCapabilities(("zh-CN", "en-US"), False, True, True, True)


__all__ = ["IndexTts25Engine"]
