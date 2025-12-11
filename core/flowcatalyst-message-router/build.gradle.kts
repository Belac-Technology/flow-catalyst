plugins {
    java
    id("io.quarkus")
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
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:quarkus-amazon-services-bom:${quarkusPlatformVersion}"))

    // Shared standby module
    implementation(project(":core:flowcatalyst-standby"))

    // REST API
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-jackson")
    implementation("io.quarkus:quarkus-arc")

    // Security
    implementation("io.quarkus:quarkus-security")
    implementation("io.quarkus:quarkus-oidc")

    // Hot Standby (optional, only loaded if standby.enabled=true)
    // Using Redisson for proper distributed lock support with clean API
    implementation("org.redisson:redisson-quarkus-30:3.40.2")

    // Traffic Management - AWS ALB integration (optional, only loaded if traffic-management.strategy=aws-alb)
    implementation("software.amazon.awssdk:elasticloadbalancingv2")

    // Health checks
    implementation("io.quarkus:quarkus-smallrye-health")

    // Message Queues
    implementation("io.quarkiverse.amazonservices:quarkus-amazon-sqs")
    implementation("software.amazon.awssdk:url-connection-client") // Required by Quarkus extension for default sync client
    implementation("software.amazon.awssdk:apache-client") // Used for SYNC mode with HTTP/2 support (configured in application.properties)
    implementation("software.amazon.awssdk:netty-nio-client") // Used for ASYNC mode (if enabled)
    implementation("org.apache.activemq:activemq-client:6.1.7")

    // Embedded Queue (for developer builds) - Pure Java SQLite for native image support
    implementation("io.quarkiverse.jdbc:quarkus-jdbc-sqlite4j:0.0.5")
    implementation("io.quarkus:quarkus-agroal") // DataSource/connection pool support

    // Resilience
    implementation("io.quarkus:quarkus-smallrye-fault-tolerance")
    implementation("io.github.resilience4j:resilience4j-ratelimiter:${resilience4jVersion}")

    // Observability
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-core")
    implementation("io.quarkus:quarkus-logging-json")

    // Scheduling
    implementation("io.quarkus:quarkus-scheduler")

    // Notifications
    implementation("io.quarkus:quarkus-mailer")

    // OpenAPI
    implementation("io.quarkus:quarkus-smallrye-openapi")

    // Container Image
    implementation("io.quarkus:quarkus-container-image-jib")

    // Caching
    implementation("io.quarkus:quarkus-cache")

    // Validation
    implementation("io.quarkus:quarkus-hibernate-validator")

    // Testing
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.awaitility:awaitility:4.2.0")
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:localstack:1.19.7")
    testImplementation("io.quarkus:quarkus-test-common")
    testImplementation("org.wiremock:wiremock:3.3.1")
}

group = "tech.flowcatalyst"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Unit tests (no @QuarkusTest)
val unitTest = tasks.test.get().apply {
    useJUnitPlatform {
        excludeTags("integration")
    }

    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")

    // Disable parallel execution - @QuarkusTest classes share ports and can't run in parallel
    maxParallelForks = 1
    systemProperty("junit.jupiter.execution.parallel.enabled", "false")
}

// Integration tests
val integrationTest by tasks.registering(Test::class) {
    description = "Runs integration tests for message router"
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

// Jib Docker image configuration
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
