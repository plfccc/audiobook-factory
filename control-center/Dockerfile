FROM maven:3.9.9-eclipse-temurin-17 AS build

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ARG MAVEN_OPTS
ARG MAVEN_PROXY_HOST
ARG MAVEN_PROXY_PORT

ENV HTTP_PROXY=${HTTP_PROXY} \
    HTTPS_PROXY=${HTTPS_PROXY} \
    NO_PROXY=${NO_PROXY} \
    MAVEN_OPTS=${MAVEN_OPTS}

WORKDIR /workspace
COPY pom.xml ./
RUN mkdir -p /root/.m2 \
    && if [ -n "${MAVEN_PROXY_HOST}" ] && [ -n "${MAVEN_PROXY_PORT}" ]; then \
        printf '%s\n' \
          '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 https://maven.apache.org/xsd/settings-1.0.0.xsd">' \
          '  <proxies>' \
          '    <proxy>' \
          '      <id>build-proxy</id>' \
          '      <active>true</active>' \
          '      <protocol>http</protocol>' \
          "      <host>${MAVEN_PROXY_HOST}</host>" \
          "      <port>${MAVEN_PROXY_PORT}</port>" \
          '    </proxy>' \
          '  </proxies>' \
          '</settings>' > /root/.m2/settings.xml; \
      else \
        printf '%s\n' '<settings xmlns="http://maven.apache.org/SETTINGS/1.0.0"></settings>' > /root/.m2/settings.xml; \
      fi
COPY src ./src
RUN /usr/share/maven/bin/mvn -s /root/.m2/settings.xml -B -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

RUN sed -i \
        -e 's|archive.ubuntu.com/ubuntu|mirrors.aliyun.com/ubuntu|g' \
        -e 's|security.ubuntu.com/ubuntu|mirrors.aliyun.com/ubuntu|g' \
        /etc/apt/sources.list \
    && HTTP_PROXY="${HTTP_PROXY}" HTTPS_PROXY="${HTTPS_PROXY}" NO_PROXY="${NO_PROXY}" \
        apt-get -o Acquire::Retries=5 update \
    && HTTP_PROXY="${HTTP_PROXY}" HTTPS_PROXY="${HTTPS_PROXY}" NO_PROXY="${NO_PROXY}" \
        apt-get -o Acquire::Retries=5 install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --home-dir /home/audiobook \
        --shell /usr/sbin/nologin audiobook

WORKDIR /app
COPY --from=build /workspace/target/control-center-0.1.0-SNAPSHOT.jar /app/app.jar

RUN mkdir -p /data \
    && chown -R audiobook:audiobook /app /data

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8"

USER audiobook
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=25s --retries=6 \
    CMD curl --silent --fail http://127.0.0.1:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
