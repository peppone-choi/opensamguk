import java.security.MessageDigest

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
    testImplementation(libs.archunit.junit5)
    testImplementation(testFixtures(project(":common")))
}

val waryongCatalogFile = rootProject.file("data/battle/waryong/catalog-v1.json")
val verifyWaryongCatalog by tasks.registering {
    inputs.file(waryongCatalogFile)
    doLast {
        val bytes = inputs.files.singleFile.readBytes()
        check(bytes.size == 954_038) { "Waryong catalog export byte count changed" }
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(actual == "2eb021038ccf36178127247d25c18f03f538e6f139a2e699fdb40e5ed5f4bb27") {
            "Waryong catalog export SHA-256 drifted: $actual"
        }
    }
}

// 입력 원장은 저장소 루트의 JSON 하나가 정본이다(game-api 의 public-alpha 카탈로그와 같은 방식).
tasks.processResources {
    dependsOn(verifyWaryongCatalog)
    from(rootProject.file("data/curated/han/local-offices.json")) {
        into("office")
    }
    from(rootProject.file("data/curated/han/office-rules.json")) {
        into("office")
    }
    from(rootProject.file("data/curated/han/vassal-rules.json")) {
        into("vassal")
    }
    from(rootProject.file("data/commands/input-catalog.json")) {
        into("command-catalog")
    }
    // 휘하 적성 가중식(게임 설계 수치) — 정본은 저장소 루트 파일 하나다.
    from(rootProject.file("data/curated/han/aptitude-weights-v1.json")) {
        into("campaign")
    }
    // 휘하 시야 반경·병력 구간·첩보 비용(2026-09-23 확정 수치) — 정본은 저장소 루트 파일 하나다.
    from(rootProject.file("data/curated/han/vision-rules-v1.json")) {
        into("campaign")
    }
    // 휘하 내정 입력(배치·방침·공사)의 2026-09-23 확정 수치 — 정본은 저장소 루트 파일 하나다.
    from(rootProject.file("data/curated/han/domestic-v1.json")) {
        into("campaign")
    }
    from(rootProject.file("data/curated/han/military-v1.json")) {
        into("campaign")
    }
    from(rootProject.file("data/curated/han/personal-v1.json")) {
        into("campaign")
    }
    from(rootProject.file("data/curated/han/people-v1.json")) {
        into("campaign")
    }
    from(rootProject.file("data/curated/han/direct-actions-v1.json")) {
        into("campaign")
    }
    from(rootProject.file("data/curated/han/political-v1.json")) {
        into("campaign")
    }
    // 보물 카드와 무제한 장비는 추출 원본에서 나눈 정본 원장을 그대로 싣는다.
    from(rootProject.file("data/curated/han/treasure-cards-v1.json")) {
        into("campaign")
    }
    from(rootProject.file("data/curated/han/equipment-v1.json")) {
        into("campaign")
    }
    from(rootProject.file("data/curated/han/personal-encounter-v1.json")) {
        into("campaign")
    }
    from(rootProject.file("data/battle/waryong-tactical-rules-v1.json")) {
        into("battle")
    }
    from(waryongCatalogFile) {
        into("battle/waryong")
    }
    from(rootProject.file("data/battle/waryong/NOTICE.md")) {
        into("battle/waryong")
    }
    from(rootProject.file("data/curated/han/march-tempo-targets-v1.json")) {
        into("campaign")
    }
}

tasks.test { useJUnitPlatform() }

// Keep report-only architecture counts visible in CI without publishing test worker stdout.
tasks.test {
    doLast {
        val report = project.file("build/reports/archunit/measurements.json")
        if (report.isFile) project.logger.lifecycle("ARCHUNIT_CI_REPORT ${report.readText().trim()}")
    }
}
