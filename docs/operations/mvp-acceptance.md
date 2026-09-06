# MVP 验收记录

本文用于记录“控制中心 + Colab Worker + Audiobookshelf”单章链路的真实验收证据。MVP 以章节为最小进度节点；不要求全书完成后才可试听或收听。

## 验收范围

- 控制中心能够导入 EPUB，并解析章节与生成任务。
- Colab Worker 能通过 Worker Token 注册、领取第一章任务并回传 WAV。
- 控制中心会校验 WAV 的大小、编码、时长、可解码性和 SHA-256。
- 第一章所有任务成功后，控制中心合并为 MP3，写入 Audiobookshelf Library 目录并触发扫描。
- 进度接口返回 `completedChapters / totalChapters / currentChapter`，章节接口返回可播放音频地址。
- 服务重启、配额暂停、Google 登录失效等异常不在本次单章闭环中伪造通过；需要在真实 Colab Worker 验收时记录。

## 测试入口

### 本地控制中心合同测试

需要本机 Docker Desktop/daemon 和 FFmpeg：

```text
C:\Users\lingpfeng.peng\.codex\bin\mvn-auto.cmd -f control-center/pom.xml -Dtest=MvpContractTest test
```

该测试使用 Testcontainers 启动 PostgreSQL，fake Worker 生成确定性的合法 WAV，并用本地 FFmpeg 完成合并；Audiobookshelf HTTP 客户端在该测试中替换为 fake publish port，因此它验证的是控制中心闭环而不是外部服务网络。

### 真实服务器单章烟囱测试

先确保控制中心已经启动、Colab Worker 已注册并保持轮询，再执行：

```bash
bash scripts/mvp-smoke.sh \
  --base-url https://book.example.com \
  --epub examples/mvp-sample.epub \
  --access-token '<APP_ACCESS_TOKEN>'
```

脚本不会输出访问令牌；它会导入示例书、提交第一章、轮询章节进度，并检查第一章是否已发布音频地址。真实生产验收应使用专用测试书，避免重复导入或占用正式书籍任务。

## 证据记录表

| 项目 | 记录 |
| --- | --- |
| 验收时间（Asia/Shanghai） | 待填写 |
| 控制中心版本 / Git commit | 待填写 |
| 服务器系统与 Docker 版本 | 待填写 |
| Colab GPU 型号 | 待填写 |
| CUDA / PyTorch 版本 | 待填写 |
| 实际 TTS 引擎 | `qwen3-tts` |
| 实际模型版本 | 待填写；记录 Hugging Face revision 或 Colab 输出 |
| 试听/生成章节 | `1` |
| 生成任务数 | 待填写 |
| 第一章音频时长 | 待填写 |
| 第一章音频 SHA-256 | 待填写 |
| MP3 文件路径 | 待填写 |
| Audiobookshelf Library ID | 待填写 |
| Audiobookshelf 扫描结果 | 待填写：成功 / 失败及日志摘要 |
| 手机端播放结果 | 待填写 |

## 通过标准

1. `MvpContractTest` 在 Docker 和 FFmpeg 可用的环境中通过。
2. 真实脚本最终输出 `MVP 单章烟囱测试通过。`。
3. 第一章状态为 `SUCCESS`，章节返回 `audioUrl`，并可通过该地址读取 `audio/mpeg`。
4. Audiobookshelf 扫描后能看到书籍和第一章；手机端能播放且时长大于零。
5. 任务失败时，后台显示可诊断的错误状态，且不会把 Worker Token、访问令牌或代理订阅地址写入任务快照和日志。

## 未通过时的排查顺序

1. 控制中心健康检查与日志。
2. Worker 是否注册成功、是否能领取任务、Worker Token 是否过期。
3. Colab GPU、模型下载和显存日志。
4. WAV 是否真实生成，`ffprobe` 是否能解码。
5. `/data/library` 权限和 Audiobookshelf 扫描日志。
6. 仅在确认服务端链路正常后，检查公网 HTTPS、反向代理和服务器防火墙。
