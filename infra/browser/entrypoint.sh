#!/usr/bin/env bash
set -euo pipefail

install -d -o browser -g browser \
  /data/chrome-profile \
  /data/downloads \
  /data/diagnostics \
  /tmp/browser-runtime
chmod 0700 /tmp/browser-runtime

exec /usr/bin/supervisord -n -c /etc/supervisor/supervisord.conf
