# Task 8 实施报告

## 完成内容

- 新增 `CosyVoice3Engine`、`IndexTts25Engine` 和 `F5TtsEngine` 三个统一 `TtsEngine` 候选适配器。
- 候选适配器使用延迟导入和本地模型目录；依赖、配置、辅助资源或权重缺失时明确失败，不自动下载权重。
- CosyVoice 适配器支持本地 `CosyVoice3` 的零样本克隆、SFT 说话人和流式输出合并，并对齐官方构造函数参数；IndexTTS 适配器对接 `IndexTTS2.infer`；F5-TTS 适配器对接本地 checkpoint 和本地 vocoder。
- `CandidateInferenceRequest` 保留 provider、model、version、language、voice、style_prompt、parameters_json、参考音频、参考文本、design_prompt 和目标路径，再进入模型专用映射。
- `ColabWorker` 的显式 `model_id` 采用严格路由：显存不足时失败，不静默切换；自动选择模式仍由 GPU 选择器选择可用的最高优先级 Qwen 模型。
- Worker 对引擎返回的路径和 `GenerationResult` 统一执行非符号链接、常规文件、非空、可读 WAV、完整帧、采样率、声道和 SHA256 校验。
- 新增 `audiobook_worker.benchmark`，提供 `offline-contract` 和 `real-candidate` 两种模式；输出模型、版本、样本、音频元数据、耗时、RTF、GPU、显存、启动耗时和失败字段。
- 新增候选依赖声明、固定中文评测样本、TTS 评测文档，以及四单元 Colab Notebook：安装依赖、读取 Secret、GPU/模型评测和启动 Worker。
- Notebook 默认使用 `Qwen/Qwen3-TTS-12Hz-1.7B-Base`；候选模型必须显式选择，本地权重和辅助文件必须预先准备。

## 验证结果

```text
$env:PYTHONPATH='worker/src'; python -m pytest -q worker/tests/test_candidate_engines.py worker/tests/test_colab_worker.py worker/tests/test_model_registry.py worker/tests/test_gpu_selector.py
47 passed

python scripts/build_colab_notebook.py
python scripts/build_colab_notebook.py --check
python -m json.tool notebooks/audiobook_factory_colab.ipynb
通过

python -m audiobook_worker.benchmark --mode offline-contract \
  --model-ids FunAudioLLM/Fun-CosyVoice3-0.5B-2512,IndexTeam/IndexTTS-2.5,SWivid/F5-TTS
12 rows（3 个候选 × 4 个样本）

python -m audiobook_worker.benchmark --mode real-candidate ...
按预期因当前 Windows 环境没有 CUDA GPU runtime 而 fail-closed
```

同时通过 Python 编译检查和 `git diff --check`。当前开发机没有候选模型包、CUDA GPU、Docker、FFmpeg/ffprobe，因此没有在本地执行真实模型推理；真实音质、RTF 和 GPU 资源比较需要在 Colab GPU 中准备本地模型资源后执行。当前 benchmark 结果落在本地 `artifacts/`，服务器实验记录接口仍属于后续扩展范围。
