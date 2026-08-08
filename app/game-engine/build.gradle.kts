import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyRuntimeBaselineJarIsolation : DefaultTask() {
    @get:InputFile
    abstract val classifierJar: RegularFileProperty

    @get:InputDirectory
    abstract val baselineJarDirectory: DirectoryProperty

    @get:Optional
    @get:InputDirectory
    abstract val productionJarDirectory: DirectoryProperty

    @TaskAction
    fun verify() {
        val classifier = classifierJar.asFile.get().canonicalFile
        val expectedDirectory = baselineJarDirectory.asFile.get().canonicalFile
        check(classifier.parentFile == expectedDirectory) {
            "Baseline classifier must be under $expectedDirectory, found $classifier"
        }
        check(classifier.name == "game-engine-cqrs-baseline.jar") {
            "Baseline classifier must use the fixed task output name, found $classifier"
        }
        val dedicatedClassifierJars = expectedDirectory
            .listFiles()
            ?.filter { it.isFile && it.name.endsWith("-cqrs-baseline.jar") }
            ?.map { it.canonicalFile }
            .orEmpty()
        check(dedicatedClassifierJars.size == 1 && dedicatedClassifierJars.single() == classifier) {
            "Baseline output directory must contain only the fixed classifier $classifier, found ${dedicatedClassifierJars.joinToString()}"
        }
        val productionClassifierJars = productionJarDirectory.orNull?.asFile
            ?.listFiles()
            ?.filter { it.isFile && it.name.endsWith("-cqrs-baseline.jar") }
            .orEmpty()
        check(productionClassifierJars.isEmpty()) {
            "Production build/libs contains baseline classifier artifacts: ${productionClassifierJars.joinToString()}"
        }
        val libraryEntries = ZipFile(classifier).use { zip ->
            val entries = mutableListOf<String>()
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) entries += iterator.nextElement().name
            entries.filter { it.startsWith("BOOT-INF/lib/") }
        }
        val forbiddenFragments = listOf("testcontainers", "junit", "game-api")
        val forbiddenEntries = libraryEntries.filter { entry ->
            forbiddenFragments.any { fragment -> entry.contains(fragment, ignoreCase = true) }
        }
        check(forbiddenEntries.isEmpty()) {
            "Baseline classifier contains test-only or game-api libraries: ${forbiddenEntries.joinToString()}"
        }
        for (requiredPrefix in listOf("flyway-core-", "flyway-database-postgresql-", "postgresql-")) {
            check(libraryEntries.any { it.substringAfterLast('/').startsWith(requiredPrefix) }) {
                "Baseline classifier is missing required runtime library prefix $requiredPrefix"
            }
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.depmgmt)
}

kotlin { jvmToolchain(21) }

// P1 Task G4 (the P1 GATE): the VerticalSliceE2EIT byte-compares the flushed rows against the
// SAME committed PHP-captured golden fixtures the :logic G2/G3 tests use. There is ONE golden
// file (logic/src/test/resources/golden/p1/), never a copy — so it can never drift. Expose it on
// the game-engine test classpath as an additional test-resources source dir (read-only consume).
sourceSets {
    test {
        resources {
            srcDir(rootProject.file("logic/src/test/resources"))
        }
    }
}

val v2NamingConventionSources = rootProject.files(
    listOf(
        "app/game-engine/src/main/kotlin",
        "app/game-api/src/main/kotlin",
        "app/gateway-api/src/main/kotlin",
        "infra/src/main/kotlin",
        "common/src/main/kotlin",
        "logic/src/main/kotlin",
    ).map { sourceRoot ->
        rootProject.fileTree(sourceRoot) {
            include("**/*.kt")
        }
    },
)

val baseline by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}

configurations.named(baseline.implementationConfigurationName) {
    extendsFrom(configurations.implementation.get())
}
configurations.named(baseline.runtimeOnlyConfigurationName) {
    extendsFrom(configurations.runtimeOnly.get())
}

// bootJar is the runnable artifact; disable the redundant plain jar so the
// Docker COPY build/libs/*.jar glob stays unambiguous.
tasks.named("jar") { enabled = false }

// 빌드 버전/시각을 /actuator/info로 노출(buildInfo) → gateway-api가 서버별 fan-out 수집해 어드민에 표시.
// 멀티서버에서 각 서버의 game-engine은 자기 버전을 보고한다. image.tag는 빌드 시 IMAGE_TAG env로 주입.
springBoot {
    buildInfo {
        properties {
            additional.put("image.tag", System.getenv("IMAGE_TAG") ?: "dev")
        }
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":logic"))
    implementation(project(":infra"))
    implementation(kotlin("reflect"))
    implementation(libs.kotlinx.serialization.json)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation(libs.sentry.spring.boot.starter)
    add(baseline.implementationConfigurationName, "org.flywaydb:flyway-core")
    add(baseline.runtimeOnlyConfigurationName, libs.flyway.postgres)
    add(baseline.runtimeOnlyConfigurationName, "org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    // G3 cross-call-site invariant test drives the REAL game-api CommandPrecheckService against the
    // SAME seeded world the game-engine ReservedTurnHandler evaluates in full mode — test-only and
    // one-directional (game-api never depends on game-engine), so no dependency cycle. This proves
    // the two REAL call sites agree (identical Allow/Deny class + identical reason string), not just
    // two invocations within :logic. We consume game-api's `mainClassesForTest` variant (its raw
    // compiled classes + transitive runtime deps) rather than the default variant, because game-api's
    // default artifact is the Spring Boot bootJar (classes nested under BOOT-INF/classes/, unreadable
    // by the downstream compiler) — see app/game-api/build.gradle.kts.
    testImplementation(project(path = ":app:game-api", configuration = "mainClassesForTest"))
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
    testImplementation("org.testcontainers:testcontainers:1.20.4")
    // F5 round-trip IT applies the :infra Flyway baseline against a Postgres container and writes
    // through the JDBC executor — Flyway + the JDBC driver are not transitive from :infra (both are
    // `implementation` there), so the test classpath pulls them directly (mirrors JdbcFlushExecutorIT).
    testImplementation("org.flywaydb:flyway-core")
    testRuntimeOnly(libs.flyway.postgres)
    testRuntimeOnly("org.postgresql:postgresql")
}

tasks.test {
    useJUnitPlatform()
    inputs.files(v2NamingConventionSources)
        .withPropertyName("v2NamingConventionSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("api.version", System.getProperty("api.version") ?: "1.44")
    providers.systemProperty("LONGSIM_SCHEMA4_CANDIDATE_DIR").orNull?.let {
        systemProperty("LONGSIM_SCHEMA4_CANDIDATE_DIR", it)
    }
    environment("DOCKER_HOST", System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock")
    environment("DOCKER_CONTEXT", "default")
    environment("TESTCONTAINERS_RYUK_DISABLED", System.getenv("TESTCONTAINERS_RYUK_DISABLED") ?: "true")
}

val runtimeBaselineJarDirectory = layout.buildDirectory.dir("cqrs-runtime-baseline/jars")
val productionDockerJarDirectory = layout.buildDirectory.dir("libs")

val runtimeBaselineJar by tasks.registering(org.springframework.boot.gradle.tasks.bundling.BootJar::class) {
    group = "verification"
    description = "Builds the isolated OPENSAM-123 baseline probe jar (not the production BootJar)."
    archiveFileName.set("game-engine-cqrs-baseline.jar")
    archiveClassifier.set("cqrs-baseline")
    destinationDirectory.set(runtimeBaselineJarDirectory)
    mainClass.set("opensamguk.engine.baseline.CqrsBaselineMain")
    targetJavaVersion.set(JavaVersion.VERSION_21)
    classpath = baseline.runtimeClasspath
    dependsOn(tasks.named(baseline.classesTaskName))
}

tasks.register<VerifyRuntimeBaselineJarIsolation>("verifyRuntimeBaselineJarIsolation") {
    group = "verification"
    description = "Verifies the baseline classifier location and classpath stay isolated from production Docker inputs."
    dependsOn(runtimeBaselineJar)
    classifierJar.set(runtimeBaselineJar.flatMap { it.archiveFile })
    baselineJarDirectory.set(runtimeBaselineJarDirectory)
    productionJarDirectory.set(productionDockerJarDirectory)
}
