plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":common"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.test { useJUnitPlatform() }
