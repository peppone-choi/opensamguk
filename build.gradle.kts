plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.kotlin.jpa) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.depmgmt) apply false
}

allprojects {
    group = "opensamguk"
    version = "0.0.1-SNAPSHOT"
    repositories { mavenCentral() }
}

// Tests in these modules open repository files directly (outside their classpath).
// Keep the map allowlist here: data/map also contains gitignored source material.
val trackedMapTestInputs = fileTree("data/map") {
    include("han-*artifacts-v1/**")
    include(
        "external-places.json", "han-780-v1-manifest.json", "han-780-v1-tiles.json",
        "han-administrative-history.json", "han-commandery-supply-links-v1.json",
        "han-ju-index-v1.json", "han-province-id-registry.tsv",
        "han-scenario-jurisdiction-conflict-allowlist-v1.json",
        "han-scenario-province-ownership-v1.json", "han-strategic-sites.json",
        "han-strategic-topology-manifest-v1.json", "han-tiles.json",
        "han-water-topology-v1.json", "han-waterway-network-v1.json",
        "han-world-v2-manifest.json", "han-world-v3-manifest-v1.json",
    )
}
subprojects {
    if (path in setOf(":infra", ":app:game-api", ":app:game-engine")) {
        tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
            inputs.files(trackedMapTestInputs, rootProject.fileTree("data/curated"),
                rootProject.fileTree("data/commands"), rootProject.fileTree("data/unitset"),
                rootProject.fileTree("data/battle"), rootProject.fileTree("infra/src/main/resources"),
                rootProject.fileTree("tools/e2e/fixtures"))
                .withPropertyName("repositoryTestInputs")
                .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
        }
    }
}
