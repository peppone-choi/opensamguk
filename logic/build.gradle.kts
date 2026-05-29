plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":common"))
    // EventCodec (FE2) parses the event-table `condition`/`action` jsonb wire via the kotlinx JSON
    // RUNTIME (parseToJsonElement / JsonElement traversal) — NO @Serializable codegen, so the
    // serialization compiler plugin is NOT required, only the runtime library.
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
