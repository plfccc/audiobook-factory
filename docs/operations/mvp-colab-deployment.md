# Audiobook Factory MVP 部署说明

本版本只提供一个公网入口：控制中心 Admin。数据库、Worker、浏览器、代理和 Audiobookshelf 都不发布主机端口。服务器负责导入书籍、管理任务、合并音频，用户从 Admin 下载已经完成的章节。

## 1. 访问边界

生产环境的 Compose 端口规划如下：

| 服务 | 容器端口 | 主机端口 | 说明 |
| --- | ---: | --- | --- |
| `control-center` | 8080 | `0.0.0.0:8080` | 唯一公网入口 |
| `postgres` | 5432 | 不发布 | 仅 Compose 内网 |
| `audiobookshelf` | 80 | 不发布 | 暂不作为公网入口，保留后续接入 |

P0 的 noVNC `6080`、Chrome CDP `9222` 和代理端口也不属于本 Admin，不要在云防火墙或 NAT 中开放它们。已有的代理转发端口应单独管理，不要复用为 Admin 端口。

本次按用户要求使用明文 HTTP：

```text
http://SERVER_IP:8080
```

明文 HTTP 会暴露访问令牌和上传/下载内容，只适合个人临时使用。Admin 仍通过 `APP_ACCESS_TOKEN` 保护接口；不要把该令牌与服务器密码、Google 密码或 Worker 注册令牌复用。

## 2. 服务器初始化

将项目放在独立目录，例如 `/opt/audiobook-factory-mvp`，不要覆盖已有 P0 目录。准备目录并设置权限：

```bash
cd /opt/audiobook-factory-mvp
mkdir -p data/{books,library,diagnostics,sources,.staging,audiobookshelf/config,audiobookshelf/metadata,postgres}
chown -R 10001:10001 data/books data/sources data/.staging data/diagnostics
chown -R 1000:1000 data/library data/audiobookshelf
chmod -R ug+rwX data/library data/audiobookshelf
chown -R 70:70 data/postgres
```

复制并编辑环境变量：

```bash
cp infra/mvp/.env.example infra/mvp/.env
chmod 600 infra/mvp/.env
```

个人 HTTP 直连至少使用下面配置：

```text
CONTROL_CENTER_BIND_ADDRESS=0.0.0.0
CONTROL_CENTER_PORT=8080
AUDIOBOOKSHELF_ENABLED=false
```

还必须替换 `DB_PASSWORD`、`WORKER_ENROLL_TOKEN` 和 `APP_ACCESS_TOKEN`。`AUDIOBOOKSHELF_ENABLED=false` 时，空的 Audiobookshelf 配置不会阻止 Admin 启动；已合并的 MP3 仍会写入 `/data/library` 并可下载。Compose 默认也不会启动 Audiobookshelf profile。

启动和检查：

```bash
bash infra/mvp/start.sh
docker compose --env-file infra/mvp/.env -f infra/mvp/docker-compose.yml ps
curl --fail -H "Authorization: Bearer ${APP_ACCESS_TOKEN}" http://127.0.0.1:8080/actuator/health
```

云服务器安全组或 NAT 只需要放行 `8080/tcp` 到该控制中心；如果云平台需要端口映射，使用 `公网端口 -> 服务器 8080`。不要放行 5432、13378、6080、7890、7891、9090、9222。

## 3. Admin 使用方式

打开 `http://SERVER_IP:8080`，首次请求返回 401 时，在页面输入 `APP_ACCESS_TOKEN`。令牌只保存在当前浏览器的 `sessionStorage`。

使用流程：

1. 导入 EPUB，等待章节解析完成。
2. 选择章节，先生成试听任务确认音色和参数。
3. 正式生成时设置章节数，单次最多 20 章；后端仍以章节为最小可见进度节点，片段只用于内部断点续跑。
4. 章节完成后，列表出现“播放”和“下载”；下载地址为 `/api/v1/chapters/{chapterId}/audio/download`。
5. 下载到本地后，可以按磁盘空间清理服务器上的 `data/library`，不要删除正在生成的 `.staging` 文件。

## 4. Colab Worker

Colab Notebook 使用以下 Secret：

```text
AUDIOBOOK_CONTROL_URL=http://SERVER_IP:8080
AUDIOBOOK_WORKER_TOKEN=<WORKER_ENROLL_TOKEN>
AUDIOBOOK_ALLOW_INSECURE_HTTP=true
```

`AUDIOBOOK_ALLOW_INSECURE_HTTP=true` 是个人 HTTP 部署的显式开关，默认关闭。Notebook 不保存真实令牌，也不自动输入 Google 账号密码；首次使用仍需在 Google/AI Studio 浏览器环境中完成登录或验证。

Worker 会自动探测当前 Colab GPU，并从可用模型中选择兼容配置；没有 CUDA/GPU 时进入等待状态，不会默默用服务器 CPU 跑长篇任务。

## 5. 后续启用 Audiobookshelf

后续需要手机播放和断点播放时，再在服务器内网初始化 Audiobookshelf，配置：

```text
AUDIOBOOKSHELF_ENABLED=true
AUDIOBOOKSHELF_BASE_URL=http://audiobookshelf:80
AUDIOBOOKSHELF_LIBRARY_ID=<library-id>
AUDIOBOOKSHELF_API_KEY=<api-key>
```

启用 profile 并启动：

```bash
docker compose --env-file infra/mvp/.env -f infra/mvp/docker-compose.yml --profile audiobookshelf up -d
```

即使启用，也不需要给 Audiobookshelf 发布公网端口；后续 Android App 应通过单独的受保护 API 或服务端适配层接入。

## 6. 日常运维

```bash
cd /opt/audiobook-factory-mvp
docker compose --env-file infra/mvp/.env -f infra/mvp/docker-compose.yml ps
docker compose --env-file infra/mvp/.env -f infra/mvp/docker-compose.yml logs --tail=200 control-center
docker compose --env-file infra/mvp/.env -f infra/mvp/docker-compose.yml restart control-center
```

不要使用 `docker compose down -v`，也不要直接删除 `data/`。备份 PostgreSQL、源 EPUB、最终 MP3 和配置文件；浏览器 profile 可能包含 Google 会话 Cookie，不能上传网盘或提交 Git。
