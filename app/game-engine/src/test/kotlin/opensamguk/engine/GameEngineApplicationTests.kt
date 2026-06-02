package opensamguk.engine

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertTrue

@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    // game-engine 자체엔 Spring Security가 없다. 다만 test classpath가 game-api(:mainClassesForTest)의
    // transitive 런타임 deps를 끌어와 spring-boot-starter-security가 함께 올라오면서, 엔진 테스트 컨텍스트에
    // Security 자동설정이 활성화돼 /admin/turn-daemon/status가 로그인 페이지를 반환했다. 엔진 런타임엔
    // 보안이 없으므로 테스트 컨텍스트에서 보안 자동설정을 명시적으로 제외한다.
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration",
    ],
)
class GameEngineApplicationTests {

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var rest: TestRestTemplate

    @Test
    fun `status endpoint reports the configured profile and idle state`() {
        val body = rest.getForObject(
            "http://localhost:$port/admin/turn-daemon/status", String::class.java
        )
        assertTrue(body!!.contains("\"state\":\"idle\""), "status body: $body")
        assertTrue(body.contains("che:scenario_2"), "status body: $body")
    }

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("management.health.redis.enabled") { "false" }
            // The production turn loop (TurnDaemonRunner) must NOT auto-start during a context-load test:
            // it would drain the world + need a live Redis command stream. The bean is still created; only
            // its SmartLifecycle start is gated off.
            registry.add("opensamguk.daemon.enabled") { "false" }
        }
    }
}
