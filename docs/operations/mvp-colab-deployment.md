# Audiobook Factory MVP 部署与恢复

这份文档对应 `infra/mvp/docker-compose.yml`。它只部署单用户控制中心、PostgreSQL 和 Audiobookshelf；Google AI Studio 的浏览器兼容环境仍由 `infra/p0` 单独管理，Colab 只运行 TTS Worker。

## 1. 服务边界

| 服务 | 容器内端口 | 默认主机绑定 | 用途 |
| --- | ---: | --- | --- |
| `control-center` | 8080 | `127.0.0.1:8080` | 上传 EPUB、管理章节任务和回传音频 |
| `postgres` | 5432 | 不发布 | 持久化业务状态 |
| `audiobookshelf` | 80 | `127.0.0.1:13378` | 手机/网页播放已发布音频 |

`noVNC:6080`、Chrome CDP `9222` 和代理端口不属于 MVP 公网入口。它们继续只绑定服务器回环地址；不要把它们写入公网 NAT，也不要拿代理端口复用为控制中心端口。

如果服务器已有 `48878 -> 7891` 的代理转发，保持它不变。控制中心应另外申请一个公网端口或域名，例如：

```text
Colab / 浏览器 HTTPS -> 反向代理 -> 127.0.0.1:8080
手机 HTTPS       -> 反向代理 -> 127.0.0.1:13378
```

公网入口必须使用 HTTPS，并由入口层限制来源或要求额外认证；控制中心本身还可以通过 `APP_ACCESS_TOKEN` 对 `/api/v1/**` 和页面请求做 Bearer Token 校验。

## 2. 首次安装

在 Ubuntu 主机上安装 Docker Engine 和 Compose 插件，然后把仓库放到固定目录：

```bash
sudo mkdir -p /opt/audiobook-factory
sudo chown "$USER":"$USER" /opt/audiobook-factory
git clone https://github.com/plfccc/audiobook-factory.git /opt/audiobook-factory
cd /opt/audiobook-factory
```

如果是已有工作树，先确认分支包含 `control-center/src/main/resources/static` 的前端构建产物。修改 `web/` 后，在有 Node.js 的构建机执行：

```bash
npm ci --prefix web
npm run build --prefix web
```

准备持久化目录。控制中心镜像使用 UID 10001；Audiobookshelf 镜像通常使用 UID 1000，控制中心通过补充组共享 Library；PostgreSQL 的 Alpine 镜像使用 UID 70：

```bash
mkdir -p data/{books,library,diagnostics,sources,.staging,audiobookshelf/config,audiobookshelf/metadata,postgres}
sudo chown -R 10001:10001 data/books data/sources data/.staging data/diagnostics
sudo chown -R 1000:1000 data/library data/audiobookshelf
sudo chmod -R ug+rwX data/library data/audiobookshelf
sudo chown -R 70:70 data/postgres
```

若目标版本的 Audiobookshelf 使用的运行 UID 不是 1000，请用 `docker run --rm advplyr/audiobookshelf:2.21.0 id` 查询后替换上面的 UID，并同步修改 Compose 中的 `group_add`；不要为了绕过权限问题直接公开容器端口或把所有服务改成特权用户。

创建真实环境文件。不要把它提交 Git，也不要在聊天记录中粘贴 Token 或订阅地址：

```bash
cp infra/mvp/.env.example infra/mvp/.env
chmod 600 infra/mvp/.env
${EDITOR:-vi} infra/mvp/.env
```

至少替换以下示例值：

```text
DB_PASSWORD
WORKER_ENROLL_TOKEN
APP_ACCESS_TOKEN
AUDIOBOOKSHELF_LIBRARY_ID
AUDIOBOOKSHELF_API_KEY
```

启动脚本会拒绝 `change-me` 和 `replace-me` 开头的值：

```bash
bash infra/mvp/start.sh
docker compose --env-file infra/mvp/.env -f infra/mvp/docker-compose.yml ps
curl --fail http://127.0.0.1:8080/actuator/health
```

控制中心默认不直接暴露公网。先在服务器上用 SSH 隧道验证：

```bash
ssh -L 8080:127.0.0.1:8080 -L 13378:127.0.0.1:13378 root@SERVER_IP
```

本机访问 `http://127.0.0.1:8080` 或 `http://127.0.0.1:13378`。配置了 `APP_ACCESS_TOKEN` 时，页面收到 401 会显示令牌输入框；令牌仅保存于当前浏览器的 `sessionStorage`。

## 3. 反向代理与 Colab

反向代理只转发控制中心或 Audiobookshelf 的 HTTP 服务。示例（把域名和证书路径替换为自己的值）：

```nginx
server {
    listen 443 ssl;
    server_name book.example.com;

    ssl_certificate     /etc/letsencrypt/live/book.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/book.example.com/privkey.pem;

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto https;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

反向代理新增的公网端口必须与 `infra/p0` 的代理端口不同。Colab Notebook 中填写：

```text
CONTROL_PLANE_URL=https://book.example.com
AUDIOBOOK_WORKER_TOKEN=<.env 中的 WORKER_ENROLL_TOKEN>
```

Worker 首次注册后会获得短期 Worker Token；该 Token 只放在 Colab 运行时 Secret 或环境变量中，不写入 Notebook、任务快照或 Git。

建议启动顺序：

1. 先启动 MVP 控制中心并确认 `/actuator/health` 为 UP。
2. 再打开 `notebooks/audiobook_factory_colab.ipynb`，填写控制中心 HTTPS 地址和 Worker Token。
3. Colab 运行时自动探测 CUDA/GPU，并只加载当前显存兼容的模型；未准备本地权重时不要切换到候选模型。
4. 页面导入 EPUB，选择章节试听，确认声音后提交整章或后续章节生成。

Worker 通过 HTTPS 领取最小片段任务，心跳续租，上传 WAV 后由服务端做格式、时长、SHA256 校验。章节全部完成后才会合并并发布到 Audiobookshelf。

单章部署验收步骤和证据表见 [`mvp-acceptance.md`](mvp-acceptance.md)；命令行烟囱测试使用仓库中的 `scripts/mvp-smoke.sh`。

## 4. 日常运维

```bash
cd /opt/audiobook-factory
docker compose --env-file infra/mvp/.env -f infra/mvp/docker-compose.yml ps
docker compose --env-file infra/mvp/.env -f infra/mvp/docker-compose.yml logs --tail=200 control-center
docker compose --env-file infra/mvp/.env -f infra/mvp/docker-compose.yml logs --tail=200 postgres
docker compose --env-file infra/mvp/.env -f infra/mvp/docker-compose.yml restart control-center
```

只重启控制中心不会删除数据库、书籍或音频。禁止用 `docker compose down -v`，因为这会删除 Compose 管理的卷；本 Compose 使用主机目录，仍应把 `data/` 纳入备份。

低磁盘时先暂停书籍任务，再清理确认无用的诊断截图和已验证的中间 WAV。不要删除正在运行任务的 `.staging` 或 Worker 输出目录。

## 5. 认证、密钥和恢复

- `APP_ACCESS_TOKEN` 是控制中心页面/API 的 Bearer Token；没有它时，必须依赖 HTTPS 反向代理的访问控制。
- `WORKER_ENROLL_TOKEN` 只用于 Worker 首次注册；Worker 获得的运行 Token 不要与它混用。
- `AUDIOBOOKSHELF_API_KEY` 只写在服务器 `infra/mvp/.env`，不写入浏览器和任务快照。
- `data/audiobookshelf/config`、`data/audiobookshelf/metadata`、`data/postgres`、`data/books` 和 `data/library` 都需要备份。
- Chrome 登录 profile 仍属于 P0 浏览器运行时，含 Google 会话 Cookie；只允许浏览器容器挂载，权限保持 700，不要同步到网盘。

服务器重启后的恢复顺序：

```bash
cd /opt/audiobook-factory
bash infra/mvp/start.sh
curl --fail http://127.0.0.1:8080/actuator/health
```

然后重新启动 Colab Worker。Worker 会从控制中心重新注册，过期 Lease 会被回收为 WAITING，已成功章节不会重复生成。若 Google 登录、配额或页面状态需要人工处理，Worker 会暂停并在日志中报告 `AUTH_REQUIRED`、`QUOTA_PAUSED` 或 `HUMAN_REQUIRED`。
