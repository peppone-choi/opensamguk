package opensamguk.engine.boot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import opensamguk.common.constants.GameConst
import opensamguk.infra.seed.MapJson
import opensamguk.infra.seed.Scenario
import opensamguk.infra.seed.ScenarioJson
import opensamguk.logic.event.EventStore
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioMapSeedIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private var dockerAvailable = false

    @BeforeAll
    fun setUpClass() {
        dockerAvailable = runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false)
        if (!dockerAvailable) return

        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val dataSource: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
        jdbc = JdbcTemplate(dataSource)
    }

    @AfterAll
    fun tearDownClass() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @BeforeEach
    fun cleanRows() {
        if (!dockerAvailable) return
        jdbc.execute(
            "TRUNCATE world_state, nation, city, general, general_turn, nation_turn, diplomacy, rank_data, ng_games, event, game_kv RESTART IDENTITY CASCADE",
        )
    }

    @Test
    fun `scenario_2 seed uses miniche_b city catalog`() {
        assumeTrue(dockerAvailable, "Docker unavailable - scenario map seed IT skipped (not failed)")

        val bootstrap = SeedBootstrap(scenarioCode = "scenario_2", worldId = opensamguk.common.world.WorldId(1))

        assertTrue(bootstrap.ensureSeeded(jdbc), "fresh scenario_2 world is seeded")
        assertEquals(78, count("city"))
        assertEquals(0, count("nation"))
        assertEquals(0, count("general"))

        val city = jdbc.queryForMap("SELECT name, level, pop_max, agri_max, comm_max FROM city WHERE id = 1")
        assertEquals("낙양", city["name"].toString())
        assertEquals(8, (city["level"] as Number).toInt())
        assertEquals(668600, (city["pop_max"] as Number).toInt())
        assertEquals(7800, (city["agri_max"] as Number).toInt())
        assertEquals(8000, (city["comm_max"] as Number).toInt())

        val config = jdbc.queryForObject("SELECT config::text FROM world_state WHERE id = 1", String::class.java)!!
        assertTrue(config.contains("\"mapName\":\"miniche_b\"") || config.contains("\"mapName\": \"miniche_b\""), config)
    }

    @Test
    fun `absent QA turnterm retains the 60-minute seed cadence`() {
        assumeTrue(dockerAvailable, "Docker unavailable - scenario map seed IT skipped (not failed)")

        assertSeedCadence(qaTurnTerm = null, expectedTurnTerm = 60)
        cleanRows()
        assertSeedCadence(qaTurnTerm = "", expectedTurnTerm = 60)
    }

    @Test
    fun `QA turnterm one is applied before seeded general jitter`() {
        assumeTrue(dockerAvailable, "Docker unavailable - scenario map seed IT skipped (not failed)")

        assertSeedCadence(qaTurnTerm = "1", expectedTurnTerm = 1)
    }

    @Test
    fun `QA turnterm one produces deterministic general jitter offsets`() {
        assumeTrue(dockerAvailable, "Docker unavailable - scenario map seed IT skipped (not failed)")

        assertTrue(
            SeedBootstrap(
                qaTurnTerm = "1",
                worldId = opensamguk.common.world.WorldId(1),
            ).ensureSeeded(jdbc),
        )
        val firstOffsets = seededJitterOffsets()

        cleanRows()
        assertTrue(
            SeedBootstrap(
                qaTurnTerm = "1",
                worldId = opensamguk.common.world.WorldId(1),
            ).ensureSeeded(jdbc),
        )
        assertEquals(firstOffsets, seededJitterOffsets())
    }

    @Test
    fun `QA turnterm rejects every value other than one`() {
        for (invalid in listOf("0", "2", "60", "01", " 1", "1 ", "one")) {
            assertFailsWith<IllegalArgumentException>(invalid) {
                SeedBootstrap(
                    qaTurnTerm = invalid,
                    worldId = opensamguk.common.world.WorldId(1),
                )
            }
        }
    }

    @Test
    fun `external scenario_3190 seed preserves numeric identity and is idempotent`(@TempDir tempDir: Path) {
        assumeTrue(dockerAvailable, "Docker unavailable - scenario map seed IT skipped (not failed)")

        val scenarioFile = tempDir.resolve("scenario_3190.json")
        val actualPilotReport = copyScenario3190(scenarioFile)
        println("ScenarioMapSeedIT scenario_3190 input=${if (actualPilotReport != null) "actual-override" else "synthetic"}")
        val sourceScenario = ScenarioJson.loadScenario(Files.readString(scenarioFile))
        val bootstrap = SeedBootstrap(
            scenarioCode = "scenario_3190",
            scenarioDir = tempDir.toString(),
            worldId = opensamguk.common.world.WorldId(1),
        )

        assertTrue(bootstrap.ensureSeeded(jdbc), "fresh scenario_3190 world is seeded")
        assertEquals("scenario_3190", worldScenarioCode())
        if (actualPilotReport != null) {
            assertActualPilotFormulas(sourceScenario, actualPilotReport)
        } else {
            assertEquals(SYNTHETIC_COUNTS, seededTableCounts())
        }
        assertSeedIntegrity(sourceScenario.nations.size)

        assertEquals(
            linkedMapOf(
                "world_state.meta.scenario" to 3190,
                "ng_games.scenario" to 3190,
            ),
            linkedMapOf(
                "world_state.meta.scenario" to worldScenarioNumber(),
                "ng_games.scenario" to ngGameScenarioNumber(),
            ),
            "world_state.scenario_code=${worldScenarioCode()} must retain the same numeric scenario identity",
        )

        val firstCounts = seededTableCounts()
        assertTrue(!bootstrap.ensureSeeded(jdbc), "second seed is skipped")
        assertEquals(firstCounts, seededTableCounts(), "second seed preserves every seeded table count")
    }

    @Test
    fun `scenario code parser preserves zero and report overrides fail closed`(@TempDir tempDir: Path) {
        assertEquals(
            0,
            SeedBootstrap(scenarioCode = "scenario_0", worldId = opensamguk.common.world.WorldId(1)).scenarioNumber(),
        )

        for (code in MALFORMED_SCENARIO_CODES) {
            assertFailsWith<IllegalArgumentException>("$code must fail closed") {
                SeedBootstrap(scenarioCode = code, worldId = opensamguk.common.world.WorldId(1)).scenarioNumber()
            }
        }

        val scenario = tempDir.resolve("actual-scenario.json")
        val report = tempDir.resolve("actual-report.json")
        val target = tempDir.resolve("scenario_3190.json")
        Files.writeString(scenario, "actual pilot bytes", StandardCharsets.UTF_8)

        assertFailsWith<IllegalArgumentException>("scenario override requires a report override") {
            copyActualScenario3190(scenario.toString(), null, target)
        }
        assertFailsWith<IllegalArgumentException>("missing report file must fail before copying a scenario") {
            copyActualScenario3190(scenario.toString(), report.toString(), target)
        }

        Files.writeString(report, """{"scenario_sha256":"${"0".repeat(64)}}""", StandardCharsets.UTF_8)
        assertFailsWith<IllegalArgumentException>("stale report hash must fail closed") {
            copyActualScenario3190(scenario.toString(), report.toString(), target)
        }

        Files.writeString(
            report,
            """
            {
              "scenario_sha256":"${sha256(Files.readAllBytes(scenario))}",
              "importer_lifecycle":{"roster_total":280},
              "seed_readiness":{"seed_ready":true,"importer_ruler_gap_nation_ids":[]}
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )
        assertFailsWith<IllegalArgumentException>("mismatched lifecycle report must fail closed") {
            copyActualScenario3190(scenario.toString(), report.toString(), target)
        }

        val validReport = validPilotReportJson(Files.readAllBytes(scenario))
        Files.writeString(report, validReport, StandardCharsets.UTF_8)
        assertFailsWith<IllegalArgumentException>("quoted integer report values must fail closed") {
            Files.writeString(
                report,
                validReport.replace("\"roster_total\":280", "\"roster_total\":\"280\""),
                StandardCharsets.UTF_8,
            )
            copyActualScenario3190(scenario.toString(), report.toString(), target)
        }
        assertFailsWith<IllegalArgumentException>("quoted boolean report values must fail closed") {
            Files.writeString(
                report,
                validReport.replace("\"seed_ready\":true", "\"seed_ready\":\"true\""),
                StandardCharsets.UTF_8,
            )
            copyActualScenario3190(scenario.toString(), report.toString(), target)
        }
        assertFailsWith<IllegalArgumentException>("quoted integer list values must fail closed") {
            Files.writeString(
                report,
                validReport.replace(
                    "\"importer_ruler_gap_nation_ids\":[]",
                    "\"importer_ruler_gap_nation_ids\":[\"1\"]",
                ),
                StandardCharsets.UTF_8,
            )
            copyActualScenario3190(scenario.toString(), report.toString(), target)
        }
        assertFailsWith<IllegalArgumentException>("nullable string report values must reject booleans") {
            Files.writeString(
                report,
                validReport.replace("\"reason\":null", "\"reason\":true"),
                StandardCharsets.UTF_8,
            )
            copyActualScenario3190(scenario.toString(), report.toString(), target)
        }
        assertFailsWith<IllegalArgumentException>("required strings must reject numeric primitives") {
            (Json.parseToJsonElement("""{"scenario_sha256":1}""") as JsonObject)
                .requiredString("scenario_sha256")
        }

        val acceptedBytes = "accepted pilot bytes".toByteArray(StandardCharsets.UTF_8)
        val replacementBytes = "replacement pilot bytes".toByteArray(StandardCharsets.UTF_8)
        Files.write(scenario, acceptedBytes)
        Files.writeString(report, validPilotReportJson(acceptedBytes), StandardCharsets.UTF_8)
        assertEquals(
            264,
            copyActualScenario3190(
                scenarioOverride = scenario.toString(),
                reportOverride = report.toString(),
                target = target,
                afterScenarioRead = { Files.write(it, replacementBytes) },
            )?.activeAtStart,
            "report is accepted against the bytes captured before source replacement",
        )
        assertContentEquals(acceptedBytes, Files.readAllBytes(target), "staged bytes are the SHA-validated bytes")
        assertEquals(sha256(acceptedBytes), sha256(Files.readAllBytes(target)), "staged SHA matches report-bound bytes")
        assertContentEquals(replacementBytes, Files.readAllBytes(scenario), "regression seam replaced only the source after read")
    }

    @Test
    fun `scenario code validation runs after gates but before resource reads or writes`(@TempDir tempDir: Path) {
        assumeTrue(dockerAvailable, "Docker unavailable - scenario map seed IT skipped (not failed)")

        val malformed = "scenario_3190.json"
        val disabled = SeedBootstrap(
            scenarioCode = malformed,
            seedEnabled = false,
            scenarioDir = tempDir.toString(),
            worldId = opensamguk.common.world.WorldId(1),
        )
        assertTrue(!disabled.ensureSeeded(jdbc), "disabled gate precedes scenario-code parsing")
        assertEquals(0, count("world_state"))

        assertTrue(
            SeedBootstrap(scenarioCode = "scenario_2", worldId = opensamguk.common.world.WorldId(1)).ensureSeeded(jdbc),
        )
        assertTrue(
            !SeedBootstrap(
                scenarioCode = malformed,
                scenarioDir = tempDir.toString(),
                worldId = opensamguk.common.world.WorldId(1),
            ).ensureSeeded(jdbc),
            "existing-world gate precedes scenario-code parsing",
        )

        cleanRows()
        assertFailsWith<IllegalArgumentException>("fresh malformed code must fail before a resource lookup") {
            SeedBootstrap(
                scenarioCode = malformed,
                scenarioDir = tempDir.toString(),
                worldId = opensamguk.common.world.WorldId(1),
            ).ensureSeeded(jdbc)
        }
        assertEquals(0, count("world_state"), "malformed scenario codes make no writes")
    }

    private fun assertSeedCadence(qaTurnTerm: String?, expectedTurnTerm: Int) {
        assertTrue(
            SeedBootstrap(
                qaTurnTerm = qaTurnTerm,
                worldId = opensamguk.common.world.WorldId(1),
            ).ensureSeeded(jdbc),
            "fresh world is seeded",
        )

        val world = jdbc.queryForMap(
            """
            SELECT tick_seconds, turn_term, config ->> 'turnterm' AS config_turnterm
              FROM world_state
             WHERE id = 1
            """.trimIndent(),
        )
        assertEquals(expectedTurnTerm * 60, (world["tick_seconds"] as Number).toInt())
        assertEquals(expectedTurnTerm, (world["turn_term"] as Number).toInt())
        assertEquals(expectedTurnTerm.toString(), world["config_turnterm"])
        assertEquals(
            expectedTurnTerm.toString(),
            jdbc.queryForObject(
                """
                SELECT value::text
                  FROM game_kv
                 WHERE "table" = 'game_env'
                   AND namespace = 'game_env'
                   AND key = 'turnterm'
                """.trimIndent(),
                String::class.java,
            ),
        )
        assertEquals(
            expectedTurnTerm.toString(),
            jdbc.queryForObject("SELECT env ->> 'turnterm' FROM ng_games", String::class.java),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM general g
                  JOIN world_state w ON w.id = g.world_id
                 WHERE g.turn_time < w.start_time
                    OR g.turn_time >= w.start_time + (? * INTERVAL '1 second')
                """.trimIndent(),
                Int::class.java,
                expectedTurnTerm * 60,
            ),
            "every seeded general turn_time is within the configured cadence range",
        )
    }

    private fun seededJitterOffsets(): List<Long> =
        jdbc.queryForList(
            """
            SELECT (EXTRACT(EPOCH FROM g.turn_time - w.start_time) * 1000000)::bigint
              FROM general g
              JOIN world_state w ON w.id = g.world_id
             ORDER BY g.id
            """.trimIndent(),
            Long::class.java,
        )

    private fun copyScenario3190(target: Path): ActualPilotReport? =
        copyActualScenario3190(
            scenarioOverride = overrideValue("opensamguk.scenario3190TestFile", "SCENARIO_3190_TEST_FILE"),
            reportOverride = overrideValue("opensamguk.scenario3190TestReportFile", "SCENARIO_3190_TEST_REPORT_FILE"),
            target = target,
        )

    private fun copyActualScenario3190(
        scenarioOverride: String?,
        reportOverride: String?,
        target: Path,
        afterScenarioRead: ((Path) -> Unit)? = null,
    ): ActualPilotReport? {
        if (scenarioOverride == null && reportOverride == null) {
            val stream = javaClass.classLoader.getResourceAsStream("scenario/scenario_3190_test.json")
                ?: error("resource not found: scenario/scenario_3190_test.json")
            stream.use { Files.copy(it, target, REPLACE_EXISTING) }
            return null
        }

        require(scenarioOverride != null && reportOverride != null) {
            "scenario_3190 actual pilot requires both scenario and report overrides"
        }
        val scenario = Path.of(scenarioOverride)
        val report = Path.of(reportOverride)
        require(Files.isRegularFile(scenario)) { "SCENARIO_3190_TEST_FILE is not a file: $scenario" }
        require(Files.isRegularFile(report)) { "SCENARIO_3190_TEST_REPORT_FILE is not a file: $report" }
        val scenarioBytes = Files.readAllBytes(scenario)
        afterScenarioRead?.invoke(scenario)
        val pilotReport = readActualPilotReport(scenarioBytes, report)
        Files.write(target, scenarioBytes)
        return pilotReport
    }

    private fun overrideValue(systemProperty: String, environment: String): String? =
        System.getProperty(systemProperty)?.takeIf { it.isNotBlank() }
            ?: System.getenv(environment)?.takeIf { it.isNotBlank() }

    private fun readActualPilotReport(scenarioBytes: ByteArray, report: Path): ActualPilotReport {
        val root = requireNotNull(Json.parseToJsonElement(Files.readString(report)) as? JsonObject) {
            "scenario_3190 pilot report must be a JSON object"
        }
        val scenarioSha256 = root.requiredString("scenario_sha256")
        require(scenarioSha256 == sha256(scenarioBytes)) {
            "scenario_3190 pilot report SHA-256 does not match the scenario bytes"
        }
        val lifecycle = root.requiredObject("importer_lifecycle")
        val readiness = root.requiredObject("seed_readiness")
        return ActualPilotReport(
            rosterTotal = lifecycle.requiredInt("roster_total"),
            activeAtStart = lifecycle.requiredInt("active_at_start"),
            deferredUnderage = lifecycle.requiredInt("deferred_underage"),
            deadAtStart = lifecycle.requiredInt("dead_at_start"),
            importerEligibleTotal = root.requiredInt("importer_eligible_total"),
            emittedAffiliatedCount = root.requiredInt("affiliated_count"),
            emittedNeutralCount = root.requiredInt("neutral_count"),
            seedReady = readiness.requiredBoolean("seed_ready"),
            rulerGapNationIds = readiness.requiredIntList("importer_ruler_gap_nation_ids"),
            readinessReason = readiness.requiredNullableString("reason"),
        )
    }

    private fun JsonObject.requiredObject(name: String): JsonObject =
        requireNotNull(this[name] as? JsonObject) { "scenario_3190 pilot report requires object '$name'" }

    private fun JsonObject.requiredString(name: String): String =
        requiredPrimitive(name).also { value ->
            require(value.isString) {
                "scenario_3190 pilot report requires string '$name'"
            }
        }.content

    private fun JsonObject.requiredInt(name: String): Int {
        val value = requiredPrimitive(name)
        require(!value.isString) {
            "scenario_3190 pilot report requires integer '$name'"
        }
        return requireNotNull(value.intOrNull) {
            "scenario_3190 pilot report requires integer '$name'"
        }
    }

    private fun JsonObject.requiredBoolean(name: String): Boolean {
        val value = requiredPrimitive(name)
        require(!value.isString) {
            "scenario_3190 pilot report requires boolean '$name'"
        }
        return requireNotNull(value.booleanOrNull) {
            "scenario_3190 pilot report requires boolean '$name'"
        }
    }

    private fun JsonObject.requiredPrimitive(name: String): JsonPrimitive =
        requireNotNull(this[name] as? JsonPrimitive) {
            "scenario_3190 pilot report requires primitive '$name'"
        }

    private fun JsonObject.requiredNullableString(name: String): String? {
        val value = requireNotNull(this[name]) {
            "scenario_3190 pilot report requires nullable string '$name'"
        }
        return when (value) {
            JsonNull -> null
            is JsonPrimitive -> {
                require(value.isString) { "scenario_3190 pilot report requires nullable string '$name'" }
                value.content
            }
            else -> throw IllegalArgumentException("scenario_3190 pilot report requires nullable string '$name'")
        }
    }

    private fun JsonObject.requiredIntList(name: String): List<Int> {
        val values = requireNotNull(this[name] as? JsonArray) {
            "scenario_3190 pilot report requires integer array '$name'"
        }
        return values.mapIndexed { index, value ->
            val primitive = requireNotNull(value as? JsonPrimitive) {
                "scenario_3190 pilot report requires integer '$name[$index]'"
            }
            require(!primitive.isString) {
                "scenario_3190 pilot report requires integer '$name[$index]'"
            }
            requireNotNull(primitive.intOrNull) {
                "scenario_3190 pilot report requires integer '$name[$index]'"
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun validPilotReportJson(scenarioBytes: ByteArray): String =
        """
        {
          "scenario_sha256":"${sha256(scenarioBytes)}",
          "importer_lifecycle":{
            "roster_total":280,
            "active_at_start":264,
            "deferred_underage":1,
            "dead_at_start":15
          },
          "importer_eligible_total":264,
          "affiliated_count":249,
          "neutral_count":31,
          "seed_readiness":{
            "seed_ready":true,
            "importer_ruler_gap_nation_ids":[],
            "reason":null
          }
        }
        """.trimIndent()

    private fun assertActualPilotFormulas(scenario: Scenario, report: ActualPilotReport) {
        val seedGenerals = scenario.initGenerals()
        val activeGenerals = seedGenerals.filter { general ->
            val birth = general.bornYear ?: 180
            val death = general.deadYear ?: 300
            death > scenario.startYear && birth + GameConst.adultAge.toInt() <= scenario.startYear
        }
        val deferredGenerals = seedGenerals.filter { general ->
            val birth = general.bornYear ?: 180
            val death = general.deadYear ?: 300
            death > scenario.startYear && birth + GameConst.adultAge.toInt() > scenario.startYear
        }
        val deferredBirths = deferredGenerals.map { it.bornYear ?: 180 }.toSet()
        val deadGenerals = seedGenerals.filter { (it.deadYear ?: 300) <= scenario.startYear }
        val mapCityCount = ScenarioJson.loadMapCities(
            readResource("map/${MapJson.resourceCode(scenarioMapName(scenario))}.json"),
        ).size
        val expectedNationTurns = scenario.nations.sumOf { nation ->
            (13 - nationChiefLevel(nation.scale)) * 12
        }
        val defaultEventCount = EventStore.defaultWireRows().size

        assertEquals(280, report.rosterTotal, "Task 4 emitted roster total")
        assertEquals(264, report.activeAtStart, "Task 4 active-at-start total")
        assertEquals(1, report.deferredUnderage, "Task 4 deferred-underage total")
        assertEquals(15, report.deadAtStart, "Task 4 dead-at-start total")
        assertEquals(264, report.importerEligibleTotal, "Task 4 importer-eligible total")
        assertEquals(249, report.emittedAffiliatedCount, "Task 4 emitted affiliated total")
        assertEquals(31, report.emittedNeutralCount, "Task 4 emitted neutral total")
        assertTrue(report.seedReady, "Task 4 report marks scenario_3190 seed-ready")
        assertEquals(emptyList(), report.rulerGapNationIds, "Task 4 report has no importer ruler gaps")
        assertEquals(null, report.readinessReason, "Task 4 report has no seed-readiness failure reason")
        assertEquals(21, scenario.nations.size, "scenario_3190 has 21 nations")
        assertEquals(94, mapCityCount, "scenario_3190 uses the 94-city che map")
        assertEquals(report.rosterTotal, seedGenerals.size, "report binds the complete emitted roster")
        assertEquals(report.activeAtStart, activeGenerals.size, "report binds importer active-at-start rows")
        assertEquals(report.deferredUnderage, deferredGenerals.size, "report binds deferred underage rows")
        assertEquals(report.deferredUnderage, deferredBirths.size, "one deferred event exists for each deferred birth")
        assertEquals(report.deadAtStart, deadGenerals.size, "report binds dead-at-start rows")
        assertEquals(report.importerEligibleTotal, report.activeAtStart, "report lifecycle totals agree")
        assertEquals(report.emittedAffiliatedCount, seedGenerals.count { it.nationId != 0 })
        assertEquals(report.emittedNeutralCount, seedGenerals.count { it.nationId == 0 })
        assertEquals(21, activeGenerals.count { it.nationId != 0 && it.officerLevel == 12 }, "Task 4 ruler total")
        assertTrue(!scenario.ignoreDefaultEvents, "actual pilot retains default event rows")
        assertEquals(0, scenario.events.size, "actual pilot has no scenario-owned event rows")
        assertEquals(12, defaultEventCount, "default event row count")
        assertEquals(1464, expectedNationTurns, "scenario_3190 nation-turn formula")
        assertEquals(1, count("world_state"))
        assertEquals(22, count("game_kv"))
        assertEquals(scenario.nations.size, count("nation"))
        assertEquals(mapCityCount, count("city"))
        assertEquals(report.importerEligibleTotal, count("general"))
        assertEquals(report.importerEligibleTotal * 30, count("general_turn"))
        assertEquals(expectedNationTurns, count("nation_turn"))
        assertEquals(scenario.nations.size * (scenario.nations.size - 1), count("diplomacy"))
        assertEquals(report.importerEligibleTotal * 37, count("rank_data"))
        assertEquals(1, count("ng_games"))
        assertEquals(233, countWhere("general g", "g.nation_id <> 0"), "active affiliated generals")
        assertEquals(31, countWhere("general g", "g.nation_id = 0"), "active neutral generals")
        assertEquals(21, countWhere("general g", "g.nation_id <> 0 AND g.officer_level = 12"))
        assertEquals(defaultEventCount + deferredBirths.size, count("event"), "default plus deferred event rows")
        assertEquals(13, count("event"), "scenario_3190 event total")
        println(
            "ScenarioMapSeedIT scenario_3190 actual lifecycle " +
                "roster_total=${report.rosterTotal} active_at_start=${report.activeAtStart} " +
                "deferred_underage=${report.deferredUnderage} dead_at_start=${report.deadAtStart} " +
                "seed_ready=${report.seedReady} gaps=${report.rulerGapNationIds} reason=${report.readinessReason} " +
                "db_general=${count("general")} db_general_turn=${count("general_turn")} " +
                "db_rank_data=${count("rank_data")} db_event=${count("event")}",
        )
    }

    private fun scenarioMapName(scenario: Scenario): String {
        val merged = LinkedHashMap<String, Any?>()
        merged.putAll(scenario.map)
        merged.putAll(scenario.const)
        return merged["mapName"] as? String ?: "che"
    }

    private fun nationChiefLevel(scale: Int): Int = when (scale) {
        9, 8, 7, 6 -> 5
        5, 4 -> 7
        3, 2 -> 9
        else -> 11
    }

    private fun readResource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: error("resource not found: $path")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    private fun seededTableCounts(): Map<String, Int> = linkedMapOf(
        "world_state" to count("world_state"),
        "game_kv" to count("game_kv"),
        "nation" to count("nation"),
        "city" to count("city"),
        "general" to count("general"),
        "general_turn" to count("general_turn"),
        "nation_turn" to count("nation_turn"),
        "diplomacy" to count("diplomacy"),
        "rank_data" to count("rank_data"),
        "ng_games" to count("ng_games"),
        "event" to count("event"),
    )

    private fun assertSeedIntegrity(expectedNationCount: Int) {
        assertEquals(0, countWhere("city c", "c.nation_id <> 0 AND NOT EXISTS (SELECT 1 FROM nation n WHERE n.id = c.nation_id)"))
        assertEquals(0, countWhere("general g", "g.nation_id <> 0 AND NOT EXISTS (SELECT 1 FROM nation n WHERE n.id = g.nation_id)"))
        assertEquals(0, countWhere("general g", "NOT EXISTS (SELECT 1 FROM city c WHERE c.id = g.city_id)"))
        assertEquals(0, countWhere("general_turn gt", "NOT EXISTS (SELECT 1 FROM general g WHERE g.id = gt.general_id)"))
        assertEquals(0, countWhere("nation_turn nt", "NOT EXISTS (SELECT 1 FROM nation n WHERE n.id = nt.nation_id)"))
        assertEquals(0, countWhere("rank_data r", "NOT EXISTS (SELECT 1 FROM general g WHERE g.id = r.general_id)"))
        assertEquals(
            0,
            countWhere(
                "diplomacy d",
                "NOT EXISTS (SELECT 1 FROM nation n WHERE n.id = d.src_nation_id) OR " +
                    "NOT EXISTS (SELECT 1 FROM nation n WHERE n.id = d.dest_nation_id)",
            ),
        )
        assertEquals(
            0,
            countWhere(
                "nation n",
                "NOT EXISTS (SELECT 1 FROM city c WHERE c.id = n.capital_city_id AND c.nation_id = n.id)",
            ),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM (
                    SELECT n.id
                      FROM nation n
                      LEFT JOIN general g ON g.nation_id = n.id AND g.officer_level = 12
                     GROUP BY n.id
                    HAVING count(g.id) <> 1
                  ) mismatched
                """.trimIndent(),
                Int::class.java,
            ),
        )
        assertEquals(expectedNationCount, count("nation"))
    }

    private fun worldScenarioCode(): String =
        jdbc.queryForObject("SELECT scenario_code FROM world_state WHERE id = 1", String::class.java)!!

    private fun worldScenarioNumber(): Int =
        jdbc.queryForObject("SELECT (meta ->> 'scenario')::integer FROM world_state WHERE id = 1", Int::class.java)!!

    private fun ngGameScenarioNumber(): Int =
        jdbc.queryForObject("SELECT scenario FROM ng_games", Int::class.java)!!

    private fun countWhere(table: String, predicate: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table WHERE $predicate", Int::class.java) ?: 0

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java) ?: 0

    private data class ActualPilotReport(
        val rosterTotal: Int,
        val activeAtStart: Int,
        val deferredUnderage: Int,
        val deadAtStart: Int,
        val importerEligibleTotal: Int,
        val emittedAffiliatedCount: Int,
        val emittedNeutralCount: Int,
        val seedReady: Boolean,
        val rulerGapNationIds: List<Int>,
        val readinessReason: String?,
    )

    private companion object {
        val SYNTHETIC_COUNTS: Map<String, Int> = linkedMapOf(
            "world_state" to 1,
            "game_kv" to 22,
            "nation" to 2,
            "city" to 78,
            "general" to 2,
            "general_turn" to 60,
            "nation_turn" to 48,
            "diplomacy" to 2,
            "rank_data" to 74,
            "ng_games" to 1,
            "event" to 0,
        )
        val MALFORMED_SCENARIO_CODES = listOf(
            "scenario_-1",
            "scenario_+1",
            "scenario_01",
            "scenario_3190_test",
            "scenario_3190.json",
            " scenario_3190",
            "scenario_3190 ",
            "../scenario_3190",
            "scenario_3190/../scenario_3190",
            "scenario_2147483648",
        )
    }
}
