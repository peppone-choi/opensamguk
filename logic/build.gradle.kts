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

// 입력 원장은 저장소 루트의 JSON 하나가 정본이다(game-api 의 public-alpha 카탈로그와 같은 방식).
tasks.processResources {
    from(rootProject.file("data/commands/hwiha-input-catalog.json")) {
        into("command-catalog")
    }
}

tasks.test { useJUnitPlatform() }
