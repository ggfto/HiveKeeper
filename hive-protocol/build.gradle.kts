dependencies {
    // Exposes the engine + DTOs to consumers (gateway/agent), since the protocol carries them verbatim.
    api(project(":hive-core"))
    implementation(libs.slf4j.api)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
