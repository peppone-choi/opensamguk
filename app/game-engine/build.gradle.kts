plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.depmgmt)
}

kotlin { jvmToolchain(21) }

// bootJar is the runnable artifact; disable the redundant plain jar so the
// Docker COPY build/libs/*.jar glob stays unambiguous.
tasks.named("jar") { enabled = false }

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
