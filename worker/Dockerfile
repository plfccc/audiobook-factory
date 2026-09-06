FROM debian:bookworm-slim

SHELL ["/bin/sh", "-euxc"]

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        ffmpeg \
        python3.11 \
        python3.11-venv \
    && rm -rf /var/lib/apt/lists/*

RUN python3.11 -m venv /opt/venv

ENV PATH="/opt/venv/bin:$PATH"
# 服务器默认无法稳定访问 pypi.org，使用可达的国内镜像完成构建。
ENV PIP_INDEX_URL="https://mirrors.aliyun.com/pypi/simple/"

WORKDIR /app

COPY pyproject.toml ./
COPY src ./src

RUN python -m pip install --no-cache-dir .

ENV PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

CMD ["python", "-m", "audiobook_worker.browser_worker"]
