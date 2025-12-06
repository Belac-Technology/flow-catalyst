plugins {
    java
    id("io.quarkus")
    id("org.kordamp.gradle.jandex") version "2.0.0"
    id("com.google.cloud.tools.jib") version "3.4.0"
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project
val resilience4jVersion: String by project

dependencies {
    // Quarkus BOM
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:quarkus-amazon-services-bom:${quarkusPlatformVersion}"))

    // ==========================================================================
    // Core Quarkus
    // ==========================================================================
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-mongodb-panache")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    // ==========================================================================
    // Security
    // ==========================================================================
    implementation("io.quarkus:quarkus-security")
    implementation("io.quarkus:quarkus-elytron-security-common")
    implementation("io.quarkus:quarkus-smallrye-jwt")
    implementation("io.quarkus:quarkus-smallrye-jwt-build")

    // OIDC (optional - can be disabled via config)
    implementation("io.quarkus:quarkus-oidc")

    // REST client for external IDP calls
    implementation("io.quarkus:quarkus-rest-client-jackson")

    // ==========================================================================
    // OpenAPI & Validation
    // ==========================================================================
    implementation("io.quarkus:quarkus-smallrye-openapi")
    implementation("io.quarkus:quarkus-hibernate-validator")

    // ==========================================================================
    // Caching for session/token management
    // ==========================================================================
    implementation("io.quarkus:quarkus-cache")


    // ==========================================================================
    // Messaging (SQS for dispatch jobs)
    // ==========================================================================
    implementation("io.quarkiverse.amazonservices:quarkus-amazon-sqs")
    implementation("software.amazon.awssdk:url-connection-client")

    // ==========================================================================
    // Resilience & Fault Tolerance
    // ==========================================================================
    implementation("io.quarkus:quarkus-smallrye-fault-tolerance")
    implementation("io.github.resilience4j:resilience4j-ratelimiter:${resilience4jVersion}")

    // ==========================================================================
    // Scheduling
    // ==========================================================================
    implementation("io.quarkus:quarkus-scheduler")

    // ==========================================================================
    // Observability
    // ==========================================================================
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-core")
    implementation("io.quarkus:quarkus-logging-json")

    // ==========================================================================
    // Container Image
    // ==========================================================================
    implementation("io.quarkus:quarkus-container-image-jib")

    // ==========================================================================
    // TSID for primary keys
    // ==========================================================================
    implementation("com.github.f4b6a3:tsid-creator:5.2.6")


    // ==========================================================================
    // Testing
    // ==========================================================================
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:mongodb:1.19.7")
    testImplementation("io.quarkus:quarkus-test-common")
    testImplementation("org.assertj:assertj-core:3.24.2")
}

group = "tech.flowcatalyst"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// ==========================================================================
// Test Configuration
// ==========================================================================

// Unit tests (no @QuarkusTest)
val unitTest = tasks.test.get().apply {
    useJUnitPlatform {
        excludeTags("integration")
    }

    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")

    // Quarkus tests must run sequentially (they share the same port)
    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}

// Integration tests
val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests"
    group = "verification"

    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath

    useJUnitPlatform {
        includeTags("integration")
    }

    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")

    // Run integration tests sequentially
    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")

    shouldRunAfter(unitTest)
}

tasks.named("check") {
    dependsOn(integrationTest)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

// ==========================================================================
// Docker Image Configuration
// ==========================================================================

jib {
    from {
        image = "eclipse-temurin:21-jre-alpine"
    }
    to {
        image = "${System.getenv("DOCKER_REGISTRY") ?: "flowcatalyst"}/${project.name}:${project.version}"
        tags = setOf("latest")
    }
    container {
        mainClass = "io.quarkus.runner.GeneratedMain"
        jvmFlags = listOf(
            "-XX:+UseContainerSupport",
            "-XX:MaxRAMPercentage=75.0",
            "-Djava.security.egd=file:/dev/./urandom"
        )
        ports = listOf("8080")
        labels.put("maintainer", "flowcatalyst@example.com")
        labels.put("version", project.version.toString())
        creationTime.set("USE_CURRENT_TIMESTAMP")
        user = "1000:1000"
    }
}
