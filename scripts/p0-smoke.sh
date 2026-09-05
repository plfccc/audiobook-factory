#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
COMPOSE_FILE="$ROOT/infra/p0/docker-compose.yml"
COMPOSE=(docker compose -f "$COMPOSE_FILE")
DATA_DIR="$ROOT/data"
BOOKS_DIR="$DATA_DIR/books"
PROFILE_DIR="$DATA_DIR/chrome-profile"
DIAGNOSTICS_DIR="$DATA_DIR/diagnostics"
REQUEST_ID="p0-manual-001"
export P0_PROXY_DIR="${P0_PROXY_DIR:-/opt/audiobook-factory-proxy}"

if ! command -v docker >/dev/null 2>&1; then
  echo "未找到 Docker，请先安装 Docker Engine 和 Compose 插件。" >&2
  exit 3
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "未找到 Docker Compose 插件。" >&2
  exit 3
fi

for required_file in \
  "$P0_PROXY_DIR/mihomo" \
  "$P0_PROXY_DIR/config.yaml" \
  "$P0_PROXY_DIR/subscription.base64"; do
  if [[ ! -r "$required_file" ]]; then
    echo "代理文件不存在或不可读：$required_file" >&2
    exit 3
  fi
done
if [[ ! -x "$P0_PROXY_DIR/mihomo" ]]; then
  echo "代理内核不可执行：$P0_PROXY_DIR/mihomo" >&2
  exit 3
fi

install -d -m 700 "$PROFILE_DIR" "$BOOKS_DIR" "$DIAGNOSTICS_DIR"
chmod 700 "$PROFILE_DIR" "$BOOKS_DIR" "$DIAGNOSTICS_DIR"

"${COMPOSE[@]}" up -d --build browser worker

for attempt in $(seq 1 30); do
  if curl --silent --fail http://127.0.0.1:9222/json/version >/dev/null; then
    break
  fi
  if [[ "$attempt" == 30 ]]; then
    echo "Browser Runtime 未在限定时间内就绪。" >&2
    exit 4
  fi
  sleep 2
done

SSH_HOST="${P0_SSH_HOST:-SERVER}"
SSH_PORT="${P0_SSH_PORT:-22}"
echo "请通过 SSH 隧道打开 noVNC 并手动完成 Google 登录："
echo "ssh -p ${SSH_PORT} -N -L 6080:127.0.0.1:6080 root@${SSH_HOST}"
echo "然后访问 http://127.0.0.1:6080/vnc.html"

if [[ "${P0_LOGIN_CONFIRMED:-0}" != "1" ]]; then
  echo "未执行生成：完成手动登录后，设置 P0_LOGIN_CONFIRMED=1 再次运行本脚本。"
  echo "Browser Runtime 将保持运行，Chrome Profile 保存在 $PROFILE_DIR。"
  exit 2
fi

cleanup() {
  "${COMPOSE[@]}" down
}
trap cleanup EXIT

set +e
"${COMPOSE[@]}" run --rm --no-deps worker \
  python -m audiobook_worker.cli \
  --text-file /app/examples/p0-sample.txt \
  --preset /app/examples/p0-preset.json \
  --request-id "$REQUEST_ID" \
  --output-dir /data/books \
  --diagnostics-dir /data/diagnostics
status=$?
set -e

if [[ "$status" -ne 0 ]]; then
  echo "P0 生成失败，诊断目录：$DIAGNOSTICS_DIR/$REQUEST_ID" >&2
  find "$DIAGNOSTICS_DIR/$REQUEST_ID" -maxdepth 1 -type f -print 2>/dev/null || true
  exit "$status"
fi

WAV="$BOOKS_DIR/$REQUEST_ID.wav"
MANIFEST="$BOOKS_DIR/$REQUEST_ID.json"
test -s "$WAV"
test -s "$MANIFEST"
"${COMPOSE[@]}" run --rm --no-deps worker \
  ffprobe -v error \
  -show_entries format=duration:stream=codec_name,sample_rate,channels \
  -of json \
  "/data/books/$REQUEST_ID.wav" >/dev/null

echo "P0 冒烟测试成功：$WAV"
