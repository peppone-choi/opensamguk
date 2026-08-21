rootProject.name = "opensamguk"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

include("common", "logic", "infra")
include("app:gateway-api", "app:game-api", "app:game-engine", "app:board-api")
