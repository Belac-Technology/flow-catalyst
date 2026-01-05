plugins {
    java
    id("io.quarkus")
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))

    // Shared standby module
    implementation(project(":core:flowcatalyst-standby"))

    // Core Quarkus
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-smallrye-health")

    // MongoDB for change streams and projection writes
    implementation("io.quarkus:quarkus-mongodb-client")

    // Redis for checkpoints - native image compatible
    implementation("io.quarkus:quarkus-redis-client")

    // Observability
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-core")

    // Logging
    implementation("io.quarkus:quarkus-logging-json")

    // Testing
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("org.testcontainers:testcontainers:1.19.7")
    testImplementation("org.testcontainers:mongodb:1.19.7")
}

group = "tech.flowcatalyst"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}
