package opensamguk.gameapi.read
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertNull
/**
 * W1-O 바퀴49 — nation_env(V3) read 채널 round-trip.
 *
 * 데몬 flush(`JdbcFlushExecutor.nationEnvKvWrite`)가 쓰는 그대로 jsonb를 INSERT하고
 * [NationEnvReadRepository]로 읽어 ObjectMapper 디코드(nationNotice.msg / scout_msg /
 * available_war_setting_cnt)를 검증한다 — game-api가 nation_env를 진짜로 읽어내는지(테이블/네임스페이스/jsonb
 * 인코딩 정합)를 실 DB로 증명. Testcontainers postgres:16-alpine + infra Flyway baseline(ddl-auto validate).
 */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class NationEnvReadIT {
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var nationEnv: NationEnvReadRepository
    private val objectMapper = ObjectMapper()
    @Test
    fun `nation_env jsonb round-trips through the read repo and decodes`() {
        // 데몬 nationEnvKvWrite가 쓰는 형태 그대로: namespace = nationId, key, value = jsonb.
        jdbc.update(
            """
            INSERT INTO nation_env (namespace, key, value) VALUES
              (1, 'nationNotice', '{"date":"200-3","msg":"천하통일을 위하여","author":"순욱","authorID":10}'::jsonb)
            """.trimIndent(),
        )
        jdbc.update("""INSERT INTO nation_env (namespace, key, value) VALUES (1, 'scout_msg', '"인재를 구합니다"'::jsonb)""")
        jdbc.update("""INSERT INTO nation_env (namespace, key, value) VALUES (1, 'available_war_setting_cnt', '3'::jsonb)""")
        // 다른 국가의 같은 키 — namespace 격리(누출 금지).
        jdbc.update("""INSERT INTO nation_env (namespace, key, value) VALUES (2, 'available_war_setting_cnt', '7'::jsonb)""")
        // nationNotice = {date,msg,author,authorID} 객체 → .msg.
        val notice = nationEnv.findByNamespaceAndKey(1, "nationNotice")!!
        assertEquals("천하통일을 위하여", objectMapper.readTree(notice.value).get("msg").asText())
        // scout_msg = JSON 문자열.
        val scout = nationEnv.findByNamespaceAndKey(1, "scout_msg")!!
        assertEquals("인재를 구합니다", objectMapper.readTree(scout.value).asText())
        // available_war_setting_cnt = JSON int.
        val warCnt = nationEnv.findByNamespaceAndKey(1, "available_war_setting_cnt")!!
        assertEquals(3, objectMapper.readTree(warCnt.value).asInt())
        // namespace 격리 + 부재 키 null(날조 금지의 read-side 근거).
        assertEquals(7, objectMapper.readTree(nationEnv.findByNamespaceAndKey(2, "available_war_setting_cnt")!!.value).asInt())
        assertNull(nationEnv.findByNamespaceAndKey(1, "nonexistent"))
        assertNull(nationEnv.findByNamespaceAndKey(99, "nationNotice"))
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
        }
    }
}
