# HiveKeeper agent image. Build from the repo ROOT:
#   docker build -f hive-agent/Dockerfile -t hivekeeper-agent .
#
# The agent dials OUT to the gateway (no inbound ports). Configure via env (see AgentConfig).

# --- build stage ---------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon :hive-agent:installDist -x test

# --- runtime stage -------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/hive-agent/build/install/hive-agent/ /app/
ENV HIVEKEEPER_GATEWAY_URL=ws://gateway:8090/agent \
    HIVEKEEPER_AGENT_ID=hive-agent \
    HIVEKEEPER_DEFAULT_USER=admin \
    HIVEKEEPER_DEFAULT_PASSWORD="" \
    HIVEKEEPER_BACKUP_DIR=/data/backups
VOLUME ["/data"]
ENTRYPOINT ["/app/bin/hive-agent"]
