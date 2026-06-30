plugins {
    application
}

dependencies {
    implementation(project(":hive-core"))
    implementation(project(":hive-protocol"))
    implementation(project(":hive-wire"))

    // Agent enrollment: parse the agent's PKCS#10 CSR and sign a leaf cert with the file-backed CA.
    implementation(libs.bcpkix)

    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.4.1"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    // Email alert delivery (JavaMailSender / SMTP). Webhook delivery reuses the web starter's RestTemplate.
    implementation("org.springframework.boot:spring-boot-starter-mail")
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
    // Real-Postgres integration tests (RLS, the SECURITY DEFINER fn, JIT, cross-tenant FKs). Versions come
    // from the Spring Boot BOM above. The tests self-skip when no container engine is available.
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.hivekeeper.gateway.HiveGatewayApplication")
    applicationName = "hive-gateway"
}
