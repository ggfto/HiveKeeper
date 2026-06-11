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
