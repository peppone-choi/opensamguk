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

// 빌드 버전/시각을 /actuator/info로 노출(buildInfo) → gateway-api가 fan-out 수집해 어드민에 표시.
// image.tag는 빌드 시 IMAGE_TAG env로 주입(없으면 dev).
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
    implementation("org.jsoup:jsoup:1.22.2")
    implementation(libs.sentry.spring.boot.starter)
    // F2 Wave 1 — JWT verify (shares gateway-api's HS256 secret; verify-only, no token issue).
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers.postgres)
    testImplementation(libs.testcontainers.junit)
    testImplementation("org.testcontainers:testcontainers:1.20.4")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("api.version", System.getProperty("api.version") ?: "1.44")
    environment("DOCKER_HOST", System.getenv("DOCKER_HOST") ?: "unix:///var/run/docker.sock")
    environment("DOCKER_CONTEXT", "default")
    environment("TESTCONTAINERS_RYUK_DISABLED", System.getenv("TESTCONTAINERS_RYUK_DISABLED") ?: "true")
}

// G3 cross-call-site seam: expose this module's compiled `main` classes as a consumable JAR variant
// so :app:game-engine's TEST source set can drive the REAL CommandPrecheckService. The default
// runtimeElements/apiElements variants advertise the Spring Boot `bootJar` (classes nested under
// BOOT-INF/classes/), which a downstream compiler can't read — and the plain `jar` is disabled to
// keep the Docker `build/libs/*.jar` glob to the single bootJar. This packages the raw `main` output
// into a plain library jar written OUTSIDE build/libs (build/test-consumable/), so the single-bootJar
// invariant in build/libs is preserved.
val mainJarForTest by tasks.registering(Jar::class) {
    archiveClassifier.set("classes-for-test")
    destinationDirectory.set(layout.buildDirectory.dir("test-consumable"))
    from(sourceSets.main.get().output)
}
val mainClassesForTest: Configuration by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
            objects.named(LibraryElements::class.java, LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class.java, Bundling.EXTERNAL))
    }
    // re-export the module's own runtime deps (logic/infra/common + spring data-jpa) transitively,
    // so the consumer resolves CommandPrecheckService's compile dependencies too.
    extendsFrom(configurations.runtimeClasspath.get())
}
artifacts {
    add(mainClassesForTest.name, mainJarForTest)
}
