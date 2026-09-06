#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
COMPOSE_FILE="${ROOT_DIR}/infra/mvp/docker-compose.yml"

docker compose -f "${COMPOSE_FILE}" config --quiet
services="$(docker compose -f "${COMPOSE_FILE}" config --services)"
grep -qx "postgres" <<<"${services}"
grep -qx "control-center" <<<"${services}"
grep -qx "audiobookshelf" <<<"${services}"

config="$(docker compose -f "${COMPOSE_FILE}" config)"
grep -q "postgres:16" <<<"${config}"
grep -q "advplyr/audiobookshelf:" <<<"${config}"
grep -q "host_ip: 127.0.0.1" <<<"${config}"
grep -Eq 'published: "?8080"?' <<<"${config}"
grep -Eq 'target: 8080' <<<"${config}"
grep -q 'AUDIOBOOKSHELF_ENABLED' <<<"${config}"
! grep -Eq 'published: "?13378"?' <<<"${config}"
! grep -q "0.0.0.0:9222" <<<"${config}"
! grep -q "0.0.0.0:6080" <<<"${config}"
grep -q "/data/books" <<<"${config}"
grep -q "/data/library" <<<"${config}"
grep -q "/data/diagnostics" <<<"${config}"
grep -q "/data/postgres" <<<"${config}"

echo "MVP Compose contract passed"
