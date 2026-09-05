#!/usr/bin/env bash
set -euo pipefail

compose=(docker compose -f infra/p0/docker-compose.yml)
download_token="$(od -An -N16 -tx1 /dev/urandom | tr -d ' \n')"
download_name="browser-runtime-smoke-download-${download_token}.txt"
download_host_path="data/downloads/${download_name}"
download_container_path="/data/downloads/${download_name}"

if test -e "${download_host_path}"; then
  printf 'refusing to overwrite persisted download: %s\n' "${download_host_path}" >&2
  exit 1
fi

"${compose[@]}" up -d --build browser

cleanup() {
  "${compose[@]}" exec -T browser rm -f "${download_container_path}" >/dev/null 2>&1 || true
  "${compose[@]}" down
}
trap cleanup EXIT

for attempt in $(seq 1 30); do
  if curl --silent --fail http://127.0.0.1:9222/json/version >/dev/null \
    && curl --silent --fail http://127.0.0.1:6080/vnc.html >/dev/null; then
    break
  fi
  sleep 2
done

curl --silent --fail http://127.0.0.1:9222/json/version >/dev/null
curl --silent --fail http://127.0.0.1:6080/vnc.html >/dev/null
test "$(docker compose -f infra/p0/docker-compose.yml port browser 6080)" = "127.0.0.1:6080"
test "$(docker compose -f infra/p0/docker-compose.yml port browser 9222)" = "127.0.0.1:9222"

curl --silent --fail --request PUT \
  "http://127.0.0.1:9222/json/new?http://127.0.0.1:6080/download-smoke.html?filename=${download_name}" >/dev/null

for attempt in $(seq 1 30); do
  if test -f "${download_host_path}"; then
    break
  fi
  sleep 1
done

test -f "${download_host_path}"
test "$(cat "${download_host_path}")" = "browser-runtime-download"
