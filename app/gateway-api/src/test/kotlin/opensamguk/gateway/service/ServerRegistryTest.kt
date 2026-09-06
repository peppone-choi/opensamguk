package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun `satisfied CREATE completion deletes only the exact transition and leaves registry metadata unchanged`() {
        val fixture = fixture("")
        val server = server("live1", generation = 2, scenarioCode = "scenario_1010")
        val other = server("live2", generation = null, scenarioCode = null)
        fixture.registry.register(server)
        fixture.registry.register(other)
        val before = fixture.registry.all()
        insertTransition(fixture.jdbc, server, OPERATION_ID, createdAt = Instant.now().minusSeconds(25 * 60 * 60))
        insertTransition(fixture.jdbc, other, OTHER_OPERATION_ID, createdAt = Instant.now().minusSeconds(25 * 60 * 60))

        val claimed = requireNotNull(fixture.registry.claimSatisfiedCreate(OPERATION_ID, "live1", OWNER))
        fixture.registry.completeSatisfiedCreateReconciliation(claimed)

        assertEquals(before, fixture.registry.all())
        assertEquals(
            listOf(OTHER_OPERATION_ID),
            fixture.jdbc.queryForList(
                "SELECT operation_id FROM game_server_registry_transition ORDER BY operation_id",
                String::class.java,
            ).map(String::trim),
        )
    }

    @Test
    fun `satisfied CREATE completion rechecks owner lease and nullable definition equality`() {
        data class Mutation(val sql: String)
        val mutations = listOf(
            Mutation("UPDATE game_server_registry_transition SET owner_token = 'other-owner' WHERE operation_id = '$OPERATION_ID'"),
            Mutation("UPDATE game_server_registry_transition SET lease_until = CURRENT_TIMESTAMP WHERE operation_id = '$OPERATION_ID'"),
            Mutation("UPDATE game_server_registry_transition SET request_fingerprint = '${"b".repeat(64)}' WHERE operation_id = '$OPERATION_ID'"),
            Mutation("UPDATE game_server_registry_transition SET dispatched = FALSE WHERE operation_id = '$OPERATION_ID'"),
            Mutation("UPDATE game_server_registry_transition SET remote_applied = TRUE WHERE operation_id = '$OPERATION_ID'"),
            Mutation("UPDATE game_server_registry_transition SET action = 'RESET' WHERE operation_id = '$OPERATION_ID'"),
            Mutation("UPDATE game_server SET generation = NULL WHERE server_id = 'live1'"),
            Mutation("UPDATE game_server SET scenario_code = NULL WHERE server_id = 'live1'"),
        )

        mutations.forEach { mutation ->
            val fixture = fixture("")
            val server = server("live1", generation = 2, scenarioCode = "scenario_1010")
            fixture.registry.register(server)
            insertTransition(fixture.jdbc, server, OPERATION_ID, createdAt = Instant.now().minusSeconds(25 * 60 * 60))
            val claimed = requireNotNull(fixture.registry.claimSatisfiedCreate(OPERATION_ID, "live1", OWNER))
            fixture.jdbc.update(mutation.sql)

            assertFailsWith<ServerRegistryTransitionConflict> {
                fixture.registry.completeSatisfiedCreateReconciliation(claimed)
            }
            assertEquals(
                1,
                fixture.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM game_server_registry_transition WHERE operation_id = ?",
                    Int::class.java,
                    OPERATION_ID,
                ),
                mutation.sql,
            )
        }
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
        jdbc.execute(
            """
            CREATE TABLE game_server_registry_transition (
                server_id VARCHAR(48) PRIMARY KEY,
                action VARCHAR(8) NOT NULL,
                display_name VARCHAR(100) NOT NULL,
                game_api_url VARCHAR(255) NOT NULL,
                game_engine_url VARCHAR(255) NOT NULL,
                deploy_project VARCHAR(100) NOT NULL,
                generation INTEGER,
                scenario_code VARCHAR(100),
                operation_id CHAR(32) NOT NULL UNIQUE,
                request_fingerprint CHAR(64) NOT NULL,
                dispatched BOOLEAN NOT NULL DEFAULT FALSE,
                remote_applied BOOLEAN NOT NULL DEFAULT FALSE,
                owner_token VARCHAR(36) NOT NULL,
                lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
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

    private fun server(id: String, generation: Int?, scenarioCode: String?) =
        ServerDef(
            id = id,
            name = "Server $id",
            gameApiUrl = "http://s$id-game-api:8081",
            gameEngineUrl = "http://s$id-game-engine:8082",
            deployProject = "opensamguk-s$id",
            generation = generation,
            scenarioCode = scenarioCode,
        )

    private fun insertTransition(
        jdbc: JdbcTemplate,
        server: ServerDef,
        operationId: String,
        createdAt: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO game_server_registry_transition (
                server_id, action, display_name, game_api_url, game_engine_url, deploy_project,
                generation, scenario_code, operation_id, request_fingerprint,
                dispatched, remote_applied, owner_token, lease_until, created_at
            ) VALUES (?, 'CREATE', ?, ?, ?, ?, ?, ?, ?, ?, TRUE, FALSE, 'old-owner', CURRENT_TIMESTAMP, ?)
            """.trimIndent(),
            server.id,
            server.name,
            server.gameApiUrl,
            server.gameEngineUrl,
            server.deployProject,
            server.generation,
            server.scenarioCode,
            operationId,
            "a".repeat(64),
            java.sql.Timestamp.from(createdAt),
        )
    }

    private data class RegistryFixture(
        val registry: ServerRegistry,
        val jdbc: JdbcTemplate,
    )

    companion object {
        private const val OPERATION_ID = "0123456789abcdef0123456789abcdef"
        private const val OTHER_OPERATION_ID = "1123456789abcdef0123456789abcdef"
        private const val OWNER = "reconciliation-owner"
    }
}
