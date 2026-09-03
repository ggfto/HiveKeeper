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
    // Override Tomcat version to fix CVE-2026-65182 (security constraint bypass)
    // Spring Boot 4.1.0 ships with Tomcat 11.0.22; 11.0.25 contains the fix.
    // This override can be removed when Spring Boot BOM is updated to include 11.0.25+
    constraints {
        implementation("org.apache.tomcat.embed:tomcat-embed-core:11.0.25")
        implementation("org.apache.tomcat.embed:tomcat-embed-websocket:11.0.25")
    }
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
    // Pinned ahead of the Spring Boot 4.1.0 BOM (which still ships 42.7.11): CVE-2026-54291 is a
    // SCRAM-SHA-256-PLUS downgrade / MITM-protection bypass, fixed in 42.7.12. Gradle resolves to the higher
    // version, so this also lifts the copy pulled transitively by flyway-database-postgresql.
    runtimeOnly("org.postgresql:postgresql:42.7.13")
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

// The console's "download install bundle" ships the operator a ready-to-run agent install, so the gateway has
// to carry the agent's compose + env template inside its jar. They are COPIED from deploy/portainer at build
// time rather than duplicated into resources: one source of truth, so a change to the real compose cannot
// silently leave the generated bundle behind. The bundle also pins HIVEKEEPER_TAG to the gateway's own version
// (agent and gateway must match), which is why the version is baked in as a property here.
tasks.named<ProcessResources>("processResources") {
    val agentDeploy = rootProject.layout.projectDirectory.dir("deploy/portainer")
    inputs.dir(agentDeploy)
    from(agentDeploy.file("agent-compose.yml")) {
        into("agent-install")
        rename { "docker-compose.yml" }
    }
    from(agentDeploy.file("agent.env.example")) {
        into("agent-install")
        rename { "env.template" }
    }
    val projectVersion = project.version.toString()
    inputs.property("projectVersion", projectVersion)
    doLast {
        destinationDir.resolve("agent-install/build.properties")
            .writeText("version=$projectVersion\n")
    }
}
