FROM maven:3.9.9-eclipse-temurin-17 AS build

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY
ARG MAVEN_OPTS

ENV HTTP_PROXY=${HTTP_PROXY} \
    HTTPS_PROXY=${HTTPS_PROXY} \
    NO_PROXY=${NO_PROXY} \
    MAVEN_OPTS=${MAVEN_OPTS}

WORKDIR /workspace
COPY pom.xml ./
RUN /usr/share/maven/bin/mvn -B -DskipTests dependency:go-offline
COPY src ./src
RUN /usr/share/maven/bin/mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG NO_PROXY

SHELL ["/bin/bash", "-o", "pipefail", "-c"]

RUN HTTP_PROXY="${HTTP_PROXY}" HTTPS_PROXY="${HTTPS_PROXY}" NO_PROXY="${NO_PROXY}" apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
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
