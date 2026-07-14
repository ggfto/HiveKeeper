# HiveKeeper gateway image (cloud control plane). Build from the repo ROOT:
#   docker build -f hive-gateway/Dockerfile -t hivekeeper-gateway .
#
# Container-only, like the server. Agents connect to /agent over WebSocket; operators hit the REST API.
# Terminate TLS in front of this for the browser-facing API (see docker-compose.prod.yml) — but NOT for the
# agent's mTLS port, which must reach the gateway unterminated or client-certificate auth cannot work.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon :hive-gateway:installDist -x test

FROM eclipse-temurin:21-jre
# curl, for the healthcheck below. The JRE image ships without it.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/hive-gateway/build/install/hive-gateway/ /app/
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8090
EXPOSE 8090

# Health and metrics live on their own port, which no compose file publishes — they are for the orchestrator and
# the monitoring stack on the internal network, never for the internet.
EXPOSE 9091
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS http://127.0.0.1:9091/actuator/health || exit 1

ENTRYPOINT ["/app/bin/hive-gateway"]
