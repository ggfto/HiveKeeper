plugins {
    application
}

dependencies {
    implementation(project(":hive-core"))
    implementation(project(":hive-protocol"))
    implementation(project(":hive-wire"))

    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.1"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    // Persistence (active only under the 'postgres' profile)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.springframework.boot:spring-boot-starter-logging")
    // OIDC: the gateway validates Keycloak JWTs as a Resource Server (active only under the 'oidc' profile).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.hivekeeper.gateway.HiveGatewayApplication")
    applicationName = "hive-gateway"
}
