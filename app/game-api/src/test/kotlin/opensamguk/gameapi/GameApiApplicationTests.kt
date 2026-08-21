package opensamguk.gameapi
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GameApiApplicationTests {
    @LocalServerPort
    var port: Int = 0
    @Autowired
    lateinit var rest: TestRestTemplate
    @Test
    fun `context loads and health endpoint reports UP`() {
        val body = rest.getForObject("http://localhost:$port/actuator/health", String::class.java)
        assertTrue(body!!.contains("\"status\":\"UP\""), "health body: $body")
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
            registry.add("opensamguk.world-id") { "1" }
            // Disable Redis health contribution for the boot test (no Redis container here).
            registry.add("management.health.redis.enabled") { "false" }
        }
    }
}
