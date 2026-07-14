# HiveKeeper agent image. Build from the repo ROOT:
#   docker build -f hive-agent/Dockerfile -t hivekeeper-agent .
#
# The agent dials OUT to the gateway and never opens an inbound port. Configure it by bind-mounting your own
# /etc/hivekeeper/agent.conf (see hive-agent/agent.conf.example) or with HIVEKEEPER_* environment variables,
# which override the file.

# --- build stage ---------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon :hive-agent:installDist -x test

# --- runtime stage -------------------------------------------------------------
FROM eclipse-temurin:21-jre
# Patch the base image's OS packages — a JRE base drifts behind the distro's security fixes between its own
# rebuilds, and the CI image scan fails the build on a fixable CRITICAL/HIGH.
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /src/hive-agent/build/install/hive-agent/ /app/

# The container's defaults. A FILE and not ENV lines, because the environment overrides the config file: a
# baked-in `ENV HIVEKEEPER_GATEWAY_URL=...` would quietly beat the agent.conf an operator mounts, at the one
# setting they most need to own. Mounting a file over this one replaces it wholesale.
COPY hive-agent/docker/agent.conf /etc/hivekeeper/agent.conf

VOLUME ["/data"]

# Healthy means CONNECTED TO THE GATEWAY, not "the process is alive": the agent refreshes the health file every
# 15s while its uplink is up and deletes it when the uplink drops. An agent that cannot reach its gateway can do
# nothing, and a check that cannot tell those two apart reports green while the fleet is unmanageable.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD test -f /tmp/hivekeeper-agent.health \
        && [ $(( $(date +%s) - $(stat -c %Y /tmp/hivekeeper-agent.health) )) -lt 60 ]

ENTRYPOINT ["/app/bin/hive-agent"]
