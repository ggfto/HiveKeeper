allprojects {
    group = "io.hivekeeper"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    configure<JavaPluginExtension> {
        toolchain {
            // Project compiles/tests on JDK 21 (LTS) for contributor accessibility,
            // even though the Gradle daemon runs on the locally-installed JDK 25.
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    // Lombok: used judiciously — @Slf4j for loggers and @Builder for wide value types.
    // Records already cover getter/equals/hashCode/toString/constructor, so we do not use @Value/@Data.
    dependencies {
        "compileOnly"("org.projectlombok:lombok:1.18.38")
        "annotationProcessor"("org.projectlombok:lombok:1.18.38")
        "testCompileOnly"("org.projectlombok:lombok:1.18.38")
        "testAnnotationProcessor"("org.projectlombok:lombok:1.18.38")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        finalizedBy("jacocoTestReport")
    }
}
