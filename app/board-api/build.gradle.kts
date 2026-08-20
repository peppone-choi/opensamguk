plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.depmgmt)
}

kotlin { jvmToolchain(21) }

tasks.named("jar") { enabled = false }

springBoot {
    buildInfo {
        properties {
            additional.put("image.tag", System.getenv("IMAGE_TAG") ?: "dev")
        }
    }
}

dependencies {
    implementation(project(":common"))
    implementation(project(":infra"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation(libs.sentry.spring.boot.starter)
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("com.h2database:h2")
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("boardApiProjectDir", project.projectDir.absolutePath)
    systemProperty("api.version", System.getProperty("api.version") ?: "1.44")
    environment("DOCKER_HOST", System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock")
    environment("DOCKER_CONTEXT", "default")
    environment("TESTCONTAINERS_RYUK_DISABLED", System.getenv("TESTCONTAINERS_RYUK_DISABLED") ?: "true")
}
