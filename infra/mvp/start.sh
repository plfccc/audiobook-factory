#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose.yml"
ENV_FILE="${ENV_FILE:-${SCRIPT_DIR}/.env}"

if [[ ! -f "${ENV_FILE}" ]]; then
  echo "missing ${ENV_FILE}; copy .env.example and fill in real values" >&2
  exit 1
fi

required_keys=(DB_URL DB_USERNAME DB_PASSWORD STORAGE_ROOT WORKER_ENROLL_TOKEN APP_ACCESS_TOKEN)
for key in "${required_keys[@]}"; do
  value="$(awk -F= -v key="${key}" '$1 == key { sub(/^[^=]*=/, ""); value=$0 } END { print value }' "${ENV_FILE}")"
  if [[ -z "${value}" ]]; then
    echo "${key} is missing in ${ENV_FILE}" >&2
    exit 1
  fi
  case "${value}" in
    change-me|change-me-*|replace-me|replace-me-*)
      echo "${key} still contains an example value; refuse to start" >&2
      exit 1
      ;;
  esac
done

audiobookshelf_enabled="$(awk -F= '$1 == "AUDIOBOOKSHELF_ENABLED" { sub(/^[^=]*=/, ""); value=$0 } END { print value }' "${ENV_FILE}")"
if [[ "${audiobookshelf_enabled:-true}" == "true" ]]; then
  for key in AUDIOBOOKSHELF_BASE_URL AUDIOBOOKSHELF_LIBRARY_ID AUDIOBOOKSHELF_API_KEY; do
    value="$(awk -F= -v key="${key}" '$1 == key { sub(/^[^=]*=/, ""); value=$0 } END { print value }' "${ENV_FILE}")"
    if [[ -z "${value}" ]]; then
      echo "${key} is required when AUDIOBOOKSHELF_ENABLED=true" >&2
      exit 1
    fi
    case "${value}" in
      change-me|change-me-*|replace-me|replace-me-*)
        echo "${key} still contains an example value; refuse to start" >&2
        exit 1
        ;;
    esac
  done
fi

cd "${ROOT_DIR}"
exec docker compose --env-file "${ENV_FILE}" -f "${COMPOSE_FILE}" up -d --build "$@"
