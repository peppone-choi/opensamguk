package opensamguk.engine.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import opensamguk.infra.v2.V2ContentCatalog
import opensamguk.infra.v2.V2SandboxGate
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * OPENSAM-35 0A-d — 로더 빈이 **게이트 안에서만** 존재함을 S2와 같은 방식([ApplicationContextRunner])으로
 * 실측한다. 로더 자체의 스코프·읽기 전용 성질은 `infra`의 `V2ContentCatalogTest`가 판정한다.
 */
class V2ContentCatalogBeanTest {

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(V2SandboxConfiguration::class.java)

    @Test
    fun `gate closed - no content catalog bean`() {
        runner.run { assertEquals(0, it.getBeansOfType(V2ContentCatalog::class.java).size) }
        runner.withPropertyValues("${V2SandboxGate.PROPERTY}=true")
            .run { assertEquals(0, it.getBeansOfType(V2ContentCatalog::class.java).size) }
        runner.withPropertyValues("spring.profiles.active=${V2SandboxGate.PROFILE}")
            .run { assertEquals(0, it.getBeansOfType(V2ContentCatalog::class.java).size) }
    }

    @Test
    fun `gate open - content catalog bean registered and reads an empty catalog`() {
        gateOpen().run { context ->
            val catalog = context.getBean(V2ContentCatalog::class.java)
            assertNotNull(catalog)
            // 실제 v2 콘텐츠 파일은 0개다(README만). 빈 목록 = 정상, 예외 아님.
            assertEquals(emptyList(), catalog.names())
        }
    }

    /**
     * 게이트가 열려도 **[V2SandboxConfiguration] 자신은** 부팅 시 자동 실행되는 빈을 등록하지 않음을 실측한다.
     *
     * 측정 범위: 이 설정 클래스 하나만 등록한 맨몸 [ApplicationContextRunner]다. 실제 앱 컨텍스트에는
     * `ScenarioSeedRunner` 등 v1 runner가 존재하므로, 이 0은 "게이트가 열린 **앱** 컨텍스트에
     * startup runner가 없다"를 뜻하지 않는다. 이 설정이 새 부팅 훅을 들여오지 않는다는 것만 고정한다.
     */
    @Test
    fun `gate open - registers no startup runner`() {
        gateOpen().run { context ->
            assertEquals(0, context.getBeansOfType(ApplicationRunner::class.java).size)
            assertEquals(0, context.getBeansOfType(CommandLineRunner::class.java).size)
        }
    }

    private fun gateOpen() = runner.withPropertyValues(
        "spring.profiles.active=${V2SandboxGate.PROFILE}",
        "${V2SandboxGate.PROPERTY}=true",
    )
}
