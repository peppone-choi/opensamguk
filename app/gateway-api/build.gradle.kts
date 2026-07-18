plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.depmgmt)
}

kotlin { jvmToolchain(21) }

// bootJar is the runnable artifact; disable the redundant plain jar so the
// Docker COPY build/libs/*.jar glob stays unambiguous.
tasks.named("jar") { enabled = false }

// 빌드 버전/시각을 META-INF/build-info.properties로 생성 → BuildProperties 빈 자동 등록.
// 어드민 버전 표시(GET /admin/version)에서 읽는다. image.tag는 빌드 시 IMAGE_TAG env로 주입(없으면 dev).
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
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation(libs.sentry.spring.boot.starter)
    implementation(libs.imageio.webp)
    implementation(libs.imageio.avif)
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
    systemProperty("api.version", System.getProperty("api.version") ?: "1.44")
    environment("DOCKER_HOST", System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock")
    environment("DOCKER_CONTEXT", "default")
    environment("TESTCONTAINERS_RYUK_DISABLED", System.getenv("TESTCONTAINERS_RYUK_DISABLED") ?: "true")
}
