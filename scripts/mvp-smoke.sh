#!/usr/bin/env bash
set -Eeuo pipefail

usage() {
  cat <<'USAGE'
用法：
  scripts/mvp-smoke.sh --base-url URL --epub PATH --access-token TOKEN

环境变量：
  MVP_SMOKE_POLL_SECONDS   轮询间隔，默认 5 秒
  MVP_SMOKE_TIMEOUT_SECONDS 总等待时间，默认 1800 秒
USAGE
}

BASE_URL=""
EPUB_PATH=""
ACCESS_TOKEN=""

while (($# > 0)); do
  case "$1" in
    --base-url)
      [[ $# -ge 2 ]] || { echo "缺少 --base-url 的值" >&2; exit 2; }
      BASE_URL="$2"
      shift 2
      ;;
    --epub)
      [[ $# -ge 2 ]] || { echo "缺少 --epub 的值" >&2; exit 2; }
      EPUB_PATH="$2"
      shift 2
      ;;
    --access-token)
      [[ $# -ge 2 ]] || { echo "缺少 --access-token 的值" >&2; exit 2; }
      ACCESS_TOKEN="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知参数：$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$BASE_URL" || -z "$EPUB_PATH" || -z "$ACCESS_TOKEN" ]]; then
  echo "--base-url、--epub、--access-token 都是必填项。" >&2
  usage >&2
  exit 2
fi
if [[ ! -f "$EPUB_PATH" ]]; then
  echo "EPUB 文件不存在：$EPUB_PATH" >&2
  exit 2
fi
if [[ "${EPUB_PATH,,}" != *.epub ]]; then
  echo "--epub 必须指向 .epub 文件。" >&2
  exit 2
fi
command -v curl >/dev/null || { echo "未找到 curl。" >&2; exit 2; }
command -v python3 >/dev/null || { echo "未找到 python3。" >&2; exit 2; }

BASE_URL="${BASE_URL%/}"
POLL_SECONDS="${MVP_SMOKE_POLL_SECONDS:-5}"
TIMEOUT_SECONDS="${MVP_SMOKE_TIMEOUT_SECONDS:-1800}"

json_field() {
  local field="$1"
  python3 -c 'import json, sys
field = sys.argv[1]
value = json.load(sys.stdin).get(field)
if value is None:
    raise SystemExit(1)
print(value)' "$field"
}

curl --silent --show-error --fail --location \
  --connect-timeout 15 --max-time 30 \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  "${BASE_URL}/actuator/health" >/dev/null
echo "控制中心健康检查通过。"

upload_response="$(curl --silent --show-error --fail --location \
  --connect-timeout 15 --max-time 120 \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Accept: application/json' \
  -F "file=@${EPUB_PATH};type=application/epub+zip" \
  "${BASE_URL}/api/v1/books")"
book_id="$(printf '%s' "$upload_response" | json_field bookId)" || {
  echo "导入接口未返回 bookId。" >&2
  exit 1
}
printf '已导入书籍：bookId=%s\n' "$book_id"

generation_request='{"chapterStart":1,"chapterEnd":1,"preset":{"provider":"qwen3-tts","engine":"qwen3-tts","model":"Qwen/Qwen3-TTS-12Hz-1.7B-Base","modelVersion":"1.0","voice":"default","language":"zh-CN","outputFormat":"wav"}}'
generation_response="$(curl --silent --show-error --fail --location \
  --connect-timeout 15 --max-time 60 \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json' \
  --data "$generation_request" \
  "${BASE_URL}/api/v1/books/${book_id}/generation")"
printf '已提交第一章生成：%s\n' "$(printf '%s' "$generation_response" | json_field status 2>/dev/null || printf 'accepted')"

deadline=$((SECONDS + TIMEOUT_SECONDS))
while ((SECONDS < deadline)); do
  progress="$(curl --silent --show-error --fail --location \
    --connect-timeout 15 --max-time 60 \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H 'Accept: application/json' \
    "${BASE_URL}/api/v1/books/${book_id}/progress")"
  completed="$(printf '%s' "$progress" | json_field completedChapters)"
  total="$(printf '%s' "$progress" | json_field totalChapters)"
  current="$(printf '%s' "$progress" | json_field currentChapter 2>/dev/null || printf 'none')"
  printf '进度：%s / %s 章；当前：%s\n' "$completed" "$total" "$current"
  if ((completed >= 1)); then
    break
  fi
  sleep "$POLL_SECONDS"
done

if ((SECONDS >= deadline)); then
  echo "等待第一章完成超时；请检查 Colab Worker、控制中心日志和异常中心。" >&2
  exit 1
fi

chapters="$(curl --silent --show-error --fail --location \
  --connect-timeout 15 --max-time 60 \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Accept: application/json' \
  "${BASE_URL}/api/v1/books/${book_id}/chapters")"
chapter_info="$(python3 -c 'import json, sys
chapters = json.load(sys.stdin)
chapter = next((item for item in chapters if item.get("chapterNumber") == 1), None)
if not chapter:
    raise SystemExit("未找到第一章")
status = chapter.get("status", "unknown")
audio_url = chapter.get("audioUrl") or ""
if status != "SUCCESS":
    raise SystemExit("第一章未成功：{}".format(status))
if not audio_url.startswith("/"):
    raise SystemExit("第一章音频地址不是相对 API 路径")
print("{}\\t{}".format(status, audio_url))' <<<"$chapters")"
IFS=$'\t' read -r chapter_status audio_url <<<"$chapter_info"
printf '第一章状态：%s\n音频地址：%s\n' "$chapter_status" "$audio_url"
audio_content_type="$(curl --silent --show-error --fail --location \
  --connect-timeout 15 --max-time 60 \
  -H "Authorization: Bearer ${ACCESS_TOKEN}" \
  -H 'Accept: audio/mpeg' \
  -o /dev/null -w '%{content_type}' \
  "${BASE_URL}${audio_url}")"
case "$audio_content_type" in
  audio/mpeg*) ;;
  *)
    echo "章节音频响应类型异常：${audio_content_type}" >&2
    exit 1
    ;;
esac
echo "MVP 单章烟囱测试通过。"
