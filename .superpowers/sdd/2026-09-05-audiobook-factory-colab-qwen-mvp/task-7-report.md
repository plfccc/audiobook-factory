# Task 7 完成报告

状态：DONE_WITH_CONCERNS

实现提交：`59aa405f2bf295ae557018da202ef672a936b25e`

## 需求覆盖

- 增加 `MediaToolRunner`，使用固定参数列表和 `ProcessBuilder` 调用 `ffprobe`/`ffmpeg`，不经过 shell；支持超时、进程销毁、输出上限和启动/超时分类。
- `FfmpegMediaService.validate` 检查路径存在、regular file、符号链接、最小 1 KiB 大小；解析 `ffprobe` 的时长、codec、采样率和声道，执行完整解码检查，并流式计算 SHA-256。
- `mergeChapter` 按传入的 segment 顺序校验并合并 WAV，统一为 24 kHz/单声道；`atempo` 和 `loudnorm` 留有显式配置入口，默认不启用 `loudnorm`。
- 合并结果先写入同目录临时 MP3，重新校验后再原子提升到目标路径；写入 ID3v2.3/ID3v1、章节序号、标题和专辑，并按 `NNN - chapter-title.mp3` 命名。
- `LibraryPublishService` 对书名、章节名、segment 顺序、目标路径和符号链接做拒绝式校验，生成结果后才调用 scan；重复发布可安全覆盖同一目标，失败不会返回成功。
- `AudiobookshelfClient` 只从 `AUDIOBOOKSHELF_BASE_URL`、`AUDIOBOOKSHELF_LIBRARY_ID`、`AUDIOBOOKSHELF_API_KEY` 读取配置，调用受保护的 `POST /api/libraries/{libraryId}/scan`。API key 不写入日志、URL 或响应体；HTTP 失败、超时、中断和配置错误分类处理。

## 修改文件

- `control-center/pom.xml`
- `control-center/src/main/resources/application.yml`
- `control-center/src/main/java/com/audiobookfactory/control/audio/AudioValidationResult.java`
- `control-center/src/main/java/com/audiobookfactory/control/audio/MediaToolRunner.java`
- `control-center/src/main/java/com/audiobookfactory/control/audio/FfmpegMediaService.java`
- `control-center/src/main/java/com/audiobookfactory/control/library/AudiobookshelfClient.java`
- `control-center/src/main/java/com/audiobookfactory/control/library/LibraryPublishService.java`
- `control-center/src/test/java/com/audiobookfactory/control/audio/FfmpegMediaServiceTest.java`
- `control-center/src/test/java/com/audiobookfactory/control/library/AudiobookshelfClientTest.java`

测试使用 test-scope 的 OkHttp `MockWebServer` 和假 `MediaToolRunner`，不连接真实 FFmpeg、Audiobookshelf 或网络服务。

## TDD 与测试结果

1. RED：先添加媒体/API 测试，再执行：

   `rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -Dtest=FfmpegMediaServiceTest,AudiobookshelfClientTest test"`

   结果：因生产类尚未创建，`testCompile` 出现 33 个缺失符号错误，退出码 1。

2. GREEN：实现后目标测试通过；随后增加有效资产与 `null` 资产边界测试，先观察到排序阶段 NPE 的 RED，再将其修复为 `AUDIO_INVALID`。

   `rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -DskipTests compile"`

   结果：`BUILD SUCCESS`，退出码 0。

   `rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -Dtest=FfmpegMediaServiceTest,AudiobookshelfClientTest test"`

   结果：8 个测试通过，Failures 0、Errors 0，`BUILD SUCCESS`，退出码 0。

3. Java 全量回归：

   `rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml test"`

   结果：Tests run 96，Failures 0，Errors 4，Skipped 1。Task 7 和其余非 Docker 测试通过；4 个错误均为 Testcontainers 启动 PostgreSQL 时找不到 Docker 环境。

4. `git diff --check` 通过，退出码 0。

所有 Maven 命令均经 `mvn-auto.cmd` 执行，识别为 HST 路由，settings 为 `D:\config\maven-setting-hst.xml`，local repository 为 `D:\dependence\hst`。

## 外部依赖与环境限制

- 生产运行需要 `ffmpeg` 和 `ffprobe` 在可执行路径中；本次测试通过假 runner 覆盖命令边界，没有依赖真实媒体工具。
- 生产运行需要可访问并正确配置 Audiobookshelf；本次 API 测试使用 MockWebServer，没有访问真实服务或网络。
- `rtk docker version` 快速探测失败：PATH 中不存在 `docker`。没有等待或启动 Docker；因此 Testcontainers PostgreSQL 集成测试本次无法完成。
- `rtk` 输出提示未安装 hook，但命令本身正常执行，不影响 Maven 结果。

## 顾虑

- 本次未在真实 FFmpeg 上验证具体版本的 filter graph、编码器可用性和最终 ID3 解析结果，部署环境需要提供 `libmp3lame` 支持并做一次运行态验收。
- 本次未在真实 Audiobookshelf 上验证目录挂载权限和 scan 后索引行为；Docker/网络恢复后应补做端到端验证。
- 报告按要求在实现提交后追加，未改变上述实现提交的文件范围；工作树中原有的 `.superpowers/brainstorm/` 和 `docs/operations/` 未触碰。

## Critical 闭环补丁（2026-09-06）

实现提交：`3d5785fa94f866f82c1d152df573ab7cf9c3f54c`

### 本次闭环

- `JobService.recordResult` 只有在临时上传文件通过媒体层校验（ffprobe 元数据、完整 ffmpeg 解码、文件 size 和 SHA-256）后才会将 `generation_job` 标记为 `SUCCESS`；无效资产会把任务/章节留在可重试的 `WAITING`，不会发布或完成章节。
- `FfmpegMediaService.mergeChapter` 逐个校验 segment，在同目录临时 MP3 上合并并再次完整校验，随后才执行带 `ATOMIC_MOVE` 的原子提升；原子提升不受支持时保留临时文件并返回可分类错误。
- `JobService` 仅在整章 generation job 全部成功且 audio asset 数量齐全时触发发布；`LibraryPublishService` 通过可注入 `ChapterCompletionPort` 按 merge → scan → complete 顺序推进章节，scan 或发布失败不会调用完成端口。
- 保留 Task 5 HTTP controller 契约；新增构造器用于注入媒体服务和 completion port，旧测试/调用构造器保持兼容。

### 最终验证

- HST compile：`C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -DskipTests compile` → `BUILD SUCCESS`。
- Task 7/Task 6 目标测试：`FfmpegMediaServiceTest,MediaToolRunnerTest,JobServiceAudioPipelineTest,LibraryPublishServiceTest,AudiobookshelfClientTest,JobServiceSecurityTest,JobServiceScopeTest,JobClaimRepositorySqlTest,JobClaimRepositoryBindingTest` → 47 tests，Failures 0，Errors 0，Skipped 1。
- Task 5 纯 HTTP 契约：`BookHttpContractTest,WorkerHttpContractTest` → 11 tests，Failures 0，Errors 0。
- `git diff --check` 通过。

### 限制与顾虑

- 未等待或启动 Docker/真实 ffmpeg；Testcontainers 的 PostgreSQL 测试因本机无 Docker 无法执行，媒体测试使用 fake runner。真实 ffmpeg 版本、`libmp3lame`、filter graph、ID3 与 PostgreSQL 事务仍需环境恢复后验收。
- 已快速探测到包含 Docker 的测试会因环境不可用失败，未将其作为本次目标测试阻塞；未修改既有 `.superpowers/brainstorm/` 与 `docs/operations/` 未跟踪内容。

## Fix round 2（2026-09-06）

- `FfmpegMediaService` 的 Spring 构造器现在注入 `AppProperties.storageRoot` 与 `audiobookshelf.library-root`；媒体输入/输出根目录缺失时 fail-closed，不再放行未约束路径。
- `JobService` 与章节媒体提升均要求 `ATOMIC_MOVE`；不支持原子移动时直接返回错误，不降级为普通 `Files.move`。
- ffprobe 解析必须存在明确的 `codec_type=audio` 音频流、合法 codec/采样率/声道和正 duration；不会使用非音频流的 duration。
- 发布失败不会把已验证、已存储的成功 generation job 退回 `WAITING`；重复上传相同结果可通过 idempotency 重新触发章节发布，避免已有 segment 的 SHA/大小冲突卡死。
- 新增缺失 `codec_type`、非音频 duration 和发布重试幂等回归测试。

目标测试：

`rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -Dtest=FfmpegMediaServiceTest,MediaToolRunnerTest,LibraryPublishServiceTest,JobServiceAudioPipelineTest test"`

结果：25 tests，Failures 0，Errors 0，Skipped 1，`BUILD SUCCESS`。未等待 Docker 或真实 ffmpeg。
