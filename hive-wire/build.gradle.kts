dependencies {
    // hive-wire owns ALL serialization of the core DTOs and the protocol frames.
    api(project(":hive-core"))
    implementation(project(":hive-protocol"))
    implementation(libs.jackson.databind)
    implementation(libs.jackson.jsr310)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
