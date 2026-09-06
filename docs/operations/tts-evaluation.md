# TTS 候选模型评测

Task 8 提供四个可比较的引擎入口：Qwen3-TTS 1.7B、CosyVoice 3、IndexTTS 2.5 和 F5-TTS。服务器不安装模型包或权重；每次 Colab 运行时只加载一个候选模型，候选适配器在首次合成时才导入依赖。依赖不可用时会返回明确的 `RuntimeError`，不会偷偷下载权重。

## 固定样本

评测样本位于 [`examples/tts-benchmark.json`](../../examples/tts-benchmark.json)，覆盖普通叙述、对话、数字与专有名词、长句标点和情绪变化。每次评测保持相同参考音频、参考文本、Preset 和样本顺序，避免把不同参数误判为模型差异。

建议为每个样本记录：发音准确率、音色一致性、长段自然度、停顿、片段衔接、实时系数（RTF）、GPU 型号、显存、模型启动耗时、单段耗时和失败率。结果保存为服务器实验记录；评测不得修改已经开始生成的书籍版本或 Preset 快照。

## Colab 使用方式

1. 在 Colab 选择 GPU 运行时，打开 `notebooks/audiobook_factory_colab.ipynb`。
2. 在 Colab Secret 中配置 `AUDIOBOOK_CONTROL_URL` 和 `AUDIOBOOK_WORKER_TOKEN`。Notebook 只读取 Secret，不打印 Token，也不自动登录 Google。
3. 运行 GPU 探测单元，确认 `cuda_available` 为真后运行 Worker 单元。
4. 每个运行时只选择一个模型并启动一次 `run_forever()`；切换模型时重新启动干净的运行时，避免显存和缓存相互污染。

Notebook 仅负责安装运行时依赖、读取 Secret、探测 GPU 和启动 Worker。GPU 型号与“最好资源”由 Colab 实际分配，Worker 只在当前运行时内自动使用可用 GPU，不绕过平台配额或登录验证。

## 构建与校验

```text
rtk python scripts/build_colab_notebook.py
rtk python scripts/build_colab_notebook.py --check
rtk python -m json.tool notebooks/audiobook_factory_colab.ipynb
rtk pytest -q worker/tests/test_candidate_engines.py
```
