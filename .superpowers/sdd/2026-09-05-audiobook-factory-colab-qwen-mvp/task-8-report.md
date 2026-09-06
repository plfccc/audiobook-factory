# Task 8 实施报告

## 完成内容

- 新增 `CosyVoice3Engine`、`IndexTts25Engine`、`F5TtsEngine` 三个统一 `TtsEngine` 候选适配器。
- 候选依赖采用惰性导入；未安装模型包或未配置真实适配器时抛出清晰 `RuntimeError`，不会导入权重或主动下载模型。
- 新增候选引擎契约测试、四类固定 benchmark 样本和 TTS 评测文档。
- 新增固定四单元 Colab Notebook 及构建脚本：安装依赖、读取 Colab Secret、GPU 探测、启动一次 `run_forever`。
- Notebook 不输出 Worker Token，不自动登录 Google，不挂载 Drive；GPU 型号和显存只做运行时探测。
- 候选引擎增加 `CandidateAdapter` 可注入执行边界；默认 loader/adapter 只在实际使用时惰性导入候选包，未配置时清晰失败。
- `ColabWorker` 默认 factory 已注册三个候选，并校验 engine/model/version 身份；候选任务校验 provider、model、version、language 和 WAV 输出格式。
- 候选合成现在读取真实 WAV 帧数、采样率、声道并计算真实时长，拒绝空文件、非法 WAV 和无效 metadata。
- 新增 `audiobook_worker.benchmark` 离线逐模型入口与 `docs/operations/tts-benchmark.md`，输出每个模型/样本的时长、采样率、声道、大小和耗时指标；只使用注入的确定性 adapter，不下载权重。
- Notebook 第一单元从公开仓库安装当前 worker 和 `requirements-colab.txt`，第三单元暴露固定 benchmark 样本入口；仍只读取 Colab Secret，不包含凭据自动化。

## 验证结果

```text
$env:PYTHONPATH='worker/src'; python -m pytest worker/tests/test_candidate_engines.py worker/tests/test_colab_worker.py worker/tests/test_model_registry.py worker/tests/test_gpu_selector.py -q  37 passed
$env:PYTHONPATH='worker/src'; python -m audiobook_worker.benchmark --help  passed
python scripts/build_colab_notebook.py --check  passed
python -m json.tool notebooks/audiobook_factory_colab.ipynb  passed
offline benchmark  12 rows (3 candidates × 4 samples)
```

当前 Windows 开发环境没有安装 `nbformat`，也未安装候选模型包、ffmpeg 或 Docker；构建脚本使用标准 nbformat JSON fallback。目标测试未下载模型/权重，真实 Colab GPU 推理和各候选包 API 仍需在 Colab 手工安装模型包后验证；离线 benchmark 只验证统一契约与指标链路，不代表音质排名。
