package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerRegistryTest {

    private val mapper = ObjectMapper()

    @Test
    fun `all reads servers inserted after registry construction`() {
        val fixture = fixture("")

        assertTrue(fixture.registry.all().isEmpty())
        fixture.jdbc.update(
            """
            INSERT INTO game_server (
                server_id, display_name, game_api_url, game_engine_url, deploy_project
            ) VALUES ('live1', '라입 서버', 'http://slive1-game-api:8081', 'http://slive1-game-engine:8082', 'opensamguk-slive1')
            """.trimIndent(),
        )

        assertEquals(listOf("live1"), fixture.registry.all().map { it.id })
    }

    @Test
    fun `environment seed does not overwrite a non-empty database`() {
        val fixture = fixture("""[{"id":"seeded"}]""") { jdbc ->
            insertCanonical(jdbc, "existing")
        }

        assertEquals(listOf("existing"), fixture.registry.all().map { it.id })
    }

    @Test
    fun `database read failure returns an empty registry`() {
        val fixture = fixture("""[{"id":"seeded"}]""")
        fixture.jdbc.execute("DROP TABLE game_server")

        assertTrue(fixture.registry.all().isEmpty())
        assertNull(fixture.registry.find("seeded"))
        assertNull(fixture.registry.default())
    }

    @Test
    fun `default compose coordinates derive from the public server ID`() {
        val parsed = registry("""[{"id":"s1"},{"id":"current"}]""")

        assertEquals(listOf("s1", "current"), parsed.all().map { it.id })
        assertEquals("http://ss1-game-api:8081", parsed.find("s1")?.gameApiUrl)
        assertEquals("http://scurrent-game-api:8081", parsed.find("current")?.gameApiUrl)
    }

    @Test
    fun `a mixed invalid collection fails closed instead of keeping its valid prefix`() {
        val parsed = registry("""[{"id":"pep"},{"id":"main"}]""")

        assertTrue(parsed.all().isEmpty())
        assertNull(parsed.find("pep"))
        assertNull(parsed.default())
    }

    @Test
    fun `canonical collisions reject the complete collection`() {
        val parsed = registry("""[{"id":"pep"},{"id":"pep"}]""")

        assertTrue(parsed.all().isEmpty())
    }

    @Test
    fun `bad internal API origin rejects the complete collection`() {
        val parsed = registry(
            """[{"id":"pep","gameApiUrl":"http://spep-game-api:8081"},{"id":"a1","gameApiUrl":"http://a1-game-api:8081"}]""",
        )

        assertTrue(parsed.all().isEmpty())
    }

    @Test
    fun `reserved top-level route IDs are rejected while current remains public`() {
        val reserved = listOf(
            "all", "main", "admin1", "admin2", "admin5", "admin7", "admin8", "auction", "battle-center",
            "betting", "board", "chief-center", "city", "coming-soon", "diplomacy", "generals",
            "global-diplomacy", "history", "inherit", "join", "mailbox", "map", "my", "my-boss",
            "my-cities", "my-generals", "my-nation", "nation", "nation-betting", "nation-finance",
            "npc-control", "rankings", "register", "select-pool", "simulator", "tournament",
            "tournament-admin", "troop", "vote", "world-log",
        )

        assertEquals(listOf("current"), registry("""[{"id":"current"}]""").all().map { it.id })
        reserved.forEach { id ->
            assertTrue(registry("""[{"id":"current"},{"id":"$id"}]""").all().isEmpty(), "reserved id=$id")
        }
    }

    @Test
    fun `object-form registry including duplicate keys rejects the complete collection`() {
        val parsed = registry(
            """{"pep":"http://wrong-game-api:8081","pep":"http://spep-game-api:8081"}""",
        )

        assertTrue(parsed.all().isEmpty())
    }

    private fun registry(json: String): ServerRegistry = fixture(json).registry

    private fun fixture(
        json: String,
        beforeRegistry: (JdbcTemplate) -> Unit = {},
    ): RegistryFixture {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:${UUID.randomUUID()};MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute(
            """
            CREATE TABLE game_server (
                sort_order BIGINT GENERATED ALWAYS AS IDENTITY UNIQUE,
                server_id VARCHAR(48) PRIMARY KEY,
                display_name VARCHAR(100) NOT NULL,
                game_api_url VARCHAR(255) NOT NULL,
                game_engine_url VARCHAR(255) NOT NULL,
                deploy_project VARCHAR(100) NOT NULL,
                generation INTEGER,
                scenario_code VARCHAR(100)
            )
            """.trimIndent(),
        )
        beforeRegistry(jdbc)
        val registry = ServerRegistry(json, mapper, jdbc)
        return RegistryFixture(registry, jdbc)
    }

    private fun insertCanonical(jdbc: JdbcTemplate, id: String) {
        jdbc.update(
            """
            INSERT INTO game_server (
                server_id, display_name, game_api_url, game_engine_url, deploy_project
            ) VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            id,
            "http://s$id-game-api:8081",
            "http://s$id-game-engine:8082",
            "opensamguk-s$id",
        )
    }

    private data class RegistryFixture(
        val registry: ServerRegistry,
        val jdbc: JdbcTemplate,
    )
}
