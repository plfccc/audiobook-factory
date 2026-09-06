# TTS 候选评测命令

`audiobook_worker.benchmark` 提供两种模式：`offline-contract` 使用确定性的 WAV 测试适配器，验证统一调用、输出校验和指标采集；`real-candidate` 使用用户预先准备的本地权重调用真实候选适配器。两种模式都不会自动下载模型权重。

## 离线契约模式

```text
PYTHONPATH=worker/src python -m audiobook_worker.benchmark \
  --mode offline-contract \
  --model-ids FunAudioLLM/Fun-CosyVoice3-0.5B-2512,IndexTeam/IndexTTS-2.5,SWivid/F5-TTS
```

该模式不导入候选模型包、不访问 Google、不使用 GPU，也不能代表音质排名。

## 真实候选模式

```text
PYTHONPATH=worker/src python -m audiobook_worker.benchmark \
  --mode real-candidate \
  --model-ids FunAudioLLM/Fun-CosyVoice3-0.5B-2512,IndexTeam/IndexTTS-2.5,SWivid/F5-TTS \
  --model-root /content/audiobook-models \
  --reference-audio /content/reference.wav \
  --reference-text "参考音频对应的逐字稿"
```

真实模式要求 CUDA、参考音频和每个候选的完整本地模型目录。缺少任一项时直接报错；推理失败会在结果行的 `failure` 字段记录，不会伪造成功指标。

## 结果字段

每一行包含：

```text
engine, model, version,
sample, language, category,
duration, sampleRate, channels, size,
elapsed, rtf, gpu, vram, startup, failure, mode
```

同时保留 `engineId`、`modelId`、`modelVersion`、`sampleId`、`durationSeconds`、`sizeBytes` 和 `elapsedSeconds`，便于兼容现有记录。音质结论必须结合人工试听，并记录真实模式的 GPU、依赖、权重版本和参考音频摘要。
