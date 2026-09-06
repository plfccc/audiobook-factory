# TTS 候选模型评测

Task 8 提供四个可比较的引擎入口：Qwen3-TTS 1.7B、CosyVoice 3、IndexTTS 2.5 和 F5-TTS。服务器不安装模型包或权重；每次 Colab 运行时只加载一个候选模型，候选适配器在首次合成时才导入对应依赖。依赖、模型目录或 GPU 不满足要求时会明确失败，不会偷偷下载权重或静默切换模型。

## 固定样本

评测样本位于 [`examples/tts-benchmark.json`](../../examples/tts-benchmark.json)，覆盖普通叙述、对话、数字与专有名词、长句标点和情绪变化。每次评测应保持相同的参考音频、参考文本、Preset 和样本顺序，避免把输入差异误判为模型差异。

音质结论由人工试听记录，建议同时记录发音准确率、音色一致性、长段自然度、停顿、片段衔接、实时系数（RTF）、GPU 型号、显存、模型启动耗时、单段耗时和失败率。评测结果只作为实验记录，不修改已经开始生成的书籍版本或 Preset。

## Colab 使用方式

1. 在 Colab 选择 GPU 运行 `notebooks/audiobook_factory_colab.ipynb`。
2. 在 Colab Secret 中配置 `AUDIOBOOK_CONTROL_URL` 和 `AUDIOBOOK_WORKER_TOKEN`。Notebook 只读取 Secret，不打印 Token，也不自动登录 Google。
3. 默认 `AUDIOBOOK_MODEL_ID` 为 `Qwen/Qwen3-TTS-12Hz-1.7B-Base`。只有显式设置完整 `model_id` 才会选择候选模型；显存、依赖或本地模型资源不足时直接失败。
4. `BENCHMARK_MODE=offline-contract` 只运行统一契约和输出校验，不加载模型权重，不能用于音质排名。`BENCHMARK_MODE=real-candidate` 才会加载本地候选模型并生成真实音频。
5. 每次运行只加载一个实际推理模型并启动一次 `run_forever()`。切换模型时重新启动干净的 Colab 运行时，避免显存和缓存互相污染。

候选依赖清单分别位于 `worker/requirements-cosyvoice.txt`、`worker/requirements-indextts.txt` 和 `worker/requirements-f5.txt`，只安装运行代码，不包含模型权重。权重和辅助文件必须预先放在 `AUDIOBOOK_LOCAL_MODEL_ROOT` 下：

```text
/content/audiobook-models/
├── cosyvoice3/                 # 必须包含 cosyvoice3.yaml
├── indextts-2.5/
│   ├── config.yaml
│   └── aux/
│       ├── w2v-bert-2.0/
│       ├── semantic_codec_model.safetensors
│       ├── campplus_cn_common.bin
│       └── bigvgan/
└── f5-tts/
    ├── *.safetensors           # 或 .ckpt/.bin/.pth
    └── vocos/
```

CosyVoice 零样本克隆和真实候选评测需要 `AUDIOBOOK_REFERENCE_AUDIO`，并建议同时设置 `AUDIOBOOK_REFERENCE_TEXT`。IndexTTS 和 F5-TTS 也要求参考音频；缺少本地资源时会 fail-closed，不自动访问 Hugging Face、ModelScope 或其它下载源。

## 本地构建与契约验证

```text
rtk python scripts/build_colab_notebook.py
rtk python scripts/build_colab_notebook.py --check
rtk python -m json.tool notebooks/audiobook_factory_colab.ipynb
rtk pytest -q worker/tests/test_candidate_engines.py
```

## 真实评测

```text
PYTHONPATH=worker/src python -m audiobook_worker.benchmark --mode real-candidate \
  --model-ids FunAudioLLM/Fun-CosyVoice3-0.5B-2512,IndexTeam/IndexTTS-2.5,SWivid/F5-TTS \
  --model-root /content/audiobook-models \
  --reference-audio /content/reference.wav \
  --reference-text "参考音频对应的逐字稿"
```

输出结果包含 `engine`、`model`、`version`、样本信息、`duration`、`sampleRate`、`channels`、`size`、`elapsed`、`rtf`、`gpu`、`vram`、`startup`、`failure` 和 `mode`，同时保留兼容性的 `*Id`/`*Seconds` 字段。`offline-contract` 的 WAV 是测试替身，只能证明任务、输出和指标链路可运行；只有 `real-candidate` 的本地权重结果才可用于人工音质比较。
