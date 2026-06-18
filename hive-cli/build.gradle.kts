plugins {
    application
}

dependencies {
    implementation(project(":hive-core"))
    implementation(project(":hive-wire"))
    implementation(libs.picocli)
    // Concrete logging binding for the CLI runtime.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.hivekeeper.cli.Main")
    applicationName = "hivekeeper"
}

// Stamp the version into the jar manifest so `hivekeeper --version` reflects the released version
// (read at runtime via Main's IVersionProvider) instead of a hardcoded string.
tasks.jar {
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}
