plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // 병종표 재출력 스위치를 테스트 JVM 으로 넘긴다 (CheUnitSetExportTest).
    System.getProperty("unitset.write")?.let { systemProperty("unitset.write", it) }
}
