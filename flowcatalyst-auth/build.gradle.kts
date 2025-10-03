plugins {
    java
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    // Quarkus BOM
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))

    // Core Quarkus
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-hibernate-orm-panache")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")

    // Security
    implementation("io.quarkus:quarkus-security")
    implementation("io.quarkus:quarkus-elytron-security-common")

    // OIDC (optional - can be disabled via config)
    implementation("io.quarkus:quarkus-oidc")

    // REST endpoints for auth
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")

    // Validation
    implementation("io.quarkus:quarkus-hibernate-validator")

    // Password hashing - using Quarkus built-in Elytron
    // For Argon2: org.bouncycastle:bcprov-jdk18on can be added if needed

    // Caching for session/token management
    implementation("io.quarkus:quarkus-cache")

    // TSID for primary keys
    implementation("com.github.f4b6a3:tsid-creator:5.2.6")

    // Testing
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.testcontainers:postgresql:1.19.7")
    testImplementation("io.quarkus:quarkus-test-common")
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
