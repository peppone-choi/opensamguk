plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

// 병종표 정본은 저장소 루트의 data/unitset/units.json 이다. 리소스에 사본을 두지 않고
// 빌드 때 실어 나른다 — 두 벌이 되면 어느 쪽이 맞는지 아무도 모른다.
tasks.processResources {
    from(rootProject.file("data/unitset/units.json")) { into("unitset") }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // 병종표 재출력 스위치를 테스트 JVM 으로 넘긴다 (CheUnitSetExportTest).
    System.getProperty("unitset.write")?.let { systemProperty("unitset.write", it) }
}
