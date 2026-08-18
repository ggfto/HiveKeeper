# HiveKeeper server image. Build from the repo ROOT:
#   docker build -f hive-server/Dockerfile -t hivekeeper-server .
#
# The server is distributed as a container only. It binds 0.0.0.0 inside the container (the container
# network isolates it); map the port and put it behind your own TLS/ingress.

# --- build stage ---------------------------------------------------------------
FROM --platform=$BUILDPLATFORM eclipse-temurin:26-jdk AS build
WORKDIR /src
COPY . .
# The running JVM (21) satisfies the Java 21 toolchain via Gradle's current-JVM detection,
# so the Windows-specific installations.paths in gradle.properties are simply ignored here.
RUN chmod +x ./gradlew && ./gradlew --no-daemon :hive-server:installDist -x test

# --- runtime stage -------------------------------------------------------------
FROM eclipse-temurin:26-jre
# Patch the base image's OS packages — a JRE base drifts behind the distro's security fixes between its own
# rebuilds, and the CI image scan fails the build on a fixable CRITICAL/HIGH.
RUN apt-get update && apt-get upgrade -y && rm -rf /var/lib/apt/lists/* \
    # Drop pebble: Canonical's service manager, bundled in the temurin base, unused here (the ENTRYPOINT runs
    # the app directly), and a static Go binary apt cannot patch — so it would keep failing the CVE scan.
    && rm -f /usr/bin/pebble \
    # An unprivileged account to run as: this process opens SSH sessions to APs using operator-supplied
    # credentials, which is not work that needs uid 0.
    && groupadd --system --gid 1001 hivekeeper \
    && useradd --system --uid 1001 --gid 1001 --home-dir /app --shell /usr/sbin/nologin hivekeeper
WORKDIR /app
# Root-owned, so the account below cannot rewrite the application it is running.
COPY --from=build /src/hive-server/build/install/hive-server/ /app/

# The server resolves its default backup directory ("hivekeeper-backups", and any relative `dir` a request
# asks for) against the WORKING DIRECTORY. That used to be /app, which meant device configuration backups
# were written into the application directory and vanished with the container. Point it at a declared volume
# owned by the app user instead: backups survive a recreate, and a traversal in a backup path lands in a data
# volume rather than on top of the binaries.
RUN mkdir -p /data && chown hivekeeper:hivekeeper /data
VOLUME ["/data"]
WORKDIR /data

# Inside the container, bind all interfaces; override at deploy time as needed.
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080
EXPOSE 8080
USER hivekeeper
ENTRYPOINT ["/app/bin/hive-server"]
