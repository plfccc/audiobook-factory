# 有声书工厂：Colab GPU + 可插拔 TTS 设计说明

- 日期：2026-09-05
- 状态：待用户审阅
- 范围：第一版单用户可用 MVP

## 1. 目标

将旧 Notebook 中已经验证过的 Qwen3-TTS 流程改造成一个可恢复的有声书生成系统：

1. 用户在服务器后台上传 EPUB。
2. 服务器解析书籍并以章节为业务进度节点。
3. Google Colab GPU Worker 负责实际模型推理。
4. 服务器负责任务调度、音频保存、校验、合并和发布。
5. 章节完成后可以逐章发布到 Audiobookshelf。
6. Colab 运行时断开后，重新连接可以从未完成的片段继续。

第一版的验收目标不是一次性跑完整本书，而是先稳定跑通一章的真实闭环，再扩大到全书。

## 2. 已确认的产品边界

### 2.1 第一版包含

- 单用户、个人使用。
- EPUB 导入。
- 章节解析、章节排序和章节级进度。
- 章节试听和指定章节/数量试跑。
- 一键开始全书任务。
- Google Colab GPU Worker。
- Qwen3-TTS 作为基线模型。
- CosyVoice 3、IndexTTS 2.5、F5-TTS 等候选模型的可插拔评测能力。
- 服务器保存源文件、WAV、MP3、封面和诊断资料。
- 内部片段级断点续跑。
- FFmpeg 音频校验、转码和章节合并。
- Audiobookshelf 逐章发布。
- 生成驾驶舱后台。

### 2.2 第一版不包含

- 用户表、管理员角色、注册、登录和多租户。
- 多个 Colab Worker 并行调度。
- Redis、RabbitMQ、Kafka、Temporal 等额外基础设施。
- 自动填写 Google 密码、恢复邮箱、2FA 或验证码。
- 账号轮换、代理轮换、配额绕过和反检测。
- 通过脆弱的页面自动化保证普通 Colab 永久无人值守。
- PDF 导入。
- 面向公众的书籍分享或商业发布能力。

虽然第一版不做账号管理，后台测试阶段仍应使用 SSH 隧道、内网绑定或单独访问 Token。不能把没有登录保护的管理接口直接暴露到公网。

## 3. 总体架构

服务器控制中心：

- Spring Boot 控制 API。
- PostgreSQL 保存书籍元数据、任务状态、Preset 快照和 Worker 心跳。
- 本地文件系统保存 EPUB、参考音频、WAV、MP3、封面和诊断资料。
- FFmpeg/ffprobe 完成音频校验和处理。
- Audiobookshelf 读取已发布的章节音频。

Colab Worker：

- 在 Google Colab GPU 运行时启动。
- 加载一个当前选定的 TTS 模型。
- 通过 HTTPS 长轮询或 WebSocket 连接服务器。
- 领取任务、发送心跳、生成 WAV、上传结果。
- 不访问 PostgreSQL，不直接写服务器文件系统。

数据流：

浏览器后台 → 控制 API → PostgreSQL/服务器文件系统

Colab Worker → 控制 API → 领取文本任务

Colab Worker → 控制 API → 上传 WAV 和生成元数据

控制 API → FFmpeg → 章节 MP3/M4B → Audiobookshelf

## 4. Colab 运行策略

### 4.1 第一版：半自动启动，任务全自动

普通 Colab 没有可靠的外部接口可以保证随时创建并抢到最强 GPU。第一版保留一个人工动作：

1. 用户打开保存好的 Colab Notebook。
2. 用户完成 Google 自己的登录和必要授权。
3. 用户连接 GPU 运行时并点击一次“启动 Worker”。
4. Worker 自动连接服务器并持续领取任务。

启动之后，用户不再手动粘贴章节文本、选择章节、点击每次生成或下载音频。

Colab 运行时被回收时：

- 服务器不丢失章节和片段进度。
- 当前租约过期后，任务回到等待状态。
- 后台显示 Worker 离线。
- 用户重新启动 Worker 后继续生成。

### 4.2 GPU 自动探测

Worker 启动后自动读取：

- GPU 型号。
- GPU 显存。
- CUDA 和 PyTorch 版本。
- 可用磁盘和系统内存。
- 各模型的最低资源要求。

模型选择逻辑：

1. 过滤当前资源无法稳定加载的模型。
2. 在可用模型中优先选择评测排名最高且资源足够的模型。
3. 没有 GPU 或模型探测失败时进入 WAITING_FOR_GPU，不用 CPU 默默跑长篇任务。
4. 后台可以显示自动选择结果，并保留人工覆盖入口。

“最好资源”指当前 Colab 已分配资源中的最佳可用组合；普通 Colab 实际能拿到的 GPU 型号、配额和生命周期仍由 Google 平台决定。

### 4.3 后续全自动路线

如需完全无人值守，后续切换到：

- Colab Enterprise 运行时模板和调度 API；或
- GCP Marketplace/Compute Engine 专用 GPU。

这时服务器可以创建、启动和停止指定资源，但会增加 Google Cloud 计费、IAM、配额和运维配置。

## 5. TTS 引擎抽象

控制中心不感知具体模型的推理代码。统一的引擎能力包括：

- health_check
- capability_probe
- prepare_voice
- synthesize
- validate_result
- classify_error

第一版候选：

### Qwen3-TTS

作为旧 Notebook 的基线，保留 VoiceDesign → 参考音频 → Base 克隆的音色流程。模型、语速、温度、Top-K、Top-P、参考音频和文本参数全部进入 Preset 快照。

### CosyVoice 3

作为首要竞争模型，比较中文长文本一致性、方言/发音控制、韵律、速度和片段衔接。

### IndexTTS 2.5

作为情绪、时长控制和发音控制对照模型。模型许可证和使用限制必须保存在模型登记信息中。

### F5-TTS

作为速度和零样本音色克隆对照组。预训练权重的许可证单独登记，不因为代码许可证宽松就默认允许其他使用范围。

### AI Studio Browser Provider

保留当前仓库已有的 AI Studio 浏览器 Provider 作为备用适配器，不作为 Qwen 主路径，也不让控制中心依赖 Google 页面结构。

## 6. 音色 Preset

Preset 是一次正式生成的不可变快照来源，包含：

- 引擎类型。
- 模型名称和版本。
- 参考音频 ID 与 SHA256。
- VoiceDesign 描述或克隆参数。
- 语言。
- 语速。
- 情绪/风格指令。
- 温度、Top-K、Top-P 等模型参数。
- 分段规则版本。
- 输出格式。

试听确认后，正式任务保存 Preset 快照。用户之后修改默认 Preset，不影响已经开始的书籍任务。

第一版默认沿用旧 Notebook 的音色配置，但先通过同一组文本对候选模型进行试听评测。

## 7. 书籍导入与章节进度

### 7.1 EPUB

服务器导入 EPUB 后：

1. 保存源文件和 SHA256。
2. 解析书名、作者、封面、目录和正文。
3. 清理导航、脚注、无关 HTML 和重复标题。
4. 建立稳定的章节顺序与标题。
5. 为每个章节生成文本哈希。

第一版不修改用户的原始 EPUB。

### 7.2 分段

章节是用户看到的最小业务进度节点，片段是内部技术节点。

分段规则：

- 优先按段落边界切分。
- 再按句号、问号、感叹号、分号等自然边界切分。
- 超长单句才在逗号、顿号或安全字符边界切分。
- 不在普通句子中间断开。
- 目标长度放入 TTS Preset，可按模型进行实验。
- 每个片段保存顺序号、字符数、文本 SHA256 和分段规则版本。

## 8. 任务和断点

### 8.1 章节状态

章节状态：

- WAITING
- RUNNING
- PAUSED
- RETRYING
- BLOCKED
- SUCCESS
- FAILED

章节只有在所有内部片段通过校验、合并成功后才计入完成数。

### 8.2 片段状态

片段状态：

- WAITING
- LEASED
- GENERATING
- UPLOADING
- VERIFYING
- SUCCESS
- RETRY_WAIT
- FAILED

片段任务至少保存：

- lease_owner
- lease_until
- heartbeat_at
- attempts
- next_retry_at
- last_error_code
- last_error_message
- started_at
- finished_at

Worker 领取任务时使用数据库事务和租约。第一版只有一个 Worker，但保留租约字段，避免进程崩溃后永久卡在 RUNNING。

### 8.3 幂等

生成任务的幂等键由以下信息组成：

- book_version
- chapter_index
- segment_index
- text_sha256
- preset_snapshot_sha256
- model_version

如果片段已有校验通过的音频，则不重复生成。

## 9. 音频资产

服务器目录按书籍版本隔离：

源文件、章节文本、片段 WAV、章节 MP3、封面和诊断资料分别存放，临时上传文件先写入临时目录，校验通过后再原子移动到正式目录。

WAV 完成后必须执行：

- 文件存在和大小检查。
- ffprobe 可读性检查。
- duration 检查。
- codec 和采样率检查。
- 完整解码检查。
- SHA256 计算。

章节合并：

- 按片段顺序拼接。
- 统一采样率、声道和输出格式。
- 默认不强制 loudnorm，先保留模型原始人声。
- 章节成功后生成 MP3。
- 全书完成后可选生成 M4B。

## 10. 后台页面

第一版不做登录和用户管理，但提供以下页面：

### 生成驾驶舱

- Worker 在线状态。
- 实际 GPU 和显存。
- 当前模型与 Preset。
- 当前书籍的已完成章节数/总章节数。
- 当前章节。
- 开始、暂停、继续。
- 上传 EPUB。
- 异常任务数量。
- 磁盘空间。

### 书籍详情

- 书籍元数据和封面。
- 章节列表。
- 章节级试听、生成、暂停、重试。
- 点击章节后才显示内部片段。

### 模型试听

- 选择参考音频和文本。
- 选择候选模型。
- 并排播放结果。
- 记录模型版本、耗时、GPU 和参数。
- 选择默认模型。

### 异常中心

- 章节和片段错误。
- 重试次数。
- 最后错误。
- Worker 状态。
- 日志、截图和脱敏诊断信息。

## 11. 第一版验收测试

### 本地单元测试

- EPUB 解析。
- 章节标题和顺序。
- 中文标点分段。
- 超长句切分。
- 文本哈希和幂等键。
- 任务租约过期恢复。
- WAV 校验和 SHA256。
- Preset 快照。

### Worker 合同测试

- Worker 注册。
- GPU 探测。
- 模型能力探测。
- 领取任务。
- 心跳。
- 上传结果。
- 失败重试。
- Colab 断线恢复。

### 真实烟囱测试

使用一段小 EPUB 或一章真实文本：

1. 后台上传 EPUB。
2. 解析出章节。
3. 选择一章试听。
4. Colab 启动 Worker 并自动探测 GPU。
5. 生成 WAV 并上传服务器。
6. 服务器校验、转 MP3、合并章节。
7. Audiobookshelf 扫描并播放。
8. 中断 Worker，再连接后验证从未完成片段继续。

在这条烟囱测试通过之前，不开始完整长篇生成。

## 12. 与当前仓库的关系

当前仓库已经有浏览器运行时、代理部署文件、AI Studio Provider、音频校验和 Worker 测试基础，但目前主路径仍是 AI Studio 浏览器自动化。

需要新增或重构：

- Colab Worker 通信协议。
- Qwen3-TTS 推理模块。
- 统一 TtsEngine 接口。
- EPUB Parser 和 Segmenter。
- 控制 API。
- PostgreSQL 数据模型和租约调度。
- 章节级后台。
- Audiobookshelf 发布适配器。

已有 AI Studio 代码保留为备用 Provider，不与 Qwen/Colab 的核心任务模型耦合。

## 13. 风险与应对

| 风险 | 应对 |
|---|---|
| 普通 Colab 运行时被回收 | 服务器保存状态，租约过期后恢复；第一版接受重新启动 Worker |
| GPU 型号不稳定 | 连接后自动探测，按资源选择模型 |
| 模型启动慢或依赖冲突 | 固定环境版本，首次单独做模型加载烟囱测试 |
| 长文本片段衔接不自然 | Preset 化分段规则，候选模型盲测 |
| 生成音频损坏 | ffprobe、解码、时长和哈希校验 |
| 后台无账号保护 | 第一版只通过 SSH/内网/访问 Token 使用，不直接裸露公网 |
| 参考音频或 Google 会话泄露 | 不保存 Google 密码；参考音频和 Worker Token 使用最小权限和严格文件权限 |

## 14. 实施顺序

1. 从旧 Notebook 抽取 Qwen3-TTS 核心推理。
2. 做 Colab Worker 一键启动和 GPU 自动探测。
3. 做服务器最小控制 API、文件上传和 Worker 协议。
4. 完成 EPUB 解析、章节级任务和内部片段断点。
5. 完成 WAV 校验、MP3 合并和 Audiobookshelf 发布。
6. 完成生成驾驶舱和异常中心。
7. 用统一样本评测 CosyVoice 3、IndexTTS 2.5、F5-TTS。
8. 选择默认模型后再跑整本书。

## 15. 参考

- [Qwen3-TTS 官方仓库](https://github.com/QwenLM/Qwen3-TTS)
- [CosyVoice 官方仓库](https://github.com/FunAudioLLM/CosyVoice)
- [IndexTTS 官方仓库与许可证](https://github.com/index-tts/index-tts)
- [F5-TTS 官方仓库](https://github.com/SWivid/F5-TTS)
- [Google Colab 官方 FAQ](https://research.google.com/colaboratory/faq.html)
- [Colab Enterprise Notebook 调度](https://cloud.google.com/colab/docs/schedule-notebook-run)
