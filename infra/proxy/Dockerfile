FROM debian:bookworm-slim

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

RUN apt-get update \
    && packages=(ca-certificates curl) \
    && pinned_packages=() \
    && for package in "${packages[@]}"; do \
         version="$(apt-cache policy "${package}" | awk '/Candidate:/ { print $2 }')"; \
         test -n "${version}" && test "${version}" != "(none)"; \
         pinned_packages+=("${package}=${version}"); \
       done \
    && apt-get install -y --no-install-recommends "${pinned_packages[@]}" \
    && rm -rf /var/lib/apt/lists/*

ENTRYPOINT ["/usr/local/bin/mihomo", "-d", "/etc/mihomo"]
