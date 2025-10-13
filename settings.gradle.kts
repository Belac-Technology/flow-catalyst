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

// Library modules (reusable components)
include("flowcatalyst-auth")
include("flowcatalyst-message-router")
include("flowcatalyst-core")

// Application modules (runnable applications)
include("flowcatalyst-router-app")
include("flowcatalyst-app")