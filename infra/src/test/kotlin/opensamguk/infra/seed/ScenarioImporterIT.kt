package opensamguk.infra.seed

import opensamguk.common.world.WorldId
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.charset.StandardCharsets
import java.time.OffsetDateTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * F1a gate — the scenario-seed importer IT (Testcontainers `postgres:16-alpine` + Flyway baseline).
 *
 * Asserts the seed counts (`world_state`=1, `nation`=2, `city`=94[소유24+공백지70], `general`=229,
 * per-general `rank_data`=37 and `general_turn`=30) and that a SECOND `importAll`/seed is a no-op
 * (the emptiness gate inserts 0 new rows). The macOS Testcontainers quirks (api.version 1.44,
 * DOCKER_CONTEXT=default, Ryuk disabled) are wired in `infra/build.gradle.kts tasks.test`. If Docker
 * is unavailable the test is SKIPPED (assumeTrue), not failed.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioImporterIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private var dockerAvailable = false
    private val canonicalWorldId = WorldId(1)

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

    @AfterTest
    fun cleanRows() {
        if (!dockerAvailable) return
        // Truncate all seeded tables so each @Test starts from an empty world (idempotency depends on
        // world_state emptiness, asserted per-test).
        jdbc.execute(
            "TRUNCATE world_state, nation, city, general, general_turn, nation_turn, diplomacy, rank_data, ng_games, event, game_kv RESTART IDENTITY CASCADE",
        )
    }

    private fun newImporter(
        showImageLevel: Int = 3,
        extendedGeneral: Boolean = true,
    ): ScenarioImporter {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_1010.json"))
        val cities = ScenarioJson.loadMapCities(readResource("map/che.json"))
        return ScenarioImporter(
            scenario = scenario,
            cities = cities,
            showImageLevel = showImageLevel,
            extendedGeneral = extendedGeneral,
        )
    }

    // 빼섭(2번째 서버)용 scenario_1030 군웅할거. cities는 che 풀맵 공용(맵 바운드, 시나리오 무관).
    private fun newImporter1030(): ScenarioImporter {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_1030.json"))
        val cities = ScenarioJson.loadMapCities(readResource("map/che.json"))
        return ScenarioImporter(scenario = scenario, cities = cities, scenarioCode = "scenario_1030")
    }

    private fun newImporter2(): ScenarioImporter {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_2.json"))
        val cities = ScenarioJson.loadMapCities(readResource("map/miniche_b.json"))
        return ScenarioImporter(scenario = scenario, cities = cities, scenarioCode = "scenario_2", scenarioNumber = 2)
    }

    @Test
    fun `importAll seeds the A-minimal world with the expected row counts`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val counts = newImporter().importAll(jdbc, canonicalWorldId)

        assertEquals(1, counts.worldState)
        assertTrue(counts.gameEnv > 0)
        assertEquals(2, counts.nation)
        // che 풀맵 94도시 = 소유 24(후한 14 + 황건적 10) + 공백지 70(nation_id=0).
        assertEquals(94, counts.city)
        assertEquals(229, counts.general)
        assertEquals(229 * 30, counts.generalTurn)
        assertEquals(229 * 37, counts.rankData)
        assertEquals(1, counts.ngGames)
        assertEquals(counts.event, count("event"))
        assertTrue(counts.event > 1, "1010 stores defaults plus the scenario event")
        assertEquals("pre_month", jdbc.queryForObject("SELECT target_code FROM event ORDER BY id ASC LIMIT 1", String::class.java))
        assertEquals(
            "Month",
            jdbc.queryForObject("SELECT target_code FROM event ORDER BY id DESC LIMIT 1", String::class.java),
        )
        assertEquals(
            77,
            jdbc.queryForObject("SELECT count(*) FROM event WHERE action::text LIKE '%RegNPC%'", Int::class.java),
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM general WHERE born_year + 14 > 181 OR dead_year <= 181",
                Int::class.java,
            ),
        )
        val firstDeferred = jdbc.queryForMap(
            """
            SELECT condition::text condition,
                   action::text action,
                   jsonb_array_length(action) AS action_count,
                   action -> (jsonb_array_length(action) - 1) ->> 0 AS last_action
              FROM event
             WHERE action::text LIKE '%소제1%'
            """.trimIndent(),
        )
        assertTrue(sso(firstDeferred["condition"]).contains("182"))
        assertTrue(sso(firstDeferred["action"]).endsWith("[\"DeleteEvent\"]]"))
        assertTrue(soi(firstDeferred["action_count"]) > 2, "same adult-year generals are grouped in one event")
        assertEquals("DeleteEvent", sso(firstDeferred["last_action"]))

        assertEquals(1, count("world_state"))
        assertEquals(counts.gameEnv, jdbc.queryForObject("SELECT count(*) FROM game_kv WHERE \"table\" = 'game_env' AND namespace = 'game_env'", Int::class.java))
        assertEquals(
            "30000",
            jdbc.queryForObject(
                "SELECT value::text FROM game_kv WHERE \"table\" = 'game_env' AND namespace = 'game_env' AND key = 'refreshLimit'",
                String::class.java,
            ),
        )
        assertEquals(
            "0",
            jdbc.queryForObject(
                "SELECT value::text FROM game_kv WHERE \"table\" = 'game_env' AND namespace = 'game_env' AND key = 'fiction'",
                String::class.java,
            ),
        )
        assertEquals(2, count("nation"))
        assertEquals(94, count("city"))
        // 공백지 70(nation_id=0).
        assertEquals(70, jdbc.queryForObject("SELECT count(*) FROM city WHERE nation_id = 0", Int::class.java))
        // 소유 = 시나리오 nation.cities 기준(후한 14 + 황건적 10). 이 fix는 1010에 무변 —
        // cities_1010.json의 baked nation_id와 nation.cities가 동일 집합이므로 소유 24가 그대로 유지.
        assertEquals(14, jdbc.queryForObject("SELECT count(*) FROM city WHERE nation_id = 1", Int::class.java))
        assertEquals(10, jdbc.queryForObject("SELECT count(*) FROM city WHERE nation_id = 2", Int::class.java))
        // 공백지 초기스탯 = CityConstBase 베이스(점령지 70%max 부스트 없음). 성도: pop 150000·wall 5000·trust 50.
        val sd = jdbc.queryForMap("SELECT pop, wall, trust FROM city WHERE name = '성도'")
        assertEquals(150000, (sd["pop"] as Number).toInt())
        assertEquals(5000, (sd["wall"] as Number).toInt())
        assertEquals(50.0, (sd["trust"] as Number).toDouble())
        // 점령지는 70%max 불변(parity). 낙양: pop=ratio70(835700)=584990, trust 80.
        val ly = jdbc.queryForMap("SELECT pop, trust FROM city WHERE name = '낙양'")
        assertEquals(584990, (ly["pop"] as Number).toInt())
        assertEquals(80.0, (ly["trust"] as Number).toDouble())
        assertEquals(229, count("general"))
        assertEquals(229 * 30, count("general_turn"))
        assertEquals(229 * 37, count("rank_data"))
        assertEquals(1, count("ng_games"))

        // diplomacy: 2 nations → 2 ordered neutral pairs (no JSON overrides in 1010).
        assertEquals(2, count("diplomacy"))

        // nation_turn: 후한 lv7→chief 5 ⇒ seats 5..12 (8) ×12; 황건적 lv2→chief 9 ⇒ seats 9..12 (4) ×12.
        assertEquals((8 + 4) * 12, count("nation_turn"))

        // Per-general invariants — sample the boy-emperor 소제1 (id 1001) to lock the stat mapping.
        val every37 = jdbc.queryForObject(
            "SELECT min(c) = 37 AND max(c) = 37 FROM (SELECT count(*) c FROM rank_data GROUP BY general_id) s",
            Boolean::class.java,
        )
        assertTrue(every37 == true, "every general has exactly 37 rank_data rows")
        val every30 = jdbc.queryForObject(
            "SELECT min(c) = 30 AND max(c) = 30 FROM (SELECT count(*) c FROM general_turn GROUP BY general_id) s",
            Boolean::class.java,
        )
        assertTrue(every30 == true, "every general has exactly 30 general_turn rows")
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM general WHERE picture ~ '^[0-9]+$'", Int::class.java),
            "numeric scenario picture ids require stored icon resolution and otherwise fall back to default.jpg",
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                """
                SELECT count(*) FROM general
                 WHERE age = 14
                   AND ((nation_id = 0 AND officer_level <> 0) OR (nation_id <> 0 AND officer_level <> 1))
                """.trimIndent(),
                Int::class.java,
            ),
            "age-14 generals use PHP new-general officer normalization",
        )
        assertTrue(
            jdbc.queryForObject(
                """
                SELECT count(*) > 0 FROM general
                 WHERE (meta ->> 'killturn')::integer <>
                       ((dead_year - 181) * 12 * 3)
                """.trimIndent(),
                Boolean::class.java,
            ) == true,
            "seeded generals carry PHP killturn jitter, not the zero-jitter baseline",
        )
        assertTrue(
            jdbc.queryForObject("SELECT count(DISTINCT turn_time) > 1 FROM general", Boolean::class.java) == true,
            "seeded turn_time uses PHP getRandTurn jitter per general",
        )
        assertTrue(
            jdbc.queryForObject("SELECT count(DISTINCT city_id) > 3 FROM general", Boolean::class.java) == true,
            "active generals persist PHP city choice results instead of collapsing to capitals/default city",
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM general WHERE affinity IS NULL OR (affinity NOT BETWEEN 1 AND 150 AND affinity <> 999)",
                Int::class.java,
            ),
            "InitScenario affinity RNG results are persisted, including PHP's 900+ to 999 normalization",
        )
        assertEquals(
            999,
            jdbc.queryForObject("SELECT affinity FROM general WHERE name = 'ⓝ우길'", Int::class.java),
            "scenario affinity 900+ normalizes to PHP sentinel 999",
        )
        val halfAwaySpec = jdbc.queryForMap(
            """
            SELECT (meta ->> 'specage')::int AS specage,
                   (meta ->> 'specage2')::int AS specage2
              FROM general
             WHERE name = 'ⓝ우길'
            """.trimIndent(),
        )
        assertEquals(53, soi(halfAwaySpec["specage"]), "seed specage uses PHP half-away rounding")
        assertEquals(55, soi(halfAwaySpec["specage2"]), "seed specage2 uses PHP half-away rounding")
        val seedMeta = jdbc.queryForMap(
            """
            SELECT (meta ->> 'npc_org')::int AS npc_org,
                   (meta ->> 'dedlevel')::int AS dedlevel,
                   (meta ->> 'specage')::int AS specage,
                   (meta ->> 'specage2')::int AS specage2
              FROM general
             WHERE name = 'ⓝ간옹'
            """.trimIndent(),
        )
        assertEquals(2, soi(seedMeta["npc_org"]), "active scenario general meta carries PHP npc_org")
        assertEquals(1, soi(seedMeta["dedlevel"]), "active scenario general meta carries PHP dedlevel seed")
        assertEquals(22, soi(seedMeta["specage"]), "active scenario general meta carries PHP specage")
        assertEquals(28, soi(seedMeta["specage2"]), "active scenario general meta carries PHP specage2")
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM general WHERE nation_id <> 0 AND officer_level NOT IN (1, 12)", Int::class.java),
            "PHP normalizes seeded officer_level=0 to 1 while preserving explicit rulers",
        )
        assertEquals(12, jdbc.queryForObject("SELECT officer_level FROM general WHERE name = 'ⓝ장각'", Int::class.java))
        assertEquals(12, jdbc.queryForObject("SELECT officer_level FROM general WHERE name = 'ⓝ하진'", Int::class.java))
        assertEquals(
            0,
            jdbc.queryForObject("SELECT count(*) FROM general WHERE personal_code = 'None'", Int::class.java),
            "InitScenario ego RNG results are consumed and persisted",
        )
        assertTrue(
            jdbc.queryForObject("SELECT count(*) > 0 FROM general WHERE meta ? 'npcmsg'", Boolean::class.java) == true,
            "fresh seed preserves scenario npcmsg in general meta",
        )

        assertEquals(
            0,
            jdbc.queryForObject(
                """
                SELECT count(*) FROM general
                 WHERE (npc_state = 2 AND name NOT LIKE 'ⓝ%')
                    OR (npc_state = 6 AND name NOT LIKE 'ⓤ%')
                """.trimIndent(),
                Int::class.java,
            ),
            "active scenario NPC names mirror GeneralBuilder prefixList",
        )
        assertEquals(
            20,
            jdbc.queryForObject("SELECT min(start_age) FROM general", Int::class.java),
            "PHP GeneralBuilder omits startage so schema default 20 is used",
        )
        assertEquals(20, jdbc.queryForObject("SELECT max(start_age) FROM general", Int::class.java))

        val domestic = jdbc.queryForMap(
            """
            SELECT special_code, special2_code, meta ->> 'special' AS meta_special,
                   meta ->> 'special2' AS meta_special2, meta ->> 'start_age' AS meta_start_age
              FROM general
             WHERE name = 'ⓝ간옹'
            """.trimIndent(),
        )
        assertEquals("che_경작", sso(domestic["special_code"]))
        assertEquals("None", sso(domestic["special2_code"]))
        assertEquals("che_경작", sso(domestic["meta_special"]))
        assertEquals("None", sso(domestic["meta_special2"]))
        assertEquals("20", sso(domestic["meta_start_age"]))

        val war = jdbc.queryForMap(
            """
            SELECT special_code, special2_code, meta ->> 'special' AS meta_special,
                   meta ->> 'special2' AS meta_special2
              FROM general
             WHERE name = 'ⓝ노식'
            """.trimIndent(),
        )
        assertEquals("None", sso(war["special_code"]))
        assertEquals("che_징병", sso(war["special2_code"]))
        assertEquals("None", sso(war["meta_special"]))
        assertEquals("che_징병", sso(war["meta_special2"]))

        val so = jdbc.queryForMap("SELECT name, leadership, strength, intel, nation_id FROM general WHERE id = 1001")
        assertEquals("ⓝ우길", sso(so["name"]))
        assertEquals(17, soi(so["leadership"]))
        assertEquals(13, soi(so["strength"]))
        assertEquals(83, soi(so["intel"]))
        assertEquals(0, soi(so["nation_id"]))

        // City x100 scaling + level/region int map: 낙양 (id 3) pop_max 835700, level 8 (특), region 2 (중원).
        val nak = jdbc.queryForMap("SELECT name, level, region, pop_max, nation_id FROM city WHERE id = 3")
        assertEquals("낙양", sso(nak["name"]))
        assertEquals(8, soi(nak["level"]))
        assertEquals(2, soi(nak["region"]))
        assertEquals(835700, soi(nak["pop_max"]))
        assertEquals(1, soi(nak["nation_id"]))

        // capital_city_id wired: 후한 (id 1) capital = first owned city 낙양 (id 3).
        val cap = jdbc.queryForObject("SELECT capital_city_id FROM nation WHERE id = 1", Int::class.java)
        assertEquals(3, cap)

        // nation.meta 패러티(PHP Scenario/Nation.php) — 종전 시드는 infoText 만 넣어 내무부 예산/정책
        // 표가 전부 '-'(FE 가드 미충족)였다. rate=15/bill=100/scout=0/war=0/strategic_cmd_limit=24/
        // surlimit=72 + gennum(소속 장수 수) 가 모두 실려야 한다.
        val nMeta = jdbc.queryForObject("SELECT meta::text FROM nation WHERE id = 1", String::class.java)!!
        assertTrue(nMeta.contains("\"rate\": 15") || nMeta.contains("\"rate\":15"), "nation meta has rate=15: $nMeta")
        assertTrue(nMeta.contains("\"bill\": 100") || nMeta.contains("\"bill\":100"), "nation meta has bill=100: $nMeta")
        assertTrue(nMeta.contains("\"scout\""), "nation meta has scout: $nMeta")
        assertTrue(nMeta.contains("\"war\""), "nation meta has war: $nMeta")
        assertTrue(nMeta.contains("\"gennum\""), "nation meta has gennum: $nMeta")
        // gennum = 후한 소속 장수 수와 일치(meta jsonb 값 == general 테이블 count).
        val hanGennum = jdbc.queryForObject("SELECT (meta->>'gennum')::int FROM nation WHERE id = 1", Int::class.java)
        val hanGenerals = jdbc.queryForObject("SELECT count(*) FROM general WHERE nation_id = 1", Int::class.java)
        assertEquals(hanGenerals, hanGennum, "nation gennum matches general count")

        // world_state.meta carries the hiddenSeed/startYear/startTime EngineEventConfig reads.
        val meta = jdbc.queryForObject("SELECT meta::text FROM world_state WHERE id = 1", String::class.java)!!
        assertTrue(meta.contains("hiddenSeed"), "meta has hiddenSeed: $meta")
        assertTrue(meta.contains("\"startYear\""), "meta has startYear: $meta")
        assertTrue(meta.contains("\"serverId\""), "meta has active serverId: $meta")
        assertTrue(meta.contains("\"ngGameId\""), "meta has active ngGameId: $meta")
        assertTrue(meta.contains("\"map\": \"che\"") || meta.contains("\"map\":\"che\""), "meta has map=che: $meta")

        // world_state.config carries entrance-gating values used by ServerBasicInfoController/FrontInfoController.
        val config = jdbc.queryForObject("SELECT config::text FROM world_state WHERE id = 1", String::class.java)!!
        assertTrue(config.contains("\"npcmode\""), "config has npcmode: $config")
        assertTrue(config.contains("\"block_general_create\""), "config has block_general_create: $config")
        assertTrue(config.contains("\"show_img_level\""), "config has show_img_level: $config")
        assertTrue(config.contains("\"extended_general\""), "config has extended_general: $config")
        assertTrue(config.contains("\"refreshLimit\""), "config has refreshLimit: $config")
        assertTrue(config.contains("\"fiction\""), "config has fiction: $config")
        assertTrue(config.contains("\"map\""), "config has map block: $config")
        assertTrue(config.contains("\"ignoreDefaultEvents\": false") || config.contains("\"ignoreDefaultEvents\":false"))
        assertEquals(
            "30000",
            jdbc.queryForObject("SELECT env ->> 'refreshLimit' FROM ng_games", String::class.java),
        )
        assertEquals("0", jdbc.queryForObject("SELECT env ->> 'fiction' FROM ng_games", String::class.java))
    }

    @Test
    fun `importAll writes the inserted canonical world id to every V31 cohort`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        newImporter().importAll(jdbc, canonicalWorldId)

        val worldId = jdbc.queryForObject("SELECT id FROM world_state", Int::class.java)
        assertEquals(1, worldId, "a fresh schema starts with canonical world_state.id=1")
        for (table in listOf("nation", "city", "general", "general_turn", "nation_turn")) {
            assertEquals(
                0,
                jdbc.queryForObject(
                    "SELECT count(*) FROM $table WHERE world_id IS NULL OR world_id <> ?",
                    Int::class.java,
                    worldId,
                ),
                "$table rows must explicitly carry the inserted canonical world id",
            )
        }
    }

    @Test
    fun `importAll carries a non-one configured world id through every V31 cohort and synchronizes its identity`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        newImporter().importAll(jdbc, WorldId(701))

        assertEquals(701, jdbc.queryForObject("SELECT id FROM world_state", Int::class.java))
        assertEquals(701L, jdbc.queryForObject("SELECT last_value FROM world_state_id_seq", Long::class.java))
        assertEquals(702L, jdbc.queryForObject("SELECT nextval('world_state_id_seq')", Long::class.java))
        for (table in listOf("nation", "city", "general", "general_turn", "nation_turn")) {
            assertEquals(
                0,
                jdbc.queryForObject(
                    "SELECT count(*) FROM $table WHERE world_id <> 701",
                    Int::class.java,
                ),
                "$table must carry the actual inserted world id rather than a literal/default identity",
            )
        }
    }

    @Test
    fun `direct importer reentry leaves no partial second world`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")
        newImporter().importAll(jdbc, canonicalWorldId)

        assertFailsWith<Exception> { newImporter().importAll(jdbc, canonicalWorldId) }

        assertEquals(listOf(1), jdbc.queryForList("SELECT id FROM world_state ORDER BY id", Int::class.java))
        for (table in listOf("nation", "city", "general", "general_turn", "nation_turn")) {
            assertEquals(
                0,
                jdbc.queryForObject("SELECT count(*) FROM $table WHERE world_id <> 1", Int::class.java),
                "$table must not retain rows from a rejected direct reentry",
            )
        }
    }

    @Test
    fun `concurrent direct import attempts admit exactly one world without partial second rows`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val attempts = List(2) {
                executor.submit<Boolean> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS)) { "concurrent importer start barrier timed out" }
                    runCatching { newImporter().importAll(jdbc, canonicalWorldId) }.isSuccess
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both importer attempts must reach the start barrier")
            start.countDown()

            val successfulAttempts = attempts.count { it.get(60, TimeUnit.SECONDS) }
            assertEquals(1, successfulAttempts, "exactly one direct import may be admitted")
            assertEquals(1, count("world_state"))
            for (table in listOf("nation", "city", "general", "general_turn", "nation_turn")) {
                assertEquals(
                    0,
                    jdbc.queryForObject("SELECT count(*) FROM $table WHERE world_id <> 1", Int::class.java),
                    "$table must not retain partial rows from the rejected concurrent import",
                )
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `importAll excludes general_ex when extended_general is disabled`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_1010.json"))
        val extendedName = scenario.generalEx.first().name
        val cities = ScenarioJson.loadMapCities(readResource("map/che.json"))
        val counts = ScenarioImporter(
            scenario = scenario,
            cities = cities,
            extendedGeneral = false,
        ).importAll(jdbc, canonicalWorldId)

        assertTrue(counts.general < 229, "disabled extended_general seeds fewer active generals")
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general WHERE name = ?", Int::class.java, extendedName))
        assertEquals(
            0,
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM event
                  CROSS JOIN LATERAL jsonb_array_elements(action) action_row
                 WHERE action_row ->> 0 = 'RegNPC'
                   AND action_row ->> 2 = ?
                """.trimIndent(),
                Int::class.java,
                extendedName,
            ),
        )
        val config = jdbc.queryForObject("SELECT config::text FROM world_state WHERE id = 1", String::class.java)!!
        assertTrue(config.contains("\"extended_general\": false") || config.contains("\"extended_general\":false"))
    }

    @Test
    fun `extended_general false still consumes InitScenario rng for general_ex before active build draws`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val baseline = scenarioWithExtendedGeneral(
            extendedAffinity = 1,
            extendedEgo = "유지",
        )
        ScenarioImporter(
            scenario = baseline,
            cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
            scenarioCode = "scenario_rng_explicit",
            scenarioNumber = 9001,
            extendedGeneral = false,
            installTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        ).importAll(jdbc, canonicalWorldId)
        val explicitTurnTime = jdbc.queryForObject(
            "SELECT turn_time::text FROM general WHERE name = 'ⓝActiveBase'",
            String::class.java,
        )

        cleanRows()

        val consuming = scenarioWithExtendedGeneral(
            extendedAffinity = 0,
            extendedEgo = null,
        )
        ScenarioImporter(
            scenario = consuming,
            cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
            scenarioCode = "scenario_rng_consuming",
            scenarioNumber = 9002,
            extendedGeneral = false,
            installTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        ).importAll(jdbc, canonicalWorldId)
        val consumingTurnTime = jdbc.queryForObject(
            "SELECT turn_time::text FROM general WHERE name = 'ⓝActiveBase'",
            String::class.java,
        )

        assertTrue(
            explicitTurnTime != consumingTurnTime,
            "PHP Scenario::initFull consumes general_ex affinity/ego RNG before build even when extended_general=false",
        )
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general WHERE name = 'ExtendedOnly'", Int::class.java))
    }

    @Test
    fun `deferred NPC events preserve PHP raw tuples and neutral action names`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "deferred",
              "startYear": 200,
              "const": {},
              "ignoreDefaultEvents": true,
              "nation": [],
              "general": [[0, "UnderBase", null, 0, null, 10, 11, 12, 0, 190, 260, null, "che_event_신산", "base text"]],
              "general_ex": [[0, "UnderEx", null, 0, null, 20, 21, 22, 0, 190, 260, null, "che_event_신산", "ex text"]],
              "general_neutral": [[0, "UnderNeutral", null, 0, null, 30, 31, 32, 0, 190, 260, null, "che_event_신산", "neutral text"]],
              "diplomacy": []
            }
            """.trimIndent(),
        )
        ScenarioImporter(
            scenario = scenario,
            cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
            scenarioCode = "scenario_deferred",
            scenarioNumber = 9003,
            extendedGeneral = false,
            installTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        ).importAll(jdbc, canonicalWorldId)

        val event = jdbc.queryForMap(
            """
            SELECT jsonb_array_length(action) AS action_count,
                   action #>> '{0,0}' AS first_action,
                   action #>> '{0,1}' AS first_raw_affinity,
                   action #>> '{0,2}' AS first_name,
                   action #>> '{0,14}' AS first_text,
                   action #>> '{1,0}' AS second_action,
                   action #>> '{1,2}' AS second_name,
                   action #>> '{1,14}' AS second_text,
                   action #>> '{2,0}' AS last_action
              FROM event
             WHERE priority = 1000
            """.trimIndent(),
        )

        assertEquals(3, soi(event["action_count"]))
        assertEquals("RegNPC", event["first_action"])
        assertEquals("0", event["first_raw_affinity"])
        assertEquals("UnderBase", event["first_name"])
        assertEquals("base text", event["first_text"])
        assertEquals("RegNeutralNPC", event["second_action"])
        assertEquals("UnderNeutral", event["second_name"])
        assertEquals("neutral text", event["second_text"])
        assertEquals("DeleteEvent", event["last_action"])
        assertEquals(
            0,
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM event
                  CROSS JOIN LATERAL jsonb_array_elements(action) action_row
                 WHERE action_row ->> 2 = 'UnderEx'
                """.trimIndent(),
                Int::class.java,
            ),
            "extended_general=false excludes general_ex from deferred event creation",
        )
    }

    @Test
    fun `importAll mirrors GeneralBuilder picture city officer and affinity edge branches`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "edge",
              "startYear": 200,
              "iconPath": "custom",
              "stored_icons": {
                ".": {"1001": "numeric.png"},
                "custom": {"Named": "named.png", "Other": "other.png", "2": "file.png"}
              },
              "const": {},
              "ignoreDefaultEvents": true,
              "nation": [["세력", "#112233", 0, 0, "", 0, "유가", 1, ["낙양"]]],
              "general": [
                [0, "DrawAffinity", "1001", 1, "성도", 10, 11, 12, 0, 180, 260, "유지", null],
                [999, "Named", null, 0, "강주", 20, 21, 22, 0, 180, 260, "안전", null],
                [150, "File", "file.png", 0, null, 30, 31, 32, 0, 180, 260, "은둔", null],
                [-5, "NegativeAffinity", "-1", 0, null, 40, 41, 42, 0, 180, 260, "재간", null]
              ],
              "general_ex": [],
              "diplomacy": []
            }
            """.trimIndent(),
        )
        val counts = ScenarioImporter(
            scenario = scenario,
            cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
            scenarioCode = "scenario_edge",
            scenarioNumber = 9999,
        ).importAll(jdbc, canonicalWorldId)

        assertEquals(4, counts.general)

        val drawAffinity = jdbc.queryForMap("SELECT city_id, affinity, picture, officer_level FROM general WHERE name = 'ⓝDrawAffinity'")
        assertEquals(
            jdbc.queryForObject("SELECT id FROM city WHERE name = '성도'", Int::class.java),
            soi(drawAffinity["city_id"]),
            "explicit locatedCity is persisted without consuming/replacing the city choice",
        )
        assertTrue(soi(drawAffinity["affinity"]) in 1..150, "affinity <1 consumes PHP RNG into 1..150")
        assertEquals("numeric.png", sso(drawAffinity["picture"]))
        assertEquals(1, soi(drawAffinity["officer_level"]), "officer_level=0 normalizes to 1 for faction generals")

        val named = jdbc.queryForMap("SELECT city_id, affinity, picture, officer_level FROM general WHERE name = 'ⓝNamed'")
        assertEquals(jdbc.queryForObject("SELECT id FROM city WHERE name = '강주'", Int::class.java), soi(named["city_id"]))
        assertEquals(999, soi(named["affinity"]))
        assertEquals("custom/named.png", sso(named["picture"]))
        assertEquals(0, soi(named["officer_level"]), "neutral officer_level=0 remains 0")

        val file = jdbc.queryForMap("SELECT affinity, picture FROM general WHERE name = 'ⓝFile'")
        assertEquals(150, soi(file["affinity"]))
        assertEquals("custom/file.png", sso(file["picture"]))

        val negative = jdbc.queryForMap("SELECT affinity, picture FROM general WHERE name = 'ⓝNegativeAffinity'")
        assertTrue(soi(negative["affinity"]) in 1..150, "negative affinity consumes PHP RNG into 1..150")
        assertEquals("default.jpg", sso(negative["picture"]))
    }

    private fun scenarioWithExtendedGeneral(extendedAffinity: Int, extendedEgo: String?): Scenario =
        ScenarioJson.loadScenario(
            """
            {
              "title": "rng",
              "startYear": 200,
              "const": {},
              "ignoreDefaultEvents": true,
              "nation": [],
              "general": [[1, "ActiveBase", null, 0, null, 10, 11, 12, 0, 180, 260, "유지", null]],
              "general_ex": [[$extendedAffinity, "ExtendedOnly", null, 0, null, 20, 21, 22, 0, 180, 260, ${extendedEgo?.let { "\"$it\"" } ?: "null"}, null]],
              "diplomacy": []
            }
            """.trimIndent(),
        )

    @Test
    fun `importAll rejects invalid GeneralBuilder affinity gap`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "invalid",
              "startYear": 200,
              "const": {},
              "ignoreDefaultEvents": true,
              "nation": [],
              "general": [[151, "BadAffinity", null, 0, null, 10, 11, 12, 0, 180, 260, "유지", null]],
              "general_ex": [],
              "diplomacy": []
            }
            """.trimIndent(),
        )

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(
                scenario = scenario,
                cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
                scenarioCode = "scenario_invalid",
                scenarioNumber = 9998,
            ).importAll(jdbc, canonicalWorldId)
        }
        for (table in listOf(
            "world_state", "game_kv", "nation", "city", "general", "general_turn", "nation_turn",
            "diplomacy", "rank_data", "ng_games", "event",
        )) {
            assertEquals(0, count(table), "$table must roll back after a mid-import failure")
        }

        val retry = newImporter().importAll(jdbc, canonicalWorldId)
        assertEquals(1, retry.worldState)
        assertEquals(1, jdbc.queryForObject("SELECT id FROM world_state", Int::class.java))
    }

    @Test
    fun `importAll seeds scenario_1030 (군웅할거, 21 nations) without error`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        // 빼섭 시나리오 일반성 게이트 — importer가 1010(2세력) 외 다세력 시나리오도 무에러 시드하는지.
        val counts = newImporter1030().importAll(jdbc, canonicalWorldId)

        assertEquals(1, counts.worldState)
        assertEquals(21, counts.nation)            // 군웅할거 21세력
        assertEquals(94, counts.city)              // che 풀맵 94도시(소유+공백지) — 맵 공용
        assertEquals(327, counts.general)
        assertEquals(counts.general * 30, counts.generalTurn)
        assertEquals(counts.general * 37, counts.rankData)
        assertEquals(1, counts.ngGames)

        assertEquals(21, count("nation"))
        assertEquals(94, count("city"))
        assertTrue(count("diplomacy") > 0, "diplomacy seeded for 21 nations")

        // ── 도시 소유 정합 (보급-동결 버그 회귀 게이트) ──
        // 소유를 시나리오 nation.cities로 배정한다(cities_1010.json baked nation_id가 아님). baked로는
        // 국가 1·2만 소유 → 19국 무소유 → capital 보유인데 supplyCities 빔 → UpdateCitySupply 미시드 →
        // doNPC구출발령의 RandUtil.choice(빈 보급도시) throw → 빼섭 턴데몬 크래시-루프 동결.
        // (1) 모든 21국이 도시를 ≥1개 소유.
        val landlessNations = jdbc.queryForObject(
            "SELECT count(*) FROM nation n WHERE NOT EXISTS (SELECT 1 FROM city c WHERE c.nation_id = n.id)",
            Int::class.java,
        )
        assertEquals(0, landlessNations, "모든 1030 국가가 도시를 소유해야 한다(무소유 국가 0)")
        // (2) 각 국가의 capital_city_id는 자국 소유 도시(UpdateCitySupply BFS가 capital을 seed할 수 있는 조건).
        val miscapital = jdbc.queryForObject(
            "SELECT count(*) FROM nation n JOIN city c ON c.id = n.capital_city_id WHERE c.nation_id <> n.id",
            Int::class.java,
        )
        assertEquals(0, miscapital, "각 국가의 수도는 자국 소유 도시여야 한다")
        // 모든 장수 불변식 유지(37 rank_data / 30 general_turn) — 다세력에서도.
        val every37 = jdbc.queryForObject(
            "SELECT min(c) = 37 AND max(c) = 37 FROM (SELECT count(*) c FROM rank_data GROUP BY general_id) s",
            Boolean::class.java,
        )
        assertTrue(every37 == true, "every general has exactly 37 rank_data rows")
        // world_state.meta startYear 191(군웅할거).
        val meta = jdbc.queryForObject("SELECT meta::text FROM world_state WHERE id = 1", String::class.java)!!
        assertTrue(meta.contains("\"startYear\""), "meta has startYear: $meta")
    }

    @Test
    fun `importAll seeds scenario_2 with miniche_b city catalog`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val counts = newImporter2().importAll(jdbc, canonicalWorldId)

        assertEquals(1, counts.worldState)
        assertEquals(0, counts.nation)
        assertEquals(78, counts.city)
        assertEquals(0, counts.general)
        assertEquals(0, counts.generalTurn)
        assertEquals(0, counts.rankData)
        assertEquals(1, counts.ngGames)
        assertEquals(78, jdbc.queryForObject("SELECT count(*) FROM city WHERE nation_id = 0", Int::class.java))

        val city = jdbc.queryForMap("SELECT name, level, pop_max, agri_max, comm_max FROM city WHERE id = 1")
        assertEquals("낙양", sso(city["name"]))
        assertEquals(8, soi(city["level"]))
        assertEquals(668600, soi(city["pop_max"]))
        assertEquals(7800, soi(city["agri_max"]))
        assertEquals(8000, soi(city["comm_max"]))

        val config = jdbc.queryForObject("SELECT config::text FROM world_state WHERE id = 1", String::class.java)!!
        assertTrue(config.contains("\"mapName\":\"miniche_b\"") || config.contains("\"mapName\": \"miniche_b\""), config)
    }

    @Test
    fun `a second seed is a no-op via the emptiness gate`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val bootstrap = SeedBootstrapTestDouble()
        // First seed populates the world.
        assertTrue(bootstrap.ensureSeeded(jdbc), "first ensureSeeded should seed")
        val firstGeneral = count("general")
        val firstRank = count("rank_data")

        // Second seed: gate sees world_state > 0 → skip, 0 new rows.
        assertTrue(!bootstrap.ensureSeeded(jdbc), "second ensureSeeded is a no-op (skipped)")
        assertEquals(firstGeneral, count("general"), "no new general rows on second seed")
        assertEquals(firstRank, count("rank_data"), "no new rank_data rows on second seed")
        assertEquals(1, count("world_state"))
    }

    @Test
    fun `RANK_COLUMNS mirror stays the 37 distinct rank_data type names`() {
        // Drift guard for the infra-local mirror of game-engine RankColumn (no cross-module dependency).
        assertEquals(37, ScenarioImporter.RANK_COLUMNS.size)
        assertEquals(37, ScenarioImporter.RANK_COLUMNS.distinct().size, "no duplicate rank columns")
    }

    private fun count(table: String): Int =
        jdbc.queryForObject("SELECT count(*) FROM $table", Int::class.java) ?: 0

    private fun soi(v: Any?): Int = (v as Number).toInt()
    private fun sso(v: Any?): String = v as String

    private fun readResource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: error("resource not found: $path")
        return stream.use { it.readBytes().toString(StandardCharsets.UTF_8) }
    }

    /**
     * A minimal stand-in for `SeedBootstrap.ensureSeeded` (which lives in `app:game-engine`, not on
     * the `infra` test classpath): the same world-emptiness gate + importer call, so the no-op
     * idempotency contract is exercised here in `infra`.
     */
    private inner class SeedBootstrapTestDouble {
        fun ensureSeeded(jdbc: JdbcTemplate): Boolean {
            val worldCount = jdbc.queryForObject("SELECT count(*) FROM world_state", Int::class.java) ?: 0
            if (worldCount > 0) return false
            newImporter().importAll(jdbc, canonicalWorldId)
            return true
        }
    }
}
