plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.depmgmt)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":common"))
    implementation(project(":logic"))
    implementation(kotlin("reflect"))
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly(libs.flyway.postgres)
    // PGobject is referenced in main code (JdbcFlushExecutor binds jsonb columns), so the
    // postgres driver must be on the compile classpath, not runtimeOnly.
    implementation("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
}

// Library module: produce a plain jar, not an executable bootJar.
tasks.named("bootJar") { enabled = false }
tasks.named("jar") { enabled = true }

tasks.test {
    useJUnitPlatform()
    // Local dev (macOS Docker Desktop 29.x): the bundled docker-java client defaults to API
    // v1.32 while the daemon requires >= v1.40. docker-java reads the client version from the
    // `api.version` SYSTEM PROPERTY (not the DOCKER_API_VERSION env var). Pin it, point at the
    // real daemon socket, and neutralize the desktop-linux context so the proxy socket is not
    // selected. These align with CI where the daemon already speaks a modern API.
    systemProperty("api.version", System.getProperty("api.version") ?: "1.44")
    environment("DOCKER_HOST", System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock")
    environment("DOCKER_CONTEXT", "default")
    environment("TESTCONTAINERS_RYUK_DISABLED", System.getenv("TESTCONTAINERS_RYUK_DISABLED") ?: "true")
}
