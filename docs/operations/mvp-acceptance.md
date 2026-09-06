# MVP 验收记录

本记录用于验证“单 Admin + Colab Worker + 章节音频下载”闭环。章节是用户可见的最小进度节点，片段只负责内部生成、校验和断点续跑。

## 验收范围

- Admin 能导入 EPUB，解析章节并提交试听或按范围生成任务。
- Colab Worker 能注册、领取任务、生成并上传 WAV。
- 控制中心能校验 WAV、合并 MP3，并在章节列表返回播放和下载地址。
- 下载接口能返回 `audio/mpeg` 与附件响应头。
- 服务器只公开控制中心端口，数据库、浏览器、代理和 Audiobookshelf 不公开。
- Worker 掉线、任务失败、额度暂停和登录失效能够保留状态并支持恢复。

## 本地验证

Java 控制中心单元测试：

```powershell
C:\Users\lingpfeng.peng\.codex\bin\mvn-auto.cmd -f control-center/pom.xml test
```

前端检查：

```powershell
npm --prefix web run typecheck
npm --prefix web run test
npm --prefix web run build
```

Worker 合同测试：

```powershell
python -m pytest -q worker/tests
```

Windows 本地没有 `ffprobe` 或 Docker 时，音频校验测试和 Testcontainers 测试会因环境缺失而失败；应在服务器容器环境或安装依赖后复验，不能把这类失败误判为业务通过。

## 服务器烟囱测试

先确认 Admin 已启动、Colab Worker 已注册并保持轮询：

```bash
bash scripts/mvp-smoke.sh \
  --base-url http://SERVER_IP:8080 \
  --epub examples/mvp-sample.epub \
  --access-token '<APP_ACCESS_TOKEN>'
```

烟囱脚本会导入示例书、提交第一章、等待完成，并检查章节的 `audioUrl` 和 `audioDownloadUrl` 均为相对 API 路径。真实生产验收应使用单独的测试书，避免与正式任务混用。

## 手工检查清单

1. 浏览器打开 `http://SERVER_IP:8080`，输入 `APP_ACCESS_TOKEN` 后页面可用。
2. 导入 EPUB 后能看到章节列表和章节数。
3. 试听任务能提交；正式任务可填写 1～20 章的批次。
4. 第一章状态为 `SUCCESS` 时出现“播放”和“下载”。
5. 播放地址返回 `audio/mpeg`；下载地址返回 `Content-Disposition: attachment`。
6. 云防火墙/NAT 仅放行 Admin 端口；从公网无法访问 5432、13378、6080、7890、7891、9090、9222。
7. 下载文件保存到本地后，确认服务器上的临时文件和源文件仍按清理策略保留或删除。

## 证据记录

| 项目 | 记录 |
| --- | --- |
| 验收时间（Asia/Shanghai） | 待填写 |
| 控制中心版本 / Git commit | 待填写 |
| 服务器系统与 Docker 版本 | 待填写 |
| Colab GPU 型号 | 待填写 |
| 实际 TTS 模型与版本 | 待填写 |
| 生成章节数 / 任务数 | 待填写 |
| 音频 SHA-256 与时长 | 待填写 |
| Admin 公网地址 | 待填写 |
| 公网端口扫描结果 | 待填写 |
