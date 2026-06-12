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

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.hivekeeper.gateway.HiveGatewayApplication")
    applicationName = "hive-gateway"
}
