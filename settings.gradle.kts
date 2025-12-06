pluginManagement {
    val quarkusPluginVersion: String by settings
    repositories {
        mavenCentral()
        gradlePluginPortal()
        mavenLocal()
    }
    plugins {
        id("io.quarkus") version quarkusPluginVersion
    }
}

rootProject.name = "flowcatalyst"

// =============================================================================
// Core Modules (Java/Quarkus)
// =============================================================================

// The main platform service (auth, admin, dispatch, mediation, event types)
include("core:flowcatalyst-platform")

// High-volume message pointer routing (scales independently)
include("core:flowcatalyst-message-router")

// SDK for integrating applications (postbox, events, roles)
include("core:flowcatalyst-sdk")

// Benchmarks
include("core:flowcatalyst-benchmark")

// =============================================================================
// Legacy Modules (TO BE REMOVED after migration verified)
// =============================================================================
include("core:flowcatalyst-postbox")
include("core:flowcatalyst-router-app")
include("core:flowcatalyst-app")
include("core:flowcatalyst-bffe")