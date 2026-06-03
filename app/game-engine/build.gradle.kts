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
    systemProperty("api.version", System.getProperty("api.version") ?: "1.44")
    environment("DOCKER_HOST", System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock")
    environment("DOCKER_CONTEXT", "default")
    environment("TESTCONTAINERS_RYUK_DISABLED", System.getenv("TESTCONTAINERS_RYUK_DISABLED") ?: "true")
}
