# TTS 候选评测

`audiobook_worker.benchmark` 提供离线逐模型契约评测入口。它使用确定性 WAV adapter 验证三个候选模型的统一调用、输出校验和指标采集，不导入模型包、不下载权重、不访问 Google。

```powershell
python -c "from pathlib import Path; from audiobook_worker.benchmark import run_from_json; run_from_json(Path('examples/tts-benchmark.json'), Path('artifacts/benchmark-audio'), Path('artifacts/benchmark-results.json'))"
```

结果包含 `engineId`、`modelId`、`sampleId`、`durationSeconds`、`sampleRate`、`channels`、`sizeBytes`、`elapsedSeconds` 和 `mode`。离线结果只验证契约，不代表真实音质排名。
