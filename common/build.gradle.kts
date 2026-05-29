plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test { useJUnitPlatform() }
