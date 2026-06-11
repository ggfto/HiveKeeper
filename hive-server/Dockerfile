# HiveKeeper server image. Build from the repo ROOT:
#   docker build -f hive-server/Dockerfile -t hivekeeper-server .
#
# The server is distributed as a container only. It binds 0.0.0.0 inside the container (the container
# network isolates it); map the port and put it behind your own TLS/ingress.

# --- build stage ---------------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
# The running JVM (21) satisfies the Java 21 toolchain via Gradle's current-JVM detection,
# so the Windows-specific installations.paths in gradle.properties are simply ignored here.
RUN chmod +x ./gradlew && ./gradlew --no-daemon :hive-server:installDist -x test

# --- runtime stage -------------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/hive-server/build/install/hive-server/ /app/
# Inside the container, bind all interfaces; override at deploy time as needed.
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8080
EXPOSE 8080
ENTRYPOINT ["/app/bin/hive-server"]
