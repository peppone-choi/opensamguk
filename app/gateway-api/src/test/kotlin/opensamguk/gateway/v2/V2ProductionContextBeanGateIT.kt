package opensamguk.gateway.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.gateway.profile.ProfileIconSecureStorageTestConfiguration
import opensamguk.infra.v2.V2ContentCatalog
import opensamguk.infra.v2.V2SandboxGate
import opensamguk.infra.v2.V2SandboxMarker
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * OPENSAM-35 0A-f (S4) — gateway-api의 **실제 부팅 컨텍스트**에서 v2 빈 수를 실측한다.
 *
 * game-engine·game-api의 동명 IT와 같은 게이트지만 기대값이 하나 다르다. gateway-api에는
 * `V2SandboxConfiguration`이 **없다** — 인증/프로필 서비스라 v2 마커 빈을 소비할 대상이 하나도
 * 없어서 쓰지도 않을 조건부 빈을 세 번째로 복사하지 않았다. 그래서 여기서는 게이트를 **완전히 연
 * 상태(④)에서도 0**이어야 한다. 나중에 v2 코드가 실수로 gateway-api로 새면 이 테스트가 잡는다.
 *
 * 컨텍스트는 기존 [opensamguk.gateway.GatewayApiApplicationTests]와 같은 방식으로 띄운다 —
 * `src/test/resources/application.yml`의 H2 + Flyway off. Docker/Testcontainers 불필요.
 */
internal fun ApplicationContext.v2PackageBeans(): Map<String, String> =
    beansByTypePrefix("opensamguk.").filterValues { it.contains(".v2.") }

/**
 * 빈 **이름**이 아니라 **타입**으로 판정한다. 컴포넌트 스캔 빈의 정의 이름은 FQN이 아니라
 * decapitalized 단순명이므로 이름 접두 비교는 실동작상 죽은 항이다.
 */
internal fun ApplicationContext.beansByTypePrefix(prefix: String): Map<String, String> =
    beanDefinitionNames.mapNotNull { name ->
        val type = runCatching { getType(name, false) }.getOrNull()?.name ?: return@mapNotNull null
        if (type.startsWith(prefix)) name to type else null
    }.toMap()

/**
 * 4개 판정 칸이 공유하는 본문. "0개"가 컨텍스트가 실제로 떠서 나온 0임을 증명해야 하는데,
 * gateway-api에는 켜져도 등록될 v2 빈이 없어 양성 대조군을 v2 빈으로 세울 수 없다. 대신
 * 애플리케이션 자신의 빈이 **타입 기준**으로 올라와 있음을 함께 확인한다(실측 30개, 2026-08-08).
 */
abstract class V2BeanGateContract {
    @Autowired lateinit var context: ApplicationContext

    @Test
    fun `gateway-api registers no v2 bean`() {
        val gatewayBeans = context.beansByTypePrefix("opensamguk.gateway")
        assertTrue(
            gatewayBeans.isNotEmpty(),
            "context did not actually boot the gateway application beans (type-prefixed 'opensamguk.gateway' beans = 0)",
        )
        assertEquals(0, context.getBeansOfType(V2SandboxMarker::class.java).size, "V2SandboxMarker beans")
        assertEquals(0, context.getBeansOfType(V2ContentCatalog::class.java).size, "V2ContentCatalog beans")
        assertEquals(emptyMap(), context.v2PackageBeans(), "beans whose type lives in an opensamguk *.v2.* package")
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("management.health.redis.enabled") { "false" }
        }
    }
}

/** ① production shape — `V2_ENABLED` 미설정 + 프로파일 미활성. */
@SpringBootTest
@Import(ProfileIconSecureStorageTestConfiguration::class)
class V2ProductionShapeBeanGateIT : V2BeanGateContract()

/** ② `v2.enabled=true`만. */
@SpringBootTest(properties = ["${V2SandboxGate.PROPERTY}=true"])
@Import(ProfileIconSecureStorageTestConfiguration::class)
class V2PropertyOnlyBeanGateIT : V2BeanGateContract()

/** ③ 프로파일 `v2-sandbox`만. */
@ActiveProfiles(V2SandboxGate.PROFILE)
@SpringBootTest
@Import(ProfileIconSecureStorageTestConfiguration::class)
class V2ProfileOnlyBeanGateIT : V2BeanGateContract()

/** ④ 둘 다 참 — 그래도 0. gateway-api에는 `V2SandboxConfiguration`이 없기 때문이다. */
@ActiveProfiles(V2SandboxGate.PROFILE)
@SpringBootTest(properties = ["${V2SandboxGate.PROPERTY}=true"])
@Import(ProfileIconSecureStorageTestConfiguration::class)
class V2BothConditionsBeanGateIT : V2BeanGateContract()
