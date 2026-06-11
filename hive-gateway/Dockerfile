# HiveKeeper gateway image (cloud control plane). Build from the repo ROOT:
#   docker build -f hive-gateway/Dockerfile -t hivekeeper-gateway .
#
# Container-only, like the server. Agents connect to /agent over WebSocket; operators hit the REST API.
# Put TLS + auth + multi-tenancy in front of this.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon :hive-gateway:installDist -x test

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/hive-gateway/build/install/hive-gateway/ /app/
ENV SERVER_ADDRESS=0.0.0.0 \
    SERVER_PORT=8090
EXPOSE 8090
ENTRYPOINT ["/app/bin/hive-gateway"]
