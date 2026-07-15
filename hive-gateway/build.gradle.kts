plugins {
    application
}

dependencies {
    implementation(project(":hive-core"))
    implementation(project(":hive-protocol"))
    implementation(project(":hive-wire"))

    // Agent enrollment: parse the agent's PKCS#10 CSR and sign a leaf cert with the file-backed CA.
    implementation(libs.bcpkix)

    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    // Health + metrics, served on a SEPARATE management port that no deployment publishes (see
    // application.properties). Without this there is no way for an orchestrator to know the gateway is up.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    // Email alert delivery (JavaMailSender / SMTP). Webhook delivery reuses the web starter's RestTemplate.
    implementation("org.springframework.boot:spring-boot-starter-mail")
    // Persistence (active only under the 'postgres' profile)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    // Spring Boot 4 moved Flyway auto-configuration out of spring-boot-autoconfigure into its own module;
    // flyway-core alone no longer wires it up, so the 'postgres' profile would silently skip migrations.
    runtimeOnly("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.springframework.boot:spring-boot-starter-logging")
    // OIDC: the gateway validates Keycloak JWTs as a Resource Server (active only under the 'oidc' profile).
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 split the test slices out of the core test starter: @WebMvcTest / @AutoConfigureMockMvc now
    // live in the webmvc-test module, and TestRestTemplate in the resttestclient module (with restclient at
    // runtime). Without these the controller slice tests and SetupIT no longer compile.
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testRuntimeOnly("org.springframework.boot:spring-boot-restclient")
    testImplementation("org.springframework.security:spring-security-test")
    // Real-Postgres integration tests (RLS, the SECURITY DEFINER fn, JIT, cross-tenant FKs). Testcontainers 2.0
    // (managed by the Spring Boot 4 BOM) renamed its modules with a testcontainers- prefix. The tests self-skip
    // when no container engine is available.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.hivekeeper.gateway.HiveGatewayApplication")
    applicationName = "hive-gateway"
}
