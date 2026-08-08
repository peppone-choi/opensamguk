package opensamguk.gameapi.v2

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
 * OPENSAM-35 0A-f (S4) — game-api의 **실제 부팅 컨텍스트**에서 v2 빈 수를 실측한다.
 *
 * S2가 게이트를 game-engine·game-api 양쪽에 설치했으므로 실측도 양쪽에서 한다. 의미·구조는
 * `opensamguk.engine.v2.V2ProductionContextBeanGateIT`와 같다. 차이는 조회 대상 하나뿐 —
 * `V2ContentCatalog`는 game-engine에만 등록되므로(S3-a) 여기서는 **어떤 경우에도 0**이어야 하고,
 * 그 사실 자체를 게이트가 열린 칸에서도 assert한다.
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
    registry.add("opensamguk.world-id") { "1" }
    registry.add("management.health.redis.enabled") { "false" }
}

/** ① production shape — `V2_ENABLED` 미설정 + 프로파일 미활성. 기대: v2 빈 0개. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
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
@SpringBootTest(properties = ["${V2SandboxGate.PROPERTY}=true"])
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
@SpringBootTest
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
 * 이 칸이 없으면 컨텍스트가 아예 뜨지 않아도 ①~③이 통과할 수 있다.
 */
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles(V2SandboxGate.PROFILE)
@SpringBootTest(properties = ["${V2SandboxGate.PROPERTY}=true"])
class V2BothConditionsBeanGateIT {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `both conditions register the v2 beans`() {
        assertEquals(1, context.getBeansOfType(V2SandboxMarker::class.java).size, "V2SandboxMarker beans")
        // game-api에는 v2 콘텐츠 소비자가 없다(S3-a) — 게이트가 열려도 로더는 등록되지 않는다.
        assertEquals(0, context.getBeansOfType(V2ContentCatalog::class.java).size, "V2ContentCatalog beans")
        val byPackage = context.v2PackageBeans()
        assertTrue(
            byPackage.values.containsAll(
                listOf(V2SandboxConfiguration::class.java.name, V2SandboxMarker::class.java.name),
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
