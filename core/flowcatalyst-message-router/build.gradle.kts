plugins {
    java
    id("io.quarkus")
    id("org.kordamp.gradle.jandex") version "2.0.0"
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

    // REST API
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-jackson")
    implementation("io.quarkus:quarkus-arc")

    // Message Queues
    implementation("io.quarkiverse.amazonservices:quarkus-amazon-sqs")
    implementation("software.amazon.awssdk:url-connection-client")
    implementation("software.amazon.awssdk:netty-nio-client") // Required for SqsAsyncClient
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

// Native image build tasks
tasks.register<Exec>("nativeBuild") {
    group = "build"
    description = "Builds a native executable (production - SQS/ActiveMQ)"
    commandLine("./gradlew", "build", "-Dquarkus.package.type=native", "-x", "test")
}

tasks.register<Exec>("nativeBuildDev") {
    group = "build"
    description = "Builds a native executable with embedded SQLite queue (developer build)"
    commandLine("./gradlew", "build", "-Dquarkus.package.type=native", "-x", "test")
}

tasks.register<Exec>("nativeTest") {
    group = "verification"
    description = "Tests the application in native mode"
    commandLine("./gradlew", "test", "-Dquarkus.package.type=native")
}
