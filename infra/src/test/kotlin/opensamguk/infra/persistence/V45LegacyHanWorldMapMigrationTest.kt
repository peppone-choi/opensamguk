package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V45LegacyHanWorldMapMigrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private val gameplaySnapshots = linkedMapOf<Int, Map<String, List<Int>>>()

    @BeforeAll
    fun setUp() {
        check(DockerClientFactory.instance().isDockerAvailable) {
            "Docker is required for V45LegacyHanWorldMapMigrationTest"
        }
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetDatabase() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
        migrateTo("44")
        gameplaySnapshots.clear()
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `V45 pins only exact 780 Han worlds and preserves unrelated JSON`() {
        seedWorld(
            worldId = 1,
            mapName = "han",
            cityIds = 1..780,
            configExtra = "\"keep\":{\"nested\":7}",
            metaExtra = "\"operator\":\"spep\"",
        )
        seedWorld(worldId = 2, mapName = "han", cityIds = 1..774)
        seedWorld(worldId = 3, mapName = "che", cityIds = 1..94)

        migrateTo("45")

        assertEquals("han-780-v1", activeMapName(1))
        assertEquals("han", activeMapName(2))
        assertEquals("che", activeMapName(3))
        assertEquals(7, config(1)["keep"].asObject()["nested"])
        assertEquals("spep", meta(1)["operator"])
        assertEquals(780, cityCount(1))
        assertEquals(0, changedGameplayIdCount())
    }

    @Test
    fun `V45 is isolated per world and leaves gameplay identities and ng_games map unchanged`() {
        seedWorld(1, "han", 1..780)
        seedWorld(2, "han", 1..774)
        val identitiesBefore = gameplayIdentities(1)
        val ngMapBefore = ngGamesMap(1)

        migrateTo("45")

        assertEquals("han-780-v1", activeMapName(1))
        assertEquals("han", activeMapName(2))
        assertEquals(identitiesBefore, gameplayIdentities(1))
        assertEquals(ngMapBefore, ngGamesMap(1))
    }

    @Test
    fun `V45 fails closed for 779 Han cities`() {
        seedWorld(1, "han", 1..779)

        assertFailsWith<FlywayException> { migrateTo("45") }

        assertEquals("han", activeMapName(1))
        assertEquals(0, successfulMigrationCount("45"))
    }

    @Test
    fun `V45 fails closed for non-contiguous 780-shaped Han ids`() {
        seedWorld(1, "han", (1..779).toList() + 781)

        assertFailsWith<FlywayException> { migrateTo("45") }

        assertEquals("han", activeMapName(1))
        assertEquals(0, successfulMigrationCount("45"))
    }

    @Test
    fun `V45 records one migration and a second migrate call is a no-op`() {
        seedWorld(1, "han", 1..780)

        migrateTo("45")
        val pinned = worldJson(1)
        migrateTo("45")

        assertEquals(pinned, worldJson(1))
        assertEquals(1, successfulMigrationCount("45"))
    }

    private fun migrateTo(target: String) {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .placeholders(mapOf("scenario_dir" to ""))
            .target(MigrationVersion.fromVersion(target))
            .load()
            .migrate()
    }

    private fun seedWorld(
        worldId: Int,
        mapName: String,
        cityIds: Iterable<Int>,
        configExtra: String? = null,
        metaExtra: String? = null,
    ) {
        val config = listOf("\"mapName\":\"$mapName\"", configExtra).filterNotNull().joinToString(",", "{", "}")
        val meta = listOf("\"mapName\":\"$mapName\"", metaExtra).filterNotNull().joinToString(",", "{", "}")
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, config, meta)
            VALUES (?, 'scenario_1010', 181, 1, 3600, ?::jsonb, ?::jsonb)
            """.trimIndent(),
            worldId,
            config,
            meta,
        )
        jdbc.update("INSERT INTO nation (world_id, id, name, color) VALUES (?, 1, 'Han', '#ffffff')", worldId)
        jdbc.update(
            "INSERT INTO general (world_id, id, name, nation_id, city_id, turn_time) VALUES (?, 1, 'General', 1, 1, now())",
            worldId,
        )
        jdbc.update("INSERT INTO troop (world_id, troop_leader, nation, name) VALUES (?, 1, 1, 'Troop')", worldId)
        jdbc.update(
            "INSERT INTO general_turn (world_id, id, general_id, turn_idx, action_code) VALUES (?, 1, 1, 0, 'rest')",
            worldId,
        )
        jdbc.update(
            "INSERT INTO nation_turn (world_id, id, nation_id, officer_level, turn_idx, action_code) VALUES (?, 1, 1, 1, 0, 'rest')",
            worldId,
        )
        jdbc.update(
            """
            INSERT INTO ng_games (world_id, id, server_id, date, map, season, scenario, scenario_name)
            VALUES (?, 1, ?, now(), ?, 1, 1010, 'fixture')
            """.trimIndent(),
            worldId,
            "fixture-$worldId",
            "ng-$mapName-$worldId",
        )
        val citySql = """
            INSERT INTO city
                (world_id, id, name, level, nation_id, pop, pop_max, agri, agri_max, comm, comm_max,
                 secu, secu_max, def, def_max, wall, wall_max, region)
            VALUES (?, ?, 'City', 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1)
        """.trimIndent()
        jdbc.dataSource!!.connection.use { connection ->
            connection.prepareStatement(citySql).use { statement ->
                cityIds.forEach { cityId ->
                    statement.setInt(1, worldId)
                    statement.setInt(2, cityId)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
        gameplaySnapshots[worldId] = gameplayIdentities(worldId)
    }

    private fun activeMapName(worldId: Int): String {
        val config = config(worldId)
        val meta = meta(worldId)
        return sequenceOf(
            config["mapName"],
            config["map"].asObjectOrNull()?.get("mapName"),
            config["map"],
            meta["mapName"],
            meta["map"].asObjectOrNull()?.get("mapName"),
            meta["map"],
        ).filterIsInstance<String>().first { it.isNotBlank() }
    }

    private fun config(worldId: Int): MutableMap<String, Any?> = worldMaps(worldId).first

    private fun meta(worldId: Int): MutableMap<String, Any?> = worldMaps(worldId).second

    private fun worldMaps(worldId: Int): Pair<MutableMap<String, Any?>, MutableMap<String, Any?>> = jdbc.queryForObject(
        "SELECT config::text, meta::text FROM world_state WHERE id = ?",
        { rs, _ -> MetaJson.decode(rs.getString(1)) to MetaJson.decode(rs.getString(2)) },
        worldId,
    )!!

    private fun cityCount(worldId: Int): Int = jdbc.queryForObject(
        "SELECT count(*) FROM city WHERE world_id = ?",
        Int::class.java,
        worldId,
    )!!

    private fun gameplayIdentities(worldId: Int): Map<String, List<Int>> = linkedMapOf(
        "city" to ids("city", worldId),
        "general" to ids("general", worldId),
        "nation" to ids("nation", worldId),
        "troop" to jdbc.queryForList(
            "SELECT troop_leader FROM troop WHERE world_id = ? ORDER BY troop_leader",
            Int::class.java,
            worldId,
        ),
        "general_turn" to ids("general_turn", worldId),
        "nation_turn" to ids("nation_turn", worldId),
    )

    private fun ids(table: String, worldId: Int): List<Int> = jdbc.queryForList(
        "SELECT id FROM $table WHERE world_id = ? ORDER BY id",
        Int::class.java,
        worldId,
    )

    private fun ngGamesMap(worldId: Int): String? = jdbc.queryForObject(
        "SELECT map FROM ng_games WHERE world_id = ? AND id = 1",
        String::class.java,
        worldId,
    )

    private fun worldJson(worldId: Int): String = jdbc.queryForObject(
        "SELECT config::text || '|' || meta::text FROM world_state WHERE id = ?",
        String::class.java,
        worldId,
    )!!

    private fun successfulMigrationCount(version: String): Int = jdbc.queryForObject(
        "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success = true",
        Int::class.java,
        version,
    )!!

    private fun Any?.asObject(): Map<*, *> = this as? Map<*, *> ?: error("expected JSON object, got $this")

    private fun Any?.asObjectOrNull(): Map<*, *>? = this as? Map<*, *>

    private fun changedGameplayIdCount(): Int = gameplaySnapshots.count { (worldId, before) ->
        before != gameplayIdentities(worldId)
    }
}
