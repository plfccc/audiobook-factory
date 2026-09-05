#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
test_root="$(mktemp -d)"
trap 'rm -rf -- "${test_root}"' EXIT

work_root="${test_root}/work"
fake_bin="${test_root}/bin"
downloads_dir="${work_root}/data/downloads"
sentinel_name=browser-runtime-smoke-download.txt

mkdir -p "${work_root}/infra/browser/tests" "${downloads_dir}" "${fake_bin}"
cp "${repo_root}/infra/browser/tests/test_runtime.sh" \
  "${work_root}/infra/browser/tests/test_runtime.sh"
cp "${repo_root}/infra/browser/download-smoke.html" \
  "${work_root}/infra/browser/download-smoke.html"
printf '%s' 'persisted-user-download' >"${downloads_dir}/${sentinel_name}"

fixture_name=browser-runtime-smoke-download-0123456789abcdef0123456789abcdef.txt
node - "${work_root}/infra/browser/download-smoke.html" "${fixture_name}" <<'NODE'
const fs = require('fs');
const vm = require('vm');

const html = fs.readFileSync(process.argv[2], 'utf8');
const expectedName = process.argv[3];
const script = html.match(/<script>([\s\S]*)<\/script>/)?.[1];
if (!script) throw new Error('download fixture script not found');

const initialDownload = html.match(/\bdownload="([^"]*)"/)?.[1] ?? '';
const anchor = {
  clicked: false,
  download: initialDownload,
  click() { this.clicked = true; },
};

global.document = { getElementById: () => anchor };
global.window = { location: { search: `?filename=${expectedName}` } };
vm.runInThisContext(script);

if (anchor.download !== expectedName) {
  throw new Error(`fixture used ${anchor.download || '(empty)'} instead of ${expectedName}`);
}
if (!anchor.clicked) throw new Error('fixture did not initiate the download');
NODE

cat >"${fake_bin}/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ " $* " == *" exec "* && " $* " == *" rm -f "* ]]; then
  container_path="${!#}"
  case "${container_path}" in
    /data/downloads/*)
      rm -f -- "${FAKE_DOWNLOADS_DIR}/${container_path##*/}"
      ;;
    *)
      exit 99
      ;;
  esac
elif [[ "$*" == *" port browser 6080"* ]]; then
  printf '%s\n' '127.0.0.1:6080'
elif [[ "$*" == *" port browser 9222"* ]]; then
  printf '%s\n' '127.0.0.1:9222'
fi
EOF

cat >"${fake_bin}/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

url="${!#}"
if [[ "${url}" == *'/json/new?'* ]]; then
  query="${url#*download-smoke.html}"
  if [[ "${query}" == '?filename='* ]]; then
    download_name="${query#?filename=}"
  else
    download_name=browser-runtime-smoke-download.txt
  fi
  printf '%s' 'browser-runtime-download' >"${FAKE_DOWNLOADS_DIR}/${download_name}"
fi
EOF

chmod +x "${fake_bin}/docker" "${fake_bin}/curl"

(
  cd "${work_root}"
  PATH="${fake_bin}:${PATH}" FAKE_DOWNLOADS_DIR="${downloads_dir}" \
    bash infra/browser/tests/test_runtime.sh
)

test "$(cat "${downloads_dir}/${sentinel_name}")" = 'persisted-user-download'

shopt -s nullglob
remaining_downloads=("${downloads_dir}"/*)
test "${#remaining_downloads[@]}" -eq 1
test "${remaining_downloads[0]}" = "${downloads_dir}/${sentinel_name}"

printf '%s\n' 'runtime smoke download safety: preserved pre-existing files and cleaned only its artifact'
