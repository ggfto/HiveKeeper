plugins {
    application
}

dependencies {
    implementation(project(":hive-core"))
    implementation(project(":hive-protocol"))
    implementation(project(":hive-wire"))
    implementation(libs.java.websocket)
    implementation(libs.slf4j.api)
    // Enrollment bootstrap: generate a keypair + build a PKCS#10 CSR to request a signed client cert.
    implementation(libs.bcpkix)
    // Concrete logging binding for the agent runtime.
    runtimeOnly(libs.slf4j.simple)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    // The test spins up a stub WebSocket gateway with the same library.
    testImplementation(libs.java.websocket)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("io.hivekeeper.agent.AgentMain")
    applicationName = "hive-agent"
}
