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
    // 휘하 적성 가중식(게임 설계 수치) — 정본은 저장소 루트 파일 하나다.
    from(rootProject.file("data/curated/han/hwiha-aptitude-weights-v1.json")) {
        into("hwiha")
    }
    // 휘하 시야 반경·병력 구간·첩보 비용(2026-09-23 확정 수치) — 정본은 저장소 루트 파일 하나다.
    from(rootProject.file("data/curated/han/hwiha-vision-rules-v1.json")) {
        into("hwiha")
    }
    // 휘하 내정 입력(배치·방침·공사)의 2026-09-23 확정 수치 — 정본은 저장소 루트 파일 하나다.
    from(rootProject.file("data/curated/han/hwiha-domestic-v1.json")) {
        into("hwiha")
    }
    from(rootProject.file("data/curated/han/hwiha-military-v1.json")) {
        into("hwiha")
    }
    from(rootProject.file("data/curated/han/hwiha-personal-v1.json")) {
        into("hwiha")
    }
    from(rootProject.file("data/curated/han/hwiha-people-v1.json")) {
        into("hwiha")
    }
    from(rootProject.file("data/curated/han/hwiha-political-v1.json")) {
        into("hwiha")
    }
    // 보물 카드와 무제한 장비는 추출 원본에서 나눈 정본 원장을 그대로 싣는다.
    from(rootProject.file("data/curated/han/hwiha-treasure-cards-v1.json")) {
        into("hwiha")
    }
    from(rootProject.file("data/curated/han/hwiha-equipment-v1.json")) {
        into("hwiha")
    }
    from(rootProject.file("data/curated/han/hwiha-personal-encounter-v1.json")) {
        into("hwiha")
    }
    from(rootProject.file("data/curated/han/march-tempo-targets-v1.json")) {
        into("hwiha")
    }
}

tasks.test { useJUnitPlatform() }
