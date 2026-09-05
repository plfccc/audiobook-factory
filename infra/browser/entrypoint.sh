#!/usr/bin/env bash
set -euo pipefail

install -d -o browser -g browser \
  /data/chrome-profile \
  /data/downloads \
  /data/diagnostics \
  /tmp/browser-runtime
chmod 0700 /tmp/browser-runtime
chmod 0700 /data/chrome-profile /data/downloads /data/diagnostics

# 容器被强制停止后 Chromium 可能留下 Singleton 锁；该 profile 只由本容器使用。
rm -f \
  /data/chrome-profile/SingletonCookie \
  /data/chrome-profile/SingletonLock \
  /data/chrome-profile/SingletonSocket

exec /usr/bin/supervisord -n -c /etc/supervisor/supervisord.conf
