plugins {
    // Resolves a JDK 21 toolchain (see build.gradle.kts) from any JDK the machine already has, and downloads one
    // when it has none. Without it, a contributor — or a CI runner — whose JDK is not where we happen to keep
    // ours simply cannot build the project.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hivekeeper"

include("hive-core", "hive-protocol", "hive-wire", "hive-cli", "hive-server", "hive-agent", "hive-gateway")
