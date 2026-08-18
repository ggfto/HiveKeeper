# HiveKeeper agent image. Build from the repo ROOT:
#   docker build -f hive-agent/Dockerfile -t hivekeeper-agent .
#
# The agent dials OUT to the gateway and never opens an inbound port. Configure it by bind-mounting your own
# /etc/hivekeeper/agent.conf (see hive-agent/agent.conf.example) or with HIVEKEEPER_* environment variables,
# which override the file.

# --- build stage ---------------------------------------------------------------
FROM --platform=$BUILDPLATFORM eclipse-temurin:26-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon :hive-agent:installDist -x test

# --- runtime stage -------------------------------------------------------------
FROM eclipse-temurin:26-jre
# Patch the base image's OS packages — a JRE base drifts behind the distro's security fixes between its own
# rebuilds, and the CI image scan fails the build on a fixable CRITICAL/HIGH.
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/* \
    # Drop pebble: Canonical's service manager, bundled in the temurin base, unused here (the ENTRYPOINT runs
    # the app directly), and a static Go binary apt cannot patch — so it would keep failing the CVE scan.
    && rm -f /usr/bin/pebble \
    # An unprivileged account to run as. The agent holds the client certificate that authenticates it to the
    # gateway and the credentials for every AP it manages; uid 0 buys it nothing.
    && groupadd --system --gid 1001 hivekeeper \
    && useradd --system --uid 1001 --gid 1001 --home-dir /app --shell /usr/sbin/nologin hivekeeper
WORKDIR /app
# Root-owned, so the account below cannot rewrite the application it is running.
COPY --from=build /src/hive-agent/build/install/hive-agent/ /app/

# The container's defaults. A FILE and not ENV lines, because the environment overrides the config file: a
# baked-in `ENV HIVEKEEPER_GATEWAY_URL=...` would quietly beat the agent.conf an operator mounts, at the one
# setting they most need to own. Mounting a file over this one replaces it wholesale.
COPY hive-agent/docker/agent.conf /etc/hivekeeper/agent.conf

# Created and chowned BEFORE the VOLUME instruction on purpose: Docker seeds a NEW named volume from the
# image's content and ownership at this path, so a fresh `agent-data` comes up owned by the agent.
#
# UPGRADING AN EXISTING DEPLOYMENT: a volume that already exists is NOT re-seeded — it stays root-owned, and
# the agent will fail to write backups to it. Once, on the host, before starting the new image:
#   docker run --rm -v agent-data:/data alpine chown -R 1001:1001 /data
RUN mkdir -p /data && chown hivekeeper:hivekeeper /data
VOLUME ["/data"]

# Several settings default to RELATIVE paths — known.hosts ("hivekeeper-known_hosts") and the backup
# destination among them — which resolve against the working directory. That used to be /app, so the pinned
# SSH host keys were written into the application directory and thrown away on every container recreate:
# with the default `tofu` policy, the agent re-trusted whatever answered on the next connection. Pointing the
# working directory at the volume fixes that and is also what lets the unprivileged user below write at all.
WORKDIR /data

# Healthy means CONNECTED TO THE GATEWAY, not "the process is alive": the agent refreshes the health file every
# 15s while its uplink is up and deletes it when the uplink drops. An agent that cannot reach its gateway can do
# nothing, and a check that cannot tell those two apart reports green while the fleet is unmanageable.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD test -f /tmp/hivekeeper-agent.health \
        && [ $(( $(date +%s) - $(stat -c %Y /tmp/hivekeeper-agent.health) )) -lt 60 ]

# The agent dials out and never binds a port, so it needs no privileged capability at all.
USER hivekeeper
ENTRYPOINT ["/app/bin/hive-agent"]
