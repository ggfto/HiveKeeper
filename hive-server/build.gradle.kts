plugins {
    application
}

dependencies {
    implementation(project(":hive-core"))
    implementation(project(":hive-wire"))

    // Spring Boot via its BOM only (no Spring Boot Gradle plugin) — keeps us clear of plugin/Gradle 9
    // compatibility issues. We run via the `application` plugin instead of bootRun.
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.0"))
    // Override Tomcat version to fix CVE-2026-65182 (security constraint bypass)
    // Spring Boot 4.1.0 ships with Tomcat 11.0.22; 11.0.25 contains the fix.
    constraints {
        implementation("org.apache.tomcat.embed:tomcat-embed-core:11.0.25")
    }
    implementation("org.springframework.boot:spring-boot-starter-web")
    runtimeOnly("org.springframework.boot:spring-boot-starter-logging")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.hivekeeper.server.HiveServerApplication")
    applicationName = "hive-server"
}
