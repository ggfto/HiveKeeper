plugins {
    application
}

dependencies {
    implementation(project(":hive-core"))
    implementation(project(":hive-wire"))

    // Spring Boot via its BOM only (no Spring Boot Gradle plugin) — keeps us clear of plugin/Gradle 9
    // compatibility issues. We run via the `application` plugin instead of bootRun.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:3.5.16"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    runtimeOnly("org.springframework.boot:spring-boot-starter-logging")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.hivekeeper.server.HiveServerApplication")
    applicationName = "hive-server"
}
