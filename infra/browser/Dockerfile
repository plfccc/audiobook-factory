FROM debian:bookworm-slim

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

RUN apt-get update \
    && packages=(chromium xvfb x11vnc novnc supervisor curl ca-certificates fonts-noto-cjk) \
    && pinned_packages=() \
    && for package in "${packages[@]}"; do \
         version="$(apt-cache policy "${package}" | awk '/Candidate:/ { print $2 }')"; \
         test -n "${version}" && test "${version}" != "(none)"; \
         pinned_packages+=("${package}=${version}"); \
       done \
    && apt-get install -y --no-install-recommends "${pinned_packages[@]}" \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --create-home --shell /bin/bash browser

COPY supervisord.conf /etc/supervisor/conf.d/browser.conf
COPY entrypoint.sh /usr/local/bin/browser-entrypoint
COPY browser-runtime-policy.json /etc/chromium/policies/managed/browser-runtime.json
COPY download-smoke.html /usr/share/novnc/download-smoke.html

RUN chmod 0755 /usr/local/bin/browser-entrypoint

EXPOSE 6080 9222

ENTRYPOINT ["/usr/local/bin/browser-entrypoint"]
