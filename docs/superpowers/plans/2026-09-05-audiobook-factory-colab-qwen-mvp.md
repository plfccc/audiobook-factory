# 有声书工厂 Colab/Qwen MVP 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 交付一个单用户可用的有声书垂直闭环：上传 EPUB、按章节生成任务、由 Google Colab GPU Worker 使用 TTS 模型推理、服务器校验并合并音频，最后发布到 Audiobookshelf。

**Architecture:** 服务器运行 Spring Boot 控制中心、PostgreSQL、FFmpeg 和 Audiobookshelf；Google Colab 运行 Python Worker，通过 HTTPS 领取章节片段并回传 WAV。章节是用户可见的进度节点，片段是内部断点节点；TTS 引擎采用统一接口，Qwen3-TTS 先作为基线，其他模型在同一 Colab Worker 中逐个评测。

**Tech Stack:** Spring Boot 3.5.16、Java 17、Maven、PostgreSQL 16、Flyway、Python 3.11、qwen-tts、PyTorch、httpx、FFmpeg/ffprobe、Vue 3、Vite、Docker Compose、Audiobookshelf API。

**Spec:** docs/superpowers/specs/2026-09-05-audiobook-factory-colab-qwen-design.md

## Global Constraints

- 第一版是单用户、个人使用，不建立用户表、管理员角色、注册或登录体系。
- 第一版正式输入只支持 EPUB，原始文件必须保留。
- 模型推理必须在 Google Colab GPU Worker 中执行，服务器不运行 Qwen、CosyVoice、IndexTTS 或 F5-TTS。
- 服务器是任务状态、章节进度和音频资产的唯一来源。
- 章节是用户可见的最小进度节点，片段只负责内部生成、校验和断点恢复。
- 第一版保留一次人工启动 Colab Worker 的动作，启动后任务处理自动运行。
- 不自动填写 Google 密码、恢复邮箱、2FA、验证码，不做账号轮换、代理轮换、配额绕过或反检测。
- 普通 Colab 的 GPU 型号由平台分配；Worker 连接后自动探测 GPU、显存、CUDA 和可用模型。
- 没有足够 GPU 资源时，Worker 进入等待状态，不用 CPU 默默生成长篇任务。
- 不引入 Redis、RabbitMQ、Kafka、Temporal 或多 Worker 并行调度。
- 每个片段必须经过文件大小、ffprobe、时长、完整解码和 SHA256 校验。
- 第一版后台不直接裸露公网；使用 SSH 隧道、内网绑定或单独访问 Token。
- 不把账号、密码、订阅地址、Worker Token 或模型私有凭据提交到 Git。
- 现有 AI Studio 浏览器 Provider 保留为备用适配器，不删除已有诊断和安全约束。
- 所有 Maven 编译、测试、验证和打包命令使用 C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd，不执行裸 mvn。

---

## 范围检查

本计划包含 Python Worker、Spring Boot、数据库、前端和部署，但这些模块共同组成一个用户验收闭环。每个任务都以一个独立可测试的边界结束。

执行前必须创建隔离 worktree。本计划只描述实现，不在当前工作区直接修改业务代码。执行时先使用 superpowers:using-git-worktrees，再使用 superpowers:subagent-driven-development 或 superpowers:executing-plans。

## 文件地图

| 路径 | 职责 |
|---|---|
| worker/src/audiobook_worker/contracts.py | Worker、Preset、音色、运行时和生成任务契约 |
| worker/src/audiobook_worker/tts_engine.py | 可插拔 TTS 引擎协议 |
| worker/src/audiobook_worker/runtime_probe.py | GPU、CUDA、显存和运行时探测 |
| worker/src/audiobook_worker/model_registry.py | 模型能力、资源要求和优先级 |
| worker/src/audiobook_worker/qwen_engine.py | Qwen3-TTS 推理适配器 |
| worker/src/audiobook_worker/voice_profiles.py | 参考音频、VoiceDesign 和音色缓存 |
| worker/src/audiobook_worker/server_client.py | Colab 到控制中心的 HTTPS 客户端 |
| worker/src/audiobook_worker/colab_worker.py | 注册、领取、心跳、生成和回传循环 |
| worker/src/audiobook_worker/gpu_selector.py | 根据实际 GPU 选择可用模型 |
| worker/tests | Python 契约、引擎、传输和 Worker 测试 |
| notebooks/audiobook_factory_colab.ipynb | 简化后的 Colab 启动 Notebook |
| control-center/pom.xml | Spring Boot 控制中心构建定义 |
| control-center/src/main/java/com/audiobookfactory/control | 控制中心 Java 源码 |
| control-center/src/main/resources/db/migration/V1__initial_schema.sql | PostgreSQL 初始结构 |
| control-center/src/test/java/com/audiobookfactory/control | 控制中心单元和集成测试 |
| web | Vue 3/Vite 生成驾驶舱 |
| infra/mvp/docker-compose.yml | PostgreSQL、控制中心和 Audiobookshelf 部署 |
| scripts/mvp-smoke.sh | 单章真实烟囱测试 |
| docs/operations/mvp-colab-deployment.md | 部署、Colab 启动、访问保护和恢复操作说明 |

### Task 1: 固化 TTS、运行时和任务契约

**Files:**

- Modify: worker/pyproject.toml
- Modify: worker/src/audiobook_worker/contracts.py
- Create: worker/src/audiobook_worker/tts_engine.py
- Create: worker/src/audiobook_worker/runtime_probe.py
- Create: worker/src/audiobook_worker/model_registry.py
- Create: worker/tests/test_tts_engine.py
- Create: worker/tests/test_runtime_probe.py
- Create: worker/tests/test_model_registry.py

**Interfaces:**

- RuntimeProbe：cuda_available、gpu_name、gpu_memory_bytes、cuda_version、torch_version、python_version。
- EngineCapabilities：languages、voice_design、voice_clone、emotion_control、duration_control。
- VoiceProfile：profile_id、name、reference_audio_path、reference_text、design_prompt。
- PreparedVoice：profile_id、cache_key、reference_audio_path、reference_text、design_prompt、clone_prompt；clone_prompt 只在 Colab Worker 内存中存在，不写入数据库。
- TtsJob：job_id、book_id、book_version_id、chapter_id、chapter_index、segment_index、text、preset、voice_profile。
- TtsEngine：engine_id、capabilities、probe、prepare_voice(profile) 和 synthesize(job, destination)。
- ModelProfile：engine_id、model_id、model_version、minimum_vram_bytes、priority、capabilities。
- ModelRegistry.select(probe, requested_engine) 返回 ModelProfile 或 None。

- [ ] **Step 1: 写失败的契约测试**

~~~python
from audiobook_worker.model_registry import ModelRegistry
from audiobook_worker.runtime_probe import RuntimeProbe


def test_selects_highest_priority_compatible_model():
    probe = RuntimeProbe(
        cuda_available=True,
        gpu_name="Test GPU",
        gpu_memory_bytes=16 * 1024**3,
        cuda_version="12.4",
        torch_version="2.7.0",
        python_version="3.11",
    )

    selected = ModelRegistry.default().select(probe)

    assert selected.model_id == "Qwen/Qwen3-TTS-12Hz-1.7B-Base"


def test_returns_none_without_cuda():
    probe = RuntimeProbe(False, None, 0, None, "2.7.0", "3.11")

    assert ModelRegistry.default().select(probe) is None
~~~

- [ ] **Step 2: 运行测试确认失败**

运行：

~~~text
rtk pytest -q worker/tests/test_tts_engine.py worker/tests/test_runtime_probe.py worker/tests/test_model_registry.py
~~~

预期：失败，因为新的契约、运行时探测和模型注册表尚不存在。

- [ ] **Step 3: 实现最小契约并保持现有 Provider 兼容**

在 contracts.py 的 TtsPreset 中新增带默认值的字段，保留当前 AI Studio 测试的构造方式：

~~~python
model_version: str = "unspecified"
voice_profile_id: str | None = None
parameters_json: str = "{}"
segment_target_chars: int = 220
segment_max_chars: int = 320
~~~

在 runtime_probe.py 中使用 torch.cuda.is_available、torch.cuda.get_device_name(0) 和 torch.cuda.get_device_properties(0).total_memory；没有 CUDA 时返回 cuda_available=False。

在 model_registry.py 登记 Qwen3-TTS 1.7B Base、Qwen3-TTS 0.6B Base、CosyVoice 3、IndexTTS 2.5 和 F5-TTS。每项保存模型 ID、版本、最小显存、能力和许可证 URL。Qwen 基线在真实评测前保持最高默认优先级。

- [ ] **Step 4: 运行新增测试和现有 Worker 回归测试**

运行：

~~~text
rtk pytest -q worker/tests/test_tts_engine.py worker/tests/test_runtime_probe.py worker/tests/test_model_registry.py
rtk pytest -q worker/tests/test_ai_studio_provider.py worker/tests/test_contracts.py
~~~

预期：新增测试和现有相关测试全部通过。

- [ ] **Step 5: 提交**

~~~text
rtk git add worker/pyproject.toml worker/src/audiobook_worker/contracts.py worker/src/audiobook_worker/tts_engine.py worker/src/audiobook_worker/runtime_probe.py worker/src/audiobook_worker/model_registry.py worker/tests/test_tts_engine.py worker/tests/test_runtime_probe.py worker/tests/test_model_registry.py
rtk git commit -m "feat: 固化可插拔 TTS 契约"
~~~

### Task 2: 抽取 Qwen3-TTS 推理和音色缓存

**Files:**

- Create: worker/src/audiobook_worker/qwen_engine.py
- Create: worker/src/audiobook_worker/voice_profiles.py
- Create: worker/src/audiobook_worker/text_normalization.py
- Create: worker/requirements-colab.txt
- Create: worker/tests/test_qwen_engine.py
- Create: worker/tests/test_voice_profiles.py

**Interfaces:**

- QwenModelLoader.load(model_id, device) 返回模型对象。
- QwenModelAdapter.create_voice_clone_prompt(reference_audio, reference_text) 返回可缓存的克隆 Prompt。
- QwenModelAdapter.generate_voice_clone(model, prompt, text, language, output_path, parameters) 写入 WAV。
- Qwen3TtsEngine.prepare_voice(profile) 返回 PreparedVoice。
- Qwen3TtsEngine.synthesize(job, destination) 返回 GenerationResult。
- VoiceProfileStore.materialize(profile, target_dir) 返回本地参考音频路径。

- [ ] **Step 1: 写失败的 Qwen 引擎测试**

~~~python
def test_qwen_engine_reuses_voice_prompt_and_writes_wav(tmp_path, fake_qwen):
    engine = Qwen3TtsEngine(model_loader=fake_qwen.loader, cache_dir=tmp_path)
    profile = VoiceProfile(
        "voice-1",
        "旁白",
        tmp_path / "reference.wav",
        "测试参考文本",
        None,
    )
    job = make_tts_job(text="这是一个测试片段。", voice_profile=profile)

    result = run_async(engine.synthesize(job, tmp_path / "out.wav"))

    assert result.output_path.exists()
    assert fake_qwen.clone_prompt_calls == 1
    assert fake_qwen.generate_calls == 1
~~~

- [ ] **Step 2: 运行测试确认失败**

运行：

~~~text
rtk pytest -q worker/tests/test_qwen_engine.py worker/tests/test_voice_profiles.py
~~~

预期：失败，因为 Qwen 引擎、音色缓存和测试替身尚不存在。

- [ ] **Step 3: 从旧 Notebook 提取纯 Python 核心**

以 D:/download/蛊真人_Qwen3TTS_V2.1_工具版.ipynb 为迁移来源，提取 qwen-tts 模型加载、VoiceDesign 参考音频生成、Base 模型参考音频克隆、WAV 输出以及语速、温度、Top-K、Top-P 参数传递。

移除 google.colab.drive.mount、Google Drive 主存储、ipywidgets 章节控件和 Notebook 全书进度。同步推理调用使用 asyncio.to_thread，避免阻塞心跳。

requirements-colab.txt 固定 qwen-tts==0.1.1、soundfile、numpy、httpx 和与 Colab CUDA 兼容的 PyTorch。服务器 Worker 镜像不安装模型权重。

- [ ] **Step 4: 运行引擎测试和现有测试**

运行：

~~~text
rtk pytest -q worker/tests/test_qwen_engine.py worker/tests/test_voice_profiles.py
rtk pytest -q worker/tests/test_ai_studio_provider.py worker/tests/test_pipeline.py
~~~

预期：测试替身路径通过；未安装 qwen-tts 的服务器环境可以导入契约而不触发重量级模型依赖。

- [ ] **Step 5: 提交**

~~~text
rtk git add worker/src/audiobook_worker/qwen_engine.py worker/src/audiobook_worker/voice_profiles.py worker/src/audiobook_worker/text_normalization.py worker/requirements-colab.txt worker/tests/test_qwen_engine.py worker/tests/test_voice_profiles.py
rtk git commit -m "feat: 抽取 Qwen3-TTS 推理引擎"
~~~

### Task 3: 建立控制中心 Spring Boot 和数据库

**Files:**

- Create: control-center/pom.xml
- Create: control-center/src/main/java/com/audiobookfactory/control/AudiobookFactoryApplication.java
- Create: control-center/src/main/java/com/audiobookfactory/control/config/AppProperties.java
- Create: control-center/src/main/java/com/audiobookfactory/control/config/AccessTokenFilter.java
- Create: control-center/src/main/resources/application.yml
- Create: control-center/src/main/resources/db/migration/V1__initial_schema.sql
- Create: control-center/src/test/java/com/audiobookfactory/control/ContextLoadTest.java
- Create: control-center/src/test/resources/application-test.yml

**Interfaces:**

- GET /actuator/health 返回控制中心健康状态。
- AppProperties.storageRoot() 返回 Path。
- AppProperties.workerEnrollToken() 返回 String。
- AppProperties.accessToken() 返回 String 或 null。
- 配置 APP_ACCESS_TOKEN 时，AccessTokenFilter 要求 Authorization: Bearer Token；未配置时只允许通过受保护部署入口访问。

- [ ] **Step 1: 写失败的 Spring Boot 上下文测试**

~~~java
@SpringBootTest
class ContextLoadTest {
    @Autowired
    ApplicationContext context;

    @Test
    void applicationContextStarts() {
        assertThat(context).isNotNull();
    }
}
~~~

- [ ] **Step 2: 运行测试确认失败**

运行：

~~~text
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml test"
~~~

预期：失败，因为控制中心工程尚不存在。

- [ ] **Step 3: 创建 Spring Boot 工程和 Flyway V1**

使用 Spring Boot 3.5.16 和 Java 17。依赖包含 Web、Validation、JDBC、Actuator、Flyway、PostgreSQL、Spring Boot Test 和 Testcontainers PostgreSQL。

V1 创建以下表：

- book：标题、作者、封面路径、状态和时间。
- book_version：源文件路径、源文件 SHA256、解析器版本和分段规则版本。
- chapter：章节序号、标题、正文路径、正文 SHA256、状态和最终音频路径。
- voice_profile：音色名称、参考音频路径、参考音频 SHA256、参考文本和 VoiceDesign 描述。
- tts_preset：引擎、模型、模型版本、风格指令、语速、模型参数和分段长度。
- generation_job：章节 ID、片段序号、片段文本、文本 SHA256、Preset 快照、状态、租约和错误字段。
- audio_asset：Job ID、文件路径、格式、时长、采样率、声道、大小和 SHA256。
- worker_registration：Worker ID、名称、能力 JSON、Token 哈希、状态和心跳时间。

不创建 user、admin 或 role 表。application.yml 从 DB_URL、DB_USERNAME、DB_PASSWORD、STORAGE_ROOT、WORKER_ENROLL_TOKEN 和 APP_ACCESS_TOKEN 读取配置，默认端口为 8080。

- [ ] **Step 4: 运行上下文和迁移测试**

运行：

~~~text
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml test"
~~~

预期：上下文测试和 Flyway 迁移测试通过。

- [ ] **Step 5: 提交**

~~~text
rtk git add control-center
rtk git commit -m "feat: 建立有声书控制中心"
~~~

### Task 4: 实现 EPUB 导入、章节解析和章节内分段

**Files:**

- Create: control-center/src/main/java/com/audiobookfactory/control/book/EpubImportService.java
- Create: control-center/src/main/java/com/audiobookfactory/control/book/EpubChapterExtractor.java
- Create: control-center/src/main/java/com/audiobookfactory/control/book/TextSegmenter.java
- Create: control-center/src/main/java/com/audiobookfactory/control/book/SegmentationPolicy.java
- Create: control-center/src/main/java/com/audiobookfactory/control/book/BookImportResult.java
- Create: control-center/src/test/java/com/audiobookfactory/control/book/EpubImportServiceTest.java
- Create: control-center/src/test/java/com/audiobookfactory/control/book/TextSegmenterTest.java
- Create: control-center/src/test/resources/fixtures/epub
- Modify: control-center/pom.xml

**Interfaces:**

- EpubImportService.importBook(InputStream source, String originalFilename) 返回 BookImportResult。
- EpubChapterExtractor.extract(Path epubPath) 返回 List<ChapterDraft>。
- TextSegmenter.segment(String chapterText, SegmentationPolicy policy) 返回 List<SegmentDraft>。
- SegmentationPolicy 包含 minChars、targetChars、maxChars 和 rulesVersion。
- BookImportResult 包含 bookId、bookVersionId、chapterCount 和 sourceSha256。

- [ ] **Step 1: 写失败的解析和分段测试**

~~~java
@Test
void importsSpineOrderAndKeepsChapterTitles() {
    BookImportResult result =
        service.importBook(fixture.openStream(), "sample.epub");

    assertThat(result.chapterCount()).isEqualTo(2);
    assertThat(repository.findChapter(0).title()).isEqualTo("第一章");
    assertThat(repository.findChapter(1).title()).isEqualTo("第二章");
}

@Test
void neverSplitsInsideASentence() {
    List<SegmentDraft> segments = segmenter.segment(
        "第一句内容。第二句内容！第三句内容？",
        new SegmentationPolicy(2, 8, 12, "v1")
    );

    assertThat(segments).isNotEmpty();
    assertThat(segments).extracting(SegmentDraft::text)
        .containsExactly("第一句内容。", "第二句内容！", "第三句内容？");
}
~~~

- [ ] **Step 2: 运行测试确认失败**

运行：

~~~text
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -Dtest=EpubImportServiceTest,TextSegmenterTest test"
~~~

预期：失败，因为导入服务、分段器和 EPUB fixture 尚不存在。

- [ ] **Step 3: 实现 EPUB 解析和分段器**

使用 JDK ZipFile 读取 EPUB，解析 container.xml 和 OPF spine，使用 Jsoup 清理 XHTML。按 spine 顺序提取正文，移除导航、脚注链接和重复标题，保存每章纯文本与 SHA256。

默认分段规则沿用旧 Notebook：

~~~java
new SegmentationPolicy(90, 220, 320, "v1-notebook")
~~~

算法依次尝试段落边界、句末标点、逗号和顿号；只有单句超过最大长度时才在安全字符边界切分。章节末尾短片段与前一个片段合并，不跨章节合并。

重复上传同一源文件 SHA256 时返回已有 book_version，不重复创建章节和 Job。

- [ ] **Step 4: 运行解析、分段和 Java 全量测试**

运行：

~~~text
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -Dtest=EpubImportServiceTest,TextSegmenterTest test"
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml test"
~~~

预期：解析、分段、迁移和上下文测试通过。

- [ ] **Step 5: 提交**

~~~text
rtk git add control-center/pom.xml control-center/src/main/java/com/audiobookfactory/control/book control-center/src/test/java/com/audiobookfactory/control/book control-center/src/test/resources/fixtures/epub
rtk git commit -m "feat: 支持 EPUB 章节解析和分段"
~~~

### Task 5: 实现 Worker 注册、GPU 选择和 HTTPS 客户端

**Files:**

- Modify: worker/pyproject.toml
- Modify: worker/src/audiobook_worker/config.py
- Create: worker/src/audiobook_worker/server_client.py
- Create: worker/src/audiobook_worker/colab_worker.py
- Create: worker/src/audiobook_worker/gpu_selector.py
- Create: worker/tests/test_server_client.py
- Create: worker/tests/test_colab_worker.py
- Create: worker/tests/test_gpu_selector.py

**Interfaces:**

- ControlPlaneClient.register(worker_name, runtime, capabilities) 返回 WorkerRegistration。
- ControlPlaneClient.claim_job() 返回 TtsJob 或 None。
- ControlPlaneClient.heartbeat(job_id, progress) 返回 None。
- ControlPlaneClient.download_asset(asset_id, target) 返回 Path。
- ControlPlaneClient.upload_result(job_id, audio_path, metadata) 返回 None。
- ControlPlaneClient.report_failure(job_id, code, message) 返回 None。
- ColabWorker.run_once() 返回 bool。
- ColabWorker.run_forever(stop_event: asyncio.Event | None = None) 返回 None；传入 stop_event 时支持测试和优雅停止，省略时持续运行。
- GpuSelector.select(registry, probe) 返回 ModelProfile 或 None。

- [ ] **Step 1: 写失败的 HTTP 客户端和选择器测试**

~~~python
def test_claim_job_sends_bearer_token(httpx_mock, client):
    httpx_mock.add_response(
        method="POST",
        url="https://factory.example/api/v1/workers/claim",
        status_code=200,
        json={
            "jobId": "job-1",
            "text": "测试",
            "chapterIndex": 1,
            "segmentIndex": 1,
        },
    )

    job = run_async(client.claim_job())

    request = httpx_mock.get_request()
    assert request.headers["Authorization"] == "Bearer worker-token"
    assert job.job_id == "job-1"
~~~

- [ ] **Step 2: 运行测试确认失败**

运行：

~~~text
rtk pytest -q worker/tests/test_server_client.py worker/tests/test_colab_worker.py worker/tests/test_gpu_selector.py
~~~

预期：失败，因为 HTTP 客户端、Worker 循环和 GPU 选择器尚不存在。

- [ ] **Step 3: 实现带心跳和退避的 Worker 循环**

使用 httpx.AsyncClient，为连接、读取和上传设置有限超时。Worker 注册后下载并缓存参考音频到 /content/audiobook-cache/voices/{sha256}.wav，不打印 Token。

Worker 顺序：

1. 探测运行时。
2. 选择可用模型。
3. 注册 Worker。
4. 领取一个 Job。
5. 启动心跳协程。
6. 调用 TTS 引擎输出 WAV。
7. 上传结果或报告错误。
8. 继续领取下一个 Job。

API 返回 204 时等待 15 秒；网络失败按 15、30、60 秒退避。AUTH_REQUIRED、QUOTA_PAUSED、WAITING_FOR_GPU 使 Worker 停止领取并进入等待状态。

- [ ] **Step 4: 运行 Worker 全量测试**

运行：

~~~text
rtk pytest -q worker/tests/test_server_client.py worker/tests/test_colab_worker.py worker/tests/test_gpu_selector.py
rtk pytest -q worker/tests
~~~

预期：新增测试和当前 Worker 测试通过，不要求真实 Google 登录、Colab GPU 或外网。

- [ ] **Step 5: 提交**

~~~text
rtk git add worker/pyproject.toml worker/src/audiobook_worker/config.py worker/src/audiobook_worker/server_client.py worker/src/audiobook_worker/colab_worker.py worker/src/audiobook_worker/gpu_selector.py worker/tests/test_server_client.py worker/tests/test_colab_worker.py worker/tests/test_gpu_selector.py
rtk git commit -m "feat: 增加 Colab Worker 通信和 GPU 选择"
~~~

### Task 6: 实现书籍、试听、任务和章节进度 API

**Files:**

- Create: control-center/src/main/java/com/audiobookfactory/control/book/BookController.java
- Create: control-center/src/main/java/com/audiobookfactory/control/book/BookService.java
- Create: control-center/src/main/java/com/audiobookfactory/control/worker/WorkerController.java
- Create: control-center/src/main/java/com/audiobookfactory/control/worker/WorkerService.java
- Create: control-center/src/main/java/com/audiobookfactory/control/job/JobController.java
- Create: control-center/src/main/java/com/audiobookfactory/control/job/JobService.java
- Create: control-center/src/main/java/com/audiobookfactory/control/job/JobClaimRepository.java
- Create: control-center/src/main/java/com/audiobookfactory/control/progress/ProgressController.java
- Create: control-center/src/main/java/com/audiobookfactory/control/progress/ProgressQueryService.java
- Create: control-center/src/test/java/com/audiobookfactory/control/book/BookControllerTest.java
- Create: control-center/src/test/java/com/audiobookfactory/control/job/JobClaimRepositoryTest.java
- Create: control-center/src/test/java/com/audiobookfactory/control/worker/WorkerControllerTest.java

**Interfaces:**

- POST /api/v1/books：multipart 上传 EPUB。
- GET /api/v1/books：返回书籍摘要。
- GET /api/v1/books/{bookId}：返回书籍详情。
- GET /api/v1/books/{bookId}/chapters：返回章节状态。
- GET /api/v1/books/{bookId}/progress：返回 completedChapters、totalChapters 和 currentChapter。
- POST /api/v1/books/{bookId}/preview：按章节和 Preset 创建试听 Job。
- POST /api/v1/books/{bookId}/generation：按章节范围创建正式 Job。
- POST /api/v1/books/{bookId}/pause 和 /resume：暂停或继续。
- GET /api/v1/tts/models 和 /api/v1/tts/presets：返回模型和 Preset。
- POST /api/v1/workers/register：使用一次性 WORKER_ENROLL_TOKEN 注册运行时能力并返回短期 Worker Token。
- POST /api/v1/workers/claim：Worker 使用 Bearer Token 领取一个片段任务，无任务时返回 204。
- POST /api/v1/workers/jobs/{jobId}/heartbeat、/result 和 /failure：更新租约、回传音频元数据或报告失败。
- GET /api/v1/assets/{assetId}/download：Worker 下载参考音频，服务端校验 Worker Token 和资产归属。

- [ ] **Step 1: 写失败的 Controller 和租约测试**

~~~java
@Test
void claimReturnsEarliestSegmentOfEarliestRunningChapter() {
    JobClaim claim = repository.claimNext(
        "worker-1",
        Instant.parse("2026-09-05T00:00:00Z")
    );

    assertThat(claim.chapterIndex()).isEqualTo(1);
    assertThat(claim.segmentIndex()).isEqualTo(1);
    assertThat(claim.leaseUntil()).isAfter(
        Instant.parse("2026-09-05T00:00:00Z")
    );
}

@Test
void progressCountsOnlySuccessfulChapters() {
    ChapterProgress progress = progressQuery.get("book-1");

    assertThat(progress.completedChapters()).isEqualTo(3);
    assertThat(progress.totalChapters()).isEqualTo(10);
}
~~~

- [ ] **Step 2: 运行测试确认失败**

运行：

~~~text
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -Dtest=BookControllerTest,JobClaimRepositoryTest,WorkerControllerTest test"
~~~

预期：失败，因为 Controller、服务和租约查询尚不存在。

- [ ] **Step 3: 实现 API 和 PostgreSQL 租约**

正式生成只允许一部书处于 RUNNING，第一版按章节序号和片段序号顺序领取任务。领取使用 PostgreSQL 事务、FOR UPDATE SKIP LOCKED 和 5 分钟租约：

~~~sql
WITH candidate AS (
    SELECT gj.id
    FROM generation_job gj
    JOIN chapter c ON c.id = gj.chapter_id
    JOIN book_version bv ON bv.id = c.book_version_id
    JOIN book b ON b.id = bv.book_id
    WHERE b.status = 'RUNNING'
      AND c.status IN ('WAITING', 'RUNNING')
      AND gj.status = 'WAITING'
      AND (gj.next_retry_at IS NULL OR gj.next_retry_at <= now())
      AND NOT EXISTS (
          SELECT 1
          FROM chapter previous
          WHERE previous.book_version_id = c.book_version_id
            AND previous.chapter_index < c.chapter_index
            AND previous.status <> 'SUCCESS'
      )
    ORDER BY c.chapter_index, gj.segment_index
    FOR UPDATE SKIP LOCKED
    LIMIT 1
)
UPDATE generation_job
SET status = 'LEASED',
    lease_owner = :workerId,
    lease_until = now() + interval '5 minutes',
    heartbeat_at = now(),
    attempts = attempts + 1
WHERE id IN (SELECT id FROM candidate)
RETURNING *;
~~~

心跳只能由当前 lease_owner 延长租约。过期的 LEASED、GENERATING 和 UPLOADING Job 在下一次领取前恢复为 WAITING。结果回传校验 Job 状态、Worker 租约和幂等键。

章节只有在全部内部 Job 成功且章节音频合并成功后更新为 SUCCESS；进度查询只统计 chapter.status = SUCCESS。

- [ ] **Step 4: 运行 API、租约和 Java 全量测试**

运行：

~~~text
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -Dtest=BookControllerTest,JobClaimRepositoryTest,WorkerControllerTest test"
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml test"
~~~

预期：Controller、Testcontainers PostgreSQL 租约测试和全部 Java 测试通过。

- [ ] **Step 5: 提交**

~~~text
rtk git add control-center/src/main/java/com/audiobookfactory/control/book control-center/src/main/java/com/audiobookfactory/control/worker control-center/src/main/java/com/audiobookfactory/control/job control-center/src/main/java/com/audiobookfactory/control/progress control-center/src/test/java/com/audiobookfactory/control
rtk git commit -m "feat: 增加书籍任务和章节进度 API"
~~~

### Task 7: 接入音频校验、章节合并和 Audiobookshelf

**Files:**

- Create: control-center/src/main/java/com/audiobookfactory/control/audio/MediaToolRunner.java
- Create: control-center/src/main/java/com/audiobookfactory/control/audio/FfmpegMediaService.java
- Create: control-center/src/main/java/com/audiobookfactory/control/audio/AudioValidationResult.java
- Create: control-center/src/main/java/com/audiobookfactory/control/library/AudiobookshelfClient.java
- Create: control-center/src/main/java/com/audiobookfactory/control/library/LibraryPublishService.java
- Create: control-center/src/test/java/com/audiobookfactory/control/audio/FfmpegMediaServiceTest.java
- Create: control-center/src/test/java/com/audiobookfactory/control/library/AudiobookshelfClientTest.java
- Modify: control-center/pom.xml
- Modify: control-center/src/main/resources/application.yml

**Interfaces:**

- FfmpegMediaService.validate(Path wav) 返回 AudioValidationResult。
- FfmpegMediaService.mergeChapter(List<Path> orderedWavFiles, Path outputMp3, ChapterMetadata metadata) 返回 Path。
- AudiobookshelfClient.scan(String libraryId) 返回 void。
- LibraryPublishService.publishChapter(Chapter chapter, List<AudioAsset> assets) 返回 Path。

- [ ] **Step 1: 写失败的媒体和 API 客户端测试**

~~~java
@Test
void rejectsMissingAudio() {
    AudioValidationResult result =
        mediaService.validate(Path.of("missing.wav"));

    assertThat(result.valid()).isFalse();
    assertThat(result.errorCode()).isEqualTo("AUDIO_INVALID");
}

@Test
void scanUsesConfiguredLibraryAndBearerKey() {
    client.scan("library-1");

    RecordedRequest request = server.takeRequest();
    assertThat(request.getPath()).isEqualTo(
        "/api/libraries/library-1/scan"
    );
    assertThat(request.getHeader("Authorization")).startsWith("Bearer ");
}
~~~

- [ ] **Step 2: 运行测试确认失败**

运行：

~~~text
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -Dtest=FfmpegMediaServiceTest,AudiobookshelfClientTest test"
~~~

预期：失败，因为媒体服务和 Audiobookshelf 客户端尚不存在。

- [ ] **Step 3: 实现服务器媒体流水线**

使用 ProcessBuilder 参数数组调用 ffprobe 和 ffmpeg，禁止拼接 shell 命令。收到 WAV 后先写临时文件，再执行文件大小、ffprobe、完整解码和 SHA256 检查；校验通过后原子移动到片段正式路径。AudiobookshelfClientTest 使用 OkHttp MockWebServer，control-center/pom.xml 增加 test scope 的 mockwebserver 依赖，避免测试访问真实服务。

章节发布目录：

~~~text
library/book-title/
├── cover.jpg
├── book.epub
├── 001 - chapter-title.mp3
└── 002 - next-chapter-title.mp3
~~~

合并统一采样率和声道，默认不执行 loudnorm。MP3 写入章节序号和标题 metadata。章节成功后调用 POST /api/libraries/{libraryId}/scan。

配置项为 AUDIOBOOKSHELF_BASE_URL、AUDIOBOOKSHELF_LIBRARY_ID 和 AUDIOBOOKSHELF_API_KEY，只从环境变量读取。

- [ ] **Step 4: 运行媒体、API 和 Java 全量测试**

运行：

~~~text
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -Dtest=FfmpegMediaServiceTest,AudiobookshelfClientTest test"
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml test"
~~~

预期：WAV fixture 验证、章节合并、请求鉴权和全部 Java 测试通过。

- [ ] **Step 5: 提交**

~~~text
rtk git add control-center/pom.xml control-center/src/main/java/com/audiobookfactory/control/audio control-center/src/main/java/com/audiobookfactory/control/library control-center/src/test/java/com/audiobookfactory/control/audio control-center/src/test/java/com/audiobookfactory/control/library control-center/src/main/resources/application.yml
rtk git commit -m "feat: 增加音频校验和 Audiobookshelf 发布"
~~~

### Task 8: 创建简化 Colab Notebook 和候选模型评测入口

**Files:**

- Create: notebooks/audiobook_factory_colab.ipynb
- Create: scripts/build_colab_notebook.py
- Create: worker/src/audiobook_worker/cosyvoice_engine.py
- Create: worker/src/audiobook_worker/indextts_engine.py
- Create: worker/src/audiobook_worker/f5_engine.py
- Create: worker/tests/test_candidate_engines.py
- Create: examples/tts-benchmark.json
- Create: docs/operations/tts-evaluation.md

**Interfaces:**

- build_colab_notebook.py 使用 nbformat 生成固定结构 Notebook。
- Notebook 只包含安装依赖、读取 Colab Secret、GPU 探测和启动 Worker 四类单元。
- CosyVoice3Engine、IndexTts25Engine 和 F5TtsEngine 实现 TtsEngine。
- tts-benchmark.json 的每个样本包含 id、text、language 和 category。

- [ ] **Step 1: 写失败的候选引擎和 Notebook 结构测试**

~~~python
def test_colab_notebook_has_one_worker_entrypoint():
    notebook = json.loads(
        Path("notebooks/audiobook_factory_colab.ipynb").read_text()
    )
    source = chr(10).join(cell["source"] for cell in notebook["cells"])

    assert source.count("run_forever") == 1
    assert "AUDIOBOOK_CONTROL_URL" in source
    assert "AUDIOBOOK_WORKER_TOKEN" in source
    assert "google.colab.drive" not in source
~~~

- [ ] **Step 2: 运行测试确认失败**

运行：

~~~text
rtk pytest -q worker/tests/test_candidate_engines.py
rtk python scripts/build_colab_notebook.py --check
~~~

预期：失败，因为候选适配器和新的 Notebook 尚不存在。

- [ ] **Step 3: 实现一次启动和逐个模型评测**

Notebook 从 Colab Secret 读取 AUDIOBOOK_CONTROL_URL 和 AUDIOBOOK_WORKER_TOKEN，不打印 Token；启动单元调用：

~~~python
worker = ColabWorker.from_environment()
await worker.run_forever()
~~~

每个 Colab 运行时只加载一个模型。候选顺序为 Qwen3-TTS 1.7B、CosyVoice 3、IndexTTS 2.5、F5-TTS。评测使用相同参考音频和 3～5 段文本，覆盖普通叙述、对话、数字/专有名词、长句标点和情绪变化。

记录发音准确率、音色一致性、长段自然度、停顿、片段衔接、RTF、GPU 型号、显存、启动耗时和失败率。结果写入服务器实验记录，不修改已经开始的书籍 Preset。

- [ ] **Step 4: 运行 Notebook、候选契约和构建测试**

运行：

~~~text
rtk pytest -q worker/tests/test_candidate_engines.py
rtk python scripts/build_colab_notebook.py --check
rtk python -m json.tool notebooks/audiobook_factory_colab.ipynb
~~~

预期：Notebook 是合法 nbformat，只有一个 Worker 启动入口，候选引擎契约测试通过。

- [ ] **Step 5: 提交**

~~~text
rtk git add notebooks/audiobook_factory_colab.ipynb scripts/build_colab_notebook.py worker/src/audiobook_worker/cosyvoice_engine.py worker/src/audiobook_worker/indextts_engine.py worker/src/audiobook_worker/f5_engine.py worker/tests/test_candidate_engines.py examples/tts-benchmark.json docs/operations/tts-evaluation.md
rtk git commit -m "feat: 增加 Colab Notebook 和 TTS 评测入口"
~~~

### Task 9: 实现生成驾驶舱前端

**Files:**

- Create: web/package.json
- Create: web/tsconfig.json
- Create: web/vite.config.ts
- Create: web/src/main.ts
- Create: web/src/App.vue
- Create: web/src/api.ts
- Create: web/src/types.ts
- Create: web/src/components/WorkerStatus.vue
- Create: web/src/components/BookProgress.vue
- Create: web/src/components/BookList.vue
- Create: web/src/components/ChapterTable.vue
- Create: web/src/components/PreviewPanel.vue
- Create: web/src/components/ErrorCenter.vue
- Create: web/src/styles.css
- Create: web/tests/dashboard.spec.ts
- Modify: control-center/src/main/resources/application.yml

**Interfaces:**

- api.listBooks() 返回 Promise<BookSummary[]>。
- api.getBookProgress(bookId) 返回 Promise<BookProgress>。
- api.uploadEpub(file) 返回 Promise<BookDetail>。
- api.startPreview(bookId, request) 返回 Promise<JobView>。
- api.startGeneration(bookId, request) 返回 Promise<void>。
- api.pauseBook(bookId) 和 api.resumeBook(bookId) 返回 Promise<void>。
- api.getWorkerStatus() 返回 Promise<WorkerStatus>。

- [ ] **Step 1: 写失败的前端行为测试**

~~~typescript
test("shows chapter progress as primary progress", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByText("43 / 2334 章")).toBeVisible();
  await expect(page.getByText("当前：第 44 章")).toBeVisible();
  await expect(page.getByText(/片段/)).not.toBeVisible();
});
~~~

- [ ] **Step 2: 运行测试确认失败**

运行：

~~~text
rtk npm --prefix web install
rtk npm --prefix web run test
~~~

预期：失败，因为前端工程和页面组件尚不存在。

- [ ] **Step 3: 实现生成驾驶舱**

web/package.json 固定 Vue 3、Vite、TypeScript、@vitejs/plugin-vue 和 @playwright/test；test 脚本使用 Playwright，build 脚本把产物写入控制中心的 static 目录。前端测试通过 page.route 注入固定书籍和进度响应，不依赖已启动的后端。

首页只显示 Worker 状态、实际 GPU、显存、当前模型、书籍章节完成数/总章节数、当前章节、上传 EPUB、试听、开始、暂停、继续、磁盘空间和异常数量。

书籍详情显示章节表；点击章节后才显示内部片段。试听面板允许选择章节文本、音色 Preset 和候选模型，并排播放结果。

不添加用户管理、登录页、注册页或管理员角色。APP_ACCESS_TOKEN 启用时，前端收到 401 后显示 Token 输入框，并将 Token 只保存到 sessionStorage。Vite 输出目录为 control-center/src/main/resources/static。

- [ ] **Step 4: 运行前端测试和生产构建**

运行：

~~~text
rtk npm --prefix web run test
rtk npm --prefix web run build
~~~

预期：章节级驾驶舱测试通过，静态资源生成到控制中心 static 目录。

- [ ] **Step 5: 提交**

~~~text
rtk git add web control-center/src/main/resources/application.yml
rtk git commit -m "feat: 增加章节级生成驾驶舱"
~~~

### Task 10: 组装 Docker 部署、访问保护和运维文档

**Files:**

- Create: control-center/Dockerfile
- Create: infra/mvp/docker-compose.yml
- Create: infra/mvp/.env.example
- Create: docs/operations/mvp-colab-deployment.md
- Create: infra/mvp/tests/test_compose.sh
- Modify: worker/Dockerfile
- Modify: worker/pyproject.toml
- Preserve: infra/p0/docker-compose.yml 作为现有 AI Studio 兼容环境

**Interfaces:**

- 控制中心容器监听 8080。
- PostgreSQL 和 Audiobookshelf 使用持久化卷和显式镜像版本。
- 控制中心、PostgreSQL 和 Audiobookshelf 走 Compose 内部网络。
- noVNC 6080 和 CDP 9222 继续绑定服务器回环地址。
- Colab Worker 使用 CONTROL_PLANE_URL 和 Worker Token。

- [ ] **Step 1: 写失败的 Compose 合同测试**

~~~bash
#!/usr/bin/env bash
set -euo pipefail

docker compose -f infra/mvp/docker-compose.yml config --quiet
test "$(docker compose -f infra/mvp/docker-compose.yml config --services | grep -c '^postgres$')" -eq 1
test "$(docker compose -f infra/mvp/docker-compose.yml config --services | grep -c '^control-center$')" -eq 1
test "$(docker compose -f infra/mvp/docker-compose.yml config --services | grep -c '^audiobookshelf$')" -eq 1
~~~

- [ ] **Step 2: 运行测试确认失败**

运行：

~~~text
rtk bash infra/mvp/tests/test_compose.sh
~~~

预期：失败，因为 MVP Compose 文件尚不存在。

- [ ] **Step 3: 创建镜像、Compose 和环境模板**

Compose 包含 postgres、control-center 和 audiobookshelf。控制中心挂载 data/books、data/library 和 data/diagnostics；Audiobookshelf 读取 data/library；PostgreSQL 持久化 data/postgres。

控制中心默认只绑定 127.0.0.1:8080。Colab 需要访问时，在服务器外层增加带访问 Token 的 HTTPS 反向代理和新的公网端口；不公开数据库、CDP、noVNC、代理管理端口，也不复用代理端口。

infra/mvp/.env.example 只列出 DB_URL、DB_USERNAME、DB_PASSWORD、STORAGE_ROOT、WORKER_ENROLL_TOKEN、APP_ACCESS_TOKEN、AUDIOBOOKSHELF_BASE_URL、AUDIOBOOKSHELF_LIBRARY_ID 和 AUDIOBOOKSHELF_API_KEY。真实 .env 不提交 Git，启动脚本拒绝示例值。

- [ ] **Step 4: 运行 Compose、镜像和文档校验**

运行：

~~~text
rtk bash infra/mvp/tests/test_compose.sh
rtk docker compose -f infra/mvp/docker-compose.yml config
rtk docker compose -f infra/mvp/docker-compose.yml build control-center
~~~

预期：Compose 合法，控制中心镜像构建成功，卷路径和健康检查清晰。

- [ ] **Step 5: 提交**

~~~text
rtk git add control-center/Dockerfile infra/mvp worker/Dockerfile worker/pyproject.toml docs/operations/mvp-colab-deployment.md
rtk git commit -m "feat: 增加 MVP 容器部署"
~~~

### Task 11: 完成端到端烟囱测试和单章验收

**Files:**

- Create: scripts/mvp-smoke.sh
- Create: examples/mvp-sample.epub
- Create: docs/operations/mvp-acceptance.md
- Modify: docs/operations/mvp-colab-deployment.md
- Create: control-center/src/test/java/com/audiobookfactory/control/MvpContractTest.java

**Interfaces:**

- scripts/mvp-smoke.sh --base-url URL --epub PATH --access-token TOKEN。
- MvpContractTest 使用 fake Worker 完成上传、领取、回传、校验、合并和进度查询。
- 验收报告记录服务器版本、Colab GPU、模型版本、音频 SHA256 和 Audiobookshelf 扫描结果。

- [ ] **Step 1: 写失败的端到端合同测试**

~~~java
@Test
void oneChapterCountsAfterAllSegmentsAreUploaded() {
    BookDetail book = api.upload(sampleEpub);
    api.startGeneration(
        book.id(),
        new GenerationStartRequest(1, 1, "preset-qwen")
    );

    fakeWorker.runUntilNoWaitingJobs();

    BookProgress progress = api.progress(book.id());
    assertThat(progress.completedChapters()).isEqualTo(1);
    assertThat(progress.totalChapters()).isEqualTo(2);
    assertThat(audioStore.chapterFile(book.id(), 1)).exists();
}
~~~

- [ ] **Step 2: 运行测试确认失败**

运行：

~~~text
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml -Dtest=MvpContractTest test"
~~~

预期：失败，因为完整上传、任务、音频和发布链路还没有接通。

- [ ] **Step 3: 实现真实烟囱脚本和恢复场景**

脚本检查控制中心健康状态、上传 EPUB、查询章节、创建第一章任务、轮询章节进度、检查 MP3 和 manifest、触发 Audiobookshelf 扫描并输出最终状态。

真实 Colab 验证：

1. 打开保存好的 Colab Notebook。
2. 完成 Google 页面要求的登录。
3. 选择 GPU 运行时并启动 Worker。
4. 在后台上传小 EPUB。
5. 试听并确认 Preset。
6. 生成第一章。
7. 第一个片段完成后停止 Worker。
8. 租约过期后重新启动 Worker。
9. 验证已完成片段不重复，未完成片段继续。
10. 在 Audiobookshelf 中播放该章。

候选模型评测在 Qwen 基线烟囱测试通过后执行。

- [ ] **Step 4: 运行所有本地验证和真实单章验收**

运行：

~~~text
rtk cmd /c "C:/Users/lingpfeng.peng/.codex/bin/mvn-auto.cmd -f control-center/pom.xml verify"
rtk pytest -q worker/tests
rtk bash scripts/mvp-smoke.sh --base-url http://127.0.0.1:8080 --epub examples/mvp-sample.epub
~~~

预期：

- Java verify 通过。
- Python 测试通过；本机缺少 ffprobe 或不具备 symlink 权限时，测试报告必须明确分类，不能伪报全绿。
- Fake Worker 合同测试通过。
- 真实 Colab Worker 生成并校验至少一章。
- Audiobookshelf 能发现并播放该章。

- [ ] **Step 5: 提交验收文档**

~~~text
rtk git add scripts/mvp-smoke.sh examples/mvp-sample.epub docs/operations/mvp-acceptance.md docs/operations/mvp-colab-deployment.md control-center/src/test/java/com/audiobookfactory/control/MvpContractTest.java
rtk git commit -m "test: 增加有声书 MVP 端到端验收"
~~~

## 自检清单

### 规格覆盖

- Colab GPU 实际推理：Task 2、Task 5、Task 8、Task 11。
- 自动探测 GPU 和模型：Task 1、Task 5。
- 单用户但不做账号管理：Task 3、Task 9、Task 10。
- EPUB 首版输入：Task 4。
- 章节级主进度、片段级断点：Task 4、Task 6、Task 11。
- 服务器主存储：Task 3、Task 7、Task 10。
- Qwen 基线和候选模型：Task 1、Task 2、Task 8。
- 音色 Preset 和参考音频：Task 1、Task 2、Task 6。
- 断线租约恢复：Task 5、Task 6、Task 11。
- FFmpeg 校验、合并和 Audiobookshelf：Task 7、Task 11。
- 生成驾驶舱：Task 9。
- 公网访问保护：Task 3、Task 10。

### 类型一致性

- TtsEngine.synthesize(job, destination) 由所有候选引擎和 Colab Worker 使用。
- TtsJob 同时由 TTS 引擎、HTTP 客户端和 Worker 循环使用。
- Worker API 返回 workerToken，数据库只保存 Token 哈希。
- 章节和片段序号统一从 1 开始，并在迁移、DTO、前端和测试中保持一致。
- 章节完成数只来自 chapter.status = SUCCESS，不能用片段数代替。

### 安全和回归

- 计划不自动输入 Google 凭据，不通过页面自动化绕过 Colab 控制，不把外部凭据写入仓库。
- 现有 infra/p0、AI Studio Provider 和未提交改动不被覆盖。
- 每个实现任务都先写失败测试，再实现最小代码，再运行回归测试并单独提交。
