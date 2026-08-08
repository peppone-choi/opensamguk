package opensamguk.engine.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.infra.v2.V2ContentCatalog
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * OPENSAM-35 0A-f (S4) — game-engine의 **실제 부팅 컨텍스트**에서 v2 빈 수를 실측한다.
 *
 * S2·S3-a의 `ApplicationContextRunner` 테스트는 조건 평가만 재는 층이다. ADR-LITE-021 (iii)이
 * 요구하는 것은 "선언이 아니라 실측" — 그래서 여기서는 `@SpringBootTest` + Testcontainers로
 * v1 프로세스와 같은 모양의 컨텍스트를 통째로 띄우고 [ApplicationContext.getBeansOfType]으로 센다.
 * 리포의 기존 아키텍처 테스트(`DaemonNoEntityManagerTest` 등)는 전부 정적 스캔이라 이 층을 대체하지 못한다.
 *
 * 4개 클래스 = 판정 매트릭스 4칸이다. 마지막 [V2BothConditionsBeanGateIT]가 **양성 대조군**으로,
 * "전부 0"이 컨텍스트가 실제로 떠서 나온 0임을 증명한다.
 */
private const val SECURITY_EXCLUDES =
    // GameEngineApplicationTests와 같은 이유: game-api(:mainClassesForTest)의 transitive security
    // 스타터가 엔진 테스트 클래스패스에 올라와 런타임에 없는 보안 자동설정이 켜진다. 테스트 아티팩트다.
    "spring.autoconfigure.exclude=" +
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
        "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
        "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration," +
        "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"

/**
 * 타입 이름 하드코딩에 기대지 않는 방어. 나중에 누가 새 v2 빈을 만들고 이 테스트의 타입 목록에
 * 추가하지 않아도 게이트가 조용히 뚫리지 않도록, 컨텍스트의 **모든 빈 정의**를 훑어 v2 패키지에서
 * 온 빈을 잡는다. `allowFactoryBeanInit = false`라 빈을 만들지 않고 타입만 해석한다.
 */
internal fun ApplicationContext.v2PackageBeans(): Map<String, String> =
    beanDefinitionNames.mapNotNull { name ->
        val type = runCatching { getType(name, false) }.getOrNull()?.name ?: return@mapNotNull null
        if (type.startsWith("opensamguk.") && type.contains(".v2.")) name to type else null
    }.toMap()

internal fun ApplicationContext.assertNoV2Beans() {
    assertEquals(0, getBeansOfType(V2SandboxMarker::class.java).size, "V2SandboxMarker beans")
    assertEquals(0, getBeansOfType(V2ContentCatalog::class.java).size, "V2ContentCatalog beans")
    assertEquals(emptyMap(), v2PackageBeans(), "beans whose type lives in an opensamguk *.v2.* package")
}

private fun postgresProps(registry: DynamicPropertyRegistry, container: PostgreSQLContainer<*>) {
    registry.add("spring.datasource.url", container::getJdbcUrl)
    registry.add("spring.datasource.username", container::getUsername)
    registry.add("spring.datasource.password", container::getPassword)
    registry.add("management.health.redis.enabled") { "false" }
    registry.add("OPENSAMGUK_WORLD_ID") { "1" }
    registry.add("SCENARIO_SEED_ENABLED") { "false" }
    // 턴 루프를 띄우면 Redis 스트림을 실제로 소비한다. 빈은 그대로 만들어지고 시작만 막는다.
    registry.add("opensamguk.daemon.enabled") { "false" }
}

/** ① production shape — `V2_ENABLED` 미설정 + 프로파일 미활성. 기대: v2 빈 0개. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = [SECURITY_EXCLUDES])
class V2ProductionShapeBeanGateIT {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `production context registers no v2 bean`() = context.assertNoV2Beans()

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = postgresProps(registry, postgres)
    }
}

/** ② `v2.enabled=true`만 — 프로파일 없음. 기대: 0개. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = [SECURITY_EXCLUDES, "${V2SandboxGate.PROPERTY}=true"])
class V2PropertyOnlyBeanGateIT {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `property alone registers no v2 bean`() = context.assertNoV2Beans()

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = postgresProps(registry, postgres)
    }
}

/** ③ 프로파일 `v2-sandbox`만 — 프로퍼티 없음. 기대: 0개. */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles(V2SandboxGate.PROFILE)
@SpringBootTest(properties = [SECURITY_EXCLUDES])
class V2ProfileOnlyBeanGateIT {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `profile alone registers no v2 bean`() = context.assertNoV2Beans()

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = postgresProps(registry, postgres)
    }
}

/**
 * ④ **양성 대조군** — 둘 다 참. 기대: 등록됨.
 *
 * 이 칸이 없으면 컨텍스트가 아예 뜨지 않아도 ①~③이 통과할 수 있다. 여기서 실제 빈이 잡히는 것이
 * "위 3칸의 0은 컨텍스트가 떠 있는 상태에서 잰 0"이라는 증거다.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles(V2SandboxGate.PROFILE)
@SpringBootTest(properties = [SECURITY_EXCLUDES, "${V2SandboxGate.PROPERTY}=true"])
class V2BothConditionsBeanGateIT {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `both conditions register the v2 beans`() {
        assertEquals(1, context.getBeansOfType(V2SandboxMarker::class.java).size, "V2SandboxMarker beans")
        assertEquals(1, context.getBeansOfType(V2ContentCatalog::class.java).size, "V2ContentCatalog beans")
        val byPackage = context.v2PackageBeans()
        // 게이트 `@Configuration` 자신도 빈이라 패키지 스캔이 함께 잡는다 — 스캔이 타입 목록보다
        // 넓게 본다는 증거이자, 새 v2 빈이 목록에 추가되지 않아도 잡힌다는 근거다.
        assertTrue(
            byPackage.values.containsAll(
                listOf(
                    V2SandboxConfiguration::class.java.name,
                    V2SandboxMarker::class.java.name,
                    V2ContentCatalog::class.java.name,
                ),
            ),
            "v2 package beans: $byPackage",
        )
    }

    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) = postgresProps(registry, postgres)
    }
}
