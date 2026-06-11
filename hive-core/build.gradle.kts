dependencies {
    // Transport: sshj is the primary SSH client (exec + interactive shell + SCP/SFTP).
    implementation(libs.sshj)
    // Git-backed backup store.
    implementation(libs.jgit)
    // Logging facade only (no binding here — adapters choose the binding).
    implementation(libs.slf4j.api)

    // Opt-in legacy-cipher fallback transport — uncomment when the jsch SshSession impl lands.
    // implementation(libs.jsch.mwiede)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
