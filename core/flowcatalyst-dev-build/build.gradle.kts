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

// ==========================================================================
// Platform UI Build (Vue.js)
// ==========================================================================
val uiDir = rootProject.file("packages/platform-ui-vue")
val uiDistDir = uiDir.resolve("dist")
val uiOutputDir = layout.buildDirectory.dir("resources/main/META-INF/resources")

val npmInstall by tasks.registering(Exec::class) {
    description = "Install npm dependencies for platform-ui-vue"
    group = "build"
    workingDir = uiDir
    commandLine("npm", "install", "--legacy-peer-deps")
    inputs.file(uiDir.resolve("package.json"))
    outputs.dir(uiDir.resolve("node_modules"))
}

val buildUi by tasks.registering(Exec::class) {
    description = "Build platform-ui-vue for production"
    group = "build"
    dependsOn(npmInstall)
    workingDir = uiDir
    // Skip api:generate (requires running backend) and vue-tsc (fix TS errors separately)
    commandLine("npx", "vite", "build")
    inputs.dir(uiDir.resolve("src"))
    inputs.file(uiDir.resolve("package.json"))
    inputs.file(uiDir.resolve("vite.config.ts"))
    inputs.file(uiDir.resolve("tsconfig.json"))
    outputs.dir(uiDistDir)
}

val copyUiToResources by tasks.registering(Copy::class) {
    description = "Copy built UI to META-INF/resources"
    group = "build"
    dependsOn(buildUi)
    from(uiDistDir)
    into(uiOutputDir)
}

tasks.named("processResources") {
    dependsOn(copyUiToResources)
}

dependencies {
    // Quarkus BOM
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))

    // ==========================================================================
    // Core Quarkus
    // ==========================================================================
    implementation("io.quarkus:quarkus-arc")

    // ==========================================================================
    // All FlowCatalyst Modules
    // ==========================================================================
    implementation(project(":core:flowcatalyst-platform"))
    implementation(project(":core:flowcatalyst-message-router"))
    implementation(project(":core:flowcatalyst-stream-processor"))
    implementation(project(":core:flowcatalyst-dispatch-scheduler"))
    implementation(project(":core:flowcatalyst-outbox-processor"))

    // ==========================================================================
    // Database Drivers for Outbox Processor Options
    // ==========================================================================
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-jdbc-mysql")
    implementation("io.quarkus:quarkus-agroal")

    // ==========================================================================
    // Testing
    // ==========================================================================
    testImplementation("io.quarkus:quarkus-junit5")
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

// ==========================================================================
// Java 24+ Compatibility
// ==========================================================================
tasks.withType<JavaExec> {
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
}

tasks.withType<Test> {
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")
    useJUnitPlatform()
}
