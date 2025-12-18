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

    // Core Quarkus (needed for startup/shutdown hooks)
    implementation("io.quarkus:quarkus-arc")

    // Include all modules for full-stack deployment
    implementation(project(":core:flowcatalyst-platform"))
    implementation(project(":core:flowcatalyst-message-router"))
    implementation(project(":core:flowcatalyst-stream-processor"))
    implementation(project(":core:flowcatalyst-dispatch-scheduler"))
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
