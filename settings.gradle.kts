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

// Core Platform Modules (Java/Quarkus)
include("core:flowcatalyst-auth")
include("core:flowcatalyst-message-router")
include("core:flowcatalyst-core")
include("core:flowcatalyst-router-app")
include("core:flowcatalyst-app")

// BFFE (Backend For Frontend)
include("core:flowcatalyst-bffe")