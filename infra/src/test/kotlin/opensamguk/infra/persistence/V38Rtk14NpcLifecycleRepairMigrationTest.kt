package opensamguk.infra.persistence

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class V38Rtk14NpcLifecycleRepairMigrationTest {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate

    private data class SourceGeneral(
        val name: String,
        val bornYear: Int,
        val appearanceYear: Int,
        val officerNumber: Int = 17001,
        val legacyActiveAtStart: Boolean = true,
        val rtk14Added: Boolean = false,
    )

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        jdbc = JdbcTemplate(DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password))
    }

    @BeforeEach
    fun resetDatabase() {
        jdbc.execute("DROP SCHEMA public CASCADE")
        jdbc.execute("CREATE SCHEMA public")
        migrateTo("37")
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `V38 repairs recorded V26 worlds with external future appearances exactly once`(@TempDir scenarioDir: Path) {
        Files.writeString(
            scenarioDir.resolve("scenario_v26_npc_lifecycle.json"),
            scenarioJson("소제1", bornYear = 160, appearanceYear = 205),
            StandardCharsets.UTF_8,
        )
        Files.writeString(
            scenarioDir.resolve("scenario_v38_external_only.json"),
            scenarioJson("외부전용", bornYear = 159, appearanceYear = 206),
            StandardCharsets.UTF_8,
        )
        seedRecordedV37World(1, "scenario_v26_npc_lifecycle", "소제1", bornYear = 160)
        seedRecordedV37World(2, "scenario_v38_external_only", "외부전용", bornYear = 159)
        seedDeferredEvent(worldId = 1, nationId = 2, name = "소제1", appearanceYear = 205)

        assertEquals(1, successfulMigrationCount("26"))
        assertEquals(1, successfulMigrationCount("37"))
        assertEquals(0, successfulMigrationCount("38"))

        migrateTo("38", scenarioDir.toString())

        assertDeferredAndRemoved(worldId = 1, name = "소제1", appearanceYear = 205)
        assertDeferredAndRemoved(worldId = 2, name = "외부전용", appearanceYear = 206)
        assertEquals(1, deferredEventCount(worldId = 1, nationId = 2, name = "소제1"))
        assertEquals(1, successfulMigrationCount("38"))

        migrateTo("38", scenarioDir.toString())

        assertEquals(1, deferredEventCount(worldId = 1, nationId = 1, name = "소제1"))
        assertEquals(1, deferredEventCount(worldId = 1, nationId = 2, name = "소제1"))
        assertEquals(1, deferredEventCount(worldId = 2, nationId = 1, name = "외부전용"))
        assertEquals(1, successfulMigrationCount("38"))
    }

    @Test
    fun `V38 rewrites a recorded V26 deferred action from the effective RTK tuple`(@TempDir scenarioDir: Path) {
        Files.writeString(
            scenarioDir.resolve("scenario_v38_deferred_repair.json"),
            scenarioJson("이전지연", bornYear = 190, appearanceYear = 205, legacyActiveAtStart = false),
            StandardCharsets.UTF_8,
        )
        seedV37World(1, "scenario_v38_deferred_repair")
        seedLegacyV26DeferredEvent(1)

        migrateTo("38", scenarioDir.toString())

        assertEquals(205, deferredYear(worldId = 1, nationId = 1, name = "이전지연"))
        assertEquals("190", deferredSourceBirthYear(worldId = 1, nationId = 1, name = "이전지연"))
        assertEquals("205", deferredSourceAppearanceYear(worldId = 1, nationId = 1, name = "이전지연"))
        assertEquals(1, deferredEventCount(worldId = 1, nationId = 1, name = "이전지연"))
        assertEquals(182, deferredYear(worldId = 1, nationId = 1, name = "보존대상"))
        assertEquals(1, deferredEventCount(worldId = 1, nationId = 1, name = "보존대상"))
        assertEquals(1, successfulMigrationCount("38"))

        migrateTo("38", scenarioDir.toString())

        assertEquals(1, deferredEventCount(worldId = 1, nationId = 1, name = "이전지연"))
        assertEquals(1, deferredEventCount(worldId = 1, nationId = 1, name = "보존대상"))
    }

    @Test
    fun `V38 leaves a legacy deferred action unchanged when only an RTK14 added officer matches`(@TempDir scenarioDir: Path) {
        Files.writeString(
            scenarioDir.resolve("scenario_v38_added_collision.json"),
            scenarioJson(
                "추가충돌",
                bornYear = 190,
                appearanceYear = 205,
                legacyActiveAtStart = false,
                rtk14Added = true,
            ),
            StandardCharsets.UTF_8,
        )
        seedV37World(1, "scenario_v38_added_collision")
        seedLegacyV26DeferredEvent(1, "추가충돌")
        val before = eventPayloads(1)

        migrateTo("38", scenarioDir.toString())

        assertEquals(before, eventPayloads(1))
        assertEquals(182, deferredYear(worldId = 1, nationId = 1, name = "추가충돌"))
        assertEquals(1, deferredEventCount(worldId = 1, nationId = 1, name = "추가충돌"))
    }

    @Test
    fun `V38 coalesces an active future officer with the same stale deferred identity`(@TempDir scenarioDir: Path) {
        Files.writeString(
            scenarioDir.resolve("scenario_v38_active_stale.json"),
            scenarioJson("동일장수", bornYear = 190, appearanceYear = 205),
            StandardCharsets.UTF_8,
        )
        seedRecordedV37World(1, "scenario_v38_active_stale", "동일장수", bornYear = 160)
        seedLegacyV26DeferredEvent(1, "동일장수")

        migrateTo("38", scenarioDir.toString())

        assertDeferredAndRemoved(worldId = 1, name = "동일장수", appearanceYear = 205)
        assertEquals("190", deferredSourceBirthYear(worldId = 1, nationId = 1, name = "동일장수"))
        assertEquals(1, deferredEventCount(worldId = 1, nationId = 1, name = "동일장수"))
    }

    @Test
    fun `V38 splits a grouped legacy event across explicit appearance years`(@TempDir scenarioDir: Path) {
        Files.writeString(
            scenarioDir.resolve("scenario_v38_split_legacy_event.json"),
            scenarioJson(
                SourceGeneral("분리갑", bornYear = 190, appearanceYear = 205, officerNumber = 17001, legacyActiveAtStart = false),
                SourceGeneral("분리을", bornYear = 191, appearanceYear = 206, officerNumber = 17002, legacyActiveAtStart = false),
            ),
            StandardCharsets.UTF_8,
        )
        seedV37World(1, "scenario_v38_split_legacy_event")
        seedLegacyV26DeferredEvent(1, "분리갑", "분리을")

        migrateTo("38", scenarioDir.toString())

        assertEquals(205, deferredYear(worldId = 1, nationId = 1, name = "분리갑"))
        assertEquals(206, deferredYear(worldId = 1, nationId = 1, name = "분리을"))
        assertEquals(1, deferredEventCount(worldId = 1, nationId = 1, name = "분리갑"))
        assertEquals(1, deferredEventCount(worldId = 1, nationId = 1, name = "분리을"))
        assertEquals(2, eventPayloads(1).size)
    }

    @Test
    fun `V38 leaves a near shaped mixed event byte identical`(@TempDir scenarioDir: Path) {
        Files.writeString(
            scenarioDir.resolve("scenario_v38_mixed_event.json"),
            scenarioJson("혼합보존", bornYear = 190, appearanceYear = 205, legacyActiveAtStart = false),
            StandardCharsets.UTF_8,
        )
        seedV37World(1, "scenario_v38_mixed_event")
        seedNearShapedMixedEvent(1, "혼합보존")
        val before = eventPayloads(1)

        migrateTo("38", scenarioDir.toString())

        assertEquals(before, eventPayloads(1))
        assertEquals(182, deferredYear(worldId = 1, nationId = 1, name = "혼합보존"))
    }

    @Test
    fun `V38 fails closed for a recorded V26 NPC world without an effective scenario`() {
        seedRecordedV37World(1, "scenario_v38_missing", "누락시나리오", bornYear = 160)

        assertFailsWith<FlywayException> { migrateTo("38") }

        assertTrue(generalExists(worldId = 1, generalId = 2001))
        assertEquals(0, deferredEventCount(worldId = 1, nationId = 1, name = "누락시나리오"))
        assertEquals(0, successfulMigrationCount("38"))
    }

    @Test
    fun `V38 rolls back when a selected external override is malformed`(@TempDir scenarioDir: Path) {
        Files.writeString(
            scenarioDir.resolve("scenario_v26_npc_lifecycle.json"),
            "{ malformed",
            StandardCharsets.UTF_8,
        )
        seedRecordedV37World(1, "scenario_v26_npc_lifecycle", "소제1", bornYear = 160)

        assertFailsWith<FlywayException> { migrateTo("38", scenarioDir.toString()) }

        assertTrue(generalExists(worldId = 1, generalId = 2001))
        assertEquals(0, deferredEventCount(worldId = 1, nationId = 1, name = "소제1"))
        assertEquals(0, successfulMigrationCount("38"))
    }

    @Test
    fun `V38 fails closed for duplicate future appearance identities`(@TempDir scenarioDir: Path) {
        Files.writeString(
            scenarioDir.resolve("scenario_v38_future_duplicate.json"),
            scenarioJson("중복미래", bornYear = 160, appearanceYear = 205),
            StandardCharsets.UTF_8,
        )
        seedRecordedV37World(1, "scenario_v38_future_duplicate", "중복미래", bornYear = 160)
        jdbc.update(
            """
            INSERT INTO general
                (world_id, id, name, nation_id, city_id, npc_state, affinity, born_year, dead_year, picture,
                 leadership, strength, intel, officer_level, turn_time, age, personal_code, special_code,
                 special2_code, meta)
            VALUES
                (1, 2002, '중복미래', 1, 3, 2, 1, 160, 240, 'duplicate.png',
                 20, 11, 48, 0, now(), 21, 'che_유지', 'None', 'None', '{"killturn":72}')
            """.trimIndent(),
        )

        assertFailsWith<FlywayException> { migrateTo("38", scenarioDir.toString()) }

        assertTrue(generalExists(worldId = 1, generalId = 2001))
        assertTrue(generalExists(worldId = 1, generalId = 2002))
        assertEquals(0, deferredEventCount(worldId = 1, nationId = 1, name = "중복미래"))
        assertEquals(0, successfulMigrationCount("38"))
    }

    private fun migrateTo(target: String, scenarioDir: String = "") {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .placeholders(mapOf("scenario_dir" to scenarioDir))
            .target(MigrationVersion.fromVersion(target))
            .load()
            .migrate()
    }

    private fun seedRecordedV37World(worldId: Int, scenarioCode: String, name: String, bornYear: Int) {
        seedV37World(worldId, scenarioCode, gennum = 1)
        jdbc.update(
            """
            INSERT INTO general
                (world_id, id, name, nation_id, city_id, npc_state, affinity, born_year, dead_year, picture,
                 leadership, strength, intel, officer_level, turn_time, age, personal_code, special_code,
                 special2_code, meta)
            VALUES
                (?, 2001, ?, 1, 3, 2, 1, ?, 240, 'future.png',
                 20, 11, 48, 0, now(), 21, 'che_유지', 'None', 'None', '{"killturn":72}')
            """.trimIndent(),
            worldId,
            name,
            bornYear,
        )
    }

    private fun seedV37World(worldId: Int, scenarioCode: String, gennum: Int = 0) {
        jdbc.update(
            "INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds) VALUES (?, ?, 181, 1, 3600)",
            worldId,
            scenarioCode,
        )
        jdbc.update(
            "INSERT INTO nation (world_id, id, name, color, meta) VALUES (?, 1, '한', '#ffffff', CAST(? AS jsonb))",
            worldId,
            "{\"gennum\":$gennum}",
        )
    }

    private fun seedLegacyV26DeferredEvent(worldId: Int, vararg names: String) {
        val deferredActions = names.ifEmpty { arrayOf("이전지연", "보존대상") }
            .mapIndexed { index, name -> legacyDeferredAction(name, affinity = if (index == 0) 97 else 33) }
        seedDeferredActions(worldId, deferredActions)
    }

    private fun seedNearShapedMixedEvent(worldId: Int, name: String) {
        seedDeferredActions(
            worldId,
            listOf(
                legacyDeferredAction(name),
                listOf(null, 33, "사용자정의", null, 1, null, 20, 11, 48, 0, 168, 240, "유지", null, null),
            ),
        )
    }

    private fun seedDeferredActions(worldId: Int, deferredActions: List<List<Any?>>) {
        jdbc.update(
            """
            INSERT INTO event (world_id, target_code, priority, condition, action)
            VALUES (?, 'Month', 1000, CAST(? AS jsonb), CAST(? AS jsonb))
            """.trimIndent(),
            worldId,
            MetaJson.encode(listOf("Date", ">=", 182, "1")),
            MetaJson.encode(deferredActions + listOf(listOf("DeleteEvent"))),
        )
    }

    private fun legacyDeferredAction(name: String, affinity: Int = 97): List<Any?> =
        listOf("RegNPC", affinity, name, null, 1, null, 20, 11, 48, 0, 168, 240, "유지", null, null)

    private fun eventPayloads(worldId: Int): List<String> = jdbc.queryForList(
        "SELECT condition::text || '|' || action::text FROM event WHERE world_id = ? ORDER BY id",
        String::class.java,
        worldId,
    )

    private fun assertDeferredAndRemoved(worldId: Int, name: String, appearanceYear: Int) {
        assertFalse(generalExists(worldId, 2001))
        assertEquals(appearanceYear, deferredYear(worldId = worldId, nationId = 1, name = name))
        assertEquals(appearanceYear.toString(), deferredSourceAppearanceYear(worldId = worldId, nationId = 1, name = name))
        assertEquals(1, deferredEventCount(worldId = worldId, nationId = 1, name = name))
        assertTrue(
            jdbc.queryForObject(
                "SELECT (meta ->> 'gennum')::integer = 0 FROM nation WHERE world_id = ? AND id = 1",
                Boolean::class.java,
                worldId,
            ) == true,
        )
    }

    private fun generalExists(worldId: Int, generalId: Int): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM general WHERE world_id = ? AND id = ?)",
        Boolean::class.java,
        worldId,
        generalId,
    ) == true

    private fun deferredYear(worldId: Int, nationId: Int, name: String): Int = jdbc.queryForObject(
        """
        SELECT (e.condition ->> 2)::integer
          FROM event e
         CROSS JOIN LATERAL jsonb_array_elements(e.action) action_row
         WHERE e.world_id = ?
           AND action_row ->> 4 = ?
           AND action_row ->> 2 = ?
        """.trimIndent(),
        Int::class.java,
        worldId,
        nationId.toString(),
        name,
    )!!

    private fun deferredSourceAppearanceYear(worldId: Int, nationId: Int, name: String): String = jdbc.queryForObject(
        """
        SELECT action_row ->> 17
          FROM event e
         CROSS JOIN LATERAL jsonb_array_elements(e.action) action_row
         WHERE e.world_id = ?
           AND action_row ->> 4 = ?
           AND action_row ->> 2 = ?
        """.trimIndent(),
        String::class.java,
        worldId,
        nationId.toString(),
        name,
    )!!

    private fun deferredSourceBirthYear(worldId: Int, nationId: Int, name: String): String = jdbc.queryForObject(
        """
        SELECT action_row ->> 10
          FROM event e
         CROSS JOIN LATERAL jsonb_array_elements(e.action) action_row
         WHERE e.world_id = ?
           AND action_row ->> 4 = ?
           AND action_row ->> 2 = ?
        """.trimIndent(),
        String::class.java,
        worldId,
        nationId.toString(),
        name,
    )!!

    private fun deferredEventCount(worldId: Int, nationId: Int, name: String): Int = jdbc.queryForObject(
        """
        SELECT count(*)
          FROM event e
         CROSS JOIN LATERAL jsonb_array_elements(e.action) action_row
         WHERE e.world_id = ?
           AND action_row ->> 0 IN ('RegNPC', 'RegNeutralNPC')
           AND action_row ->> 4 = ?
           AND action_row ->> 2 = ?
        """.trimIndent(),
        Int::class.java,
        worldId,
        nationId.toString(),
        name,
    )!!

    private fun seedDeferredEvent(worldId: Int, nationId: Int, name: String, appearanceYear: Int) {
        jdbc.update(
            """
            INSERT INTO event (world_id, target_code, priority, condition, action)
            VALUES (?, 'Month', 1000, CAST(? AS jsonb), CAST(? AS jsonb))
            """.trimIndent(),
            worldId,
            "[\"Date\",\">=\",$appearanceYear,\"1\"]",
            "[[\"RegNPC\",77,\"$name\",null,$nationId],[\"DeleteEvent\"]]",
        )
    }

    private fun successfulMigrationCount(version: String): Int = jdbc.queryForObject(
        "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success",
        Int::class.java,
        version,
    )!!

    private fun scenarioJson(
        name: String,
        bornYear: Int,
        appearanceYear: Int,
        legacyActiveAtStart: Boolean = true,
        rtk14Added: Boolean = false,
    ): String = scenarioJson(
        SourceGeneral(
            name = name,
            bornYear = bornYear,
            appearanceYear = appearanceYear,
            legacyActiveAtStart = legacyActiveAtStart,
            rtk14Added = rtk14Added,
        ),
    )

    private fun scenarioJson(vararg generals: SourceGeneral): String =
        """{"title":"V38 external","startYear":181,"map":{},"const":{},"nation":[],"general":[${generals.joinToString(",") { general -> sourceTuple(general) }}],"general_ex":[],"general_neutral":[],"diplomacy":[]}"""

    private fun sourceTuple(general: SourceGeneral): String =
        """[97,"${general.name}",null,1,null,20,11,48,0,${general.bornYear},240,"유지",null,null,77,88,${general.appearanceYear},${general.officerNumber},"female",50,30,300,"왕도",${general.rtk14Added},${general.legacyActiveAtStart}]"""
}
