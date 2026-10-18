# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# OpenCanard server image
#
#   docker build -t opencanard .
#   docker run -d --name opencanard -p 8815:8815 -p 8084:8084 \
#       -v opencanard-data:/opt/opencanard/data \
#       -v opencanard-metadata:/opt/opencanard/metadata \
#       opencanard
# ---------------------------------------------------------------------------

# ---- Build stage: compile and assemble the server distribution ------------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

COPY . .

# The Maven cache is kept between builds (needs BuildKit, the default in current Docker)
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests -pl opencanard-server -am package

RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir /dist \
    && unzip -q opencanard-server/target/opencanard.zip -d /dist


# ---- Runtime stage ---------------------------------------------------------
# A glibc-based image is required: DuckDB and Arrow ship native libraries
# that do not work on Alpine (musl).
FROM eclipse-temurin:17-jre

RUN groupadd --system --gid 10001 opencanard \
    && useradd --system --uid 10001 --gid opencanard \
               --home-dir /opt/opencanard --shell /usr/sbin/nologin opencanard

COPY --from=build /dist/opencanard /opt/opencanard

RUN mkdir -p /opt/opencanard/data /opt/opencanard/metadata /opt/opencanard/logs \
    && chmod +x /opt/opencanard/bin/start.sh \
    && chown -R opencanard:opencanard /opt/opencanard

# DuckDB stores its extensions (the "arrow" extension is downloaded on first
# start) under $HOME/.duckdb
ENV HOME=/opt/opencanard \
    OPENCANARD_XMS=512m \
    OPENCANARD_XMX=2g

WORKDIR /opt/opencanard
USER opencanard

# 8815: Arrow Flight (gRPC)   8084: REST API
EXPOSE 8815 8084

VOLUME ["/opt/opencanard/data", "/opt/opencanard/metadata", "/opt/opencanard/logs"]

# Uses the default REST port; adjust it if you change opencanard.rest.port
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8084 && printf "GET /health HTTP/1.0\r\n\r\n" >&3 && head -n1 <&3 | grep -q " 200 "'

# start.sh ends with "exec java", so the JVM is PID 1 and receives SIGTERM from "docker stop"
ENTRYPOINT ["/opt/opencanard/bin/start.sh"]
