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
 * Asserts the seed counts (`world_state`=1, `nation`=2, `city`=774[소유710+공백지64], `general`=229,
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

    /**
     * 시나리오가 선언한 맵의 城을 읽는다 — 프로덕션 `ScenarioSeedRunner.scenarioMapName` 과 같은 규칙이다.
     * 맵을 `che` 로 박아두면 시나리오가 han 으로 바뀌었을 때 城 id 가 하나도 안 맞아 소유가 통째로 날아간다.
     */
    private fun mapCitiesOf(scenario: Scenario) =
        ScenarioJson.loadMapCities(
            readResource("map/${MapJson.resourceCode(scenario.map["mapName"] as? String ?: "han-world-v2")}.json"),
        )

    private fun newImporter(
        showImageLevel: Int = 3,
        extendedGeneral: Boolean = true,
    ): ScenarioImporter {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_1010.json"))
        val cities = mapCitiesOf(scenario)
        return ScenarioImporter(
            scenario = scenario,
            cities = cities,
            showImageLevel = showImageLevel,
            extendedGeneral = extendedGeneral,
        )
    }

    /** mapName을 생략한 구 시나리오도 공백지도 정본인 han으로 가져온다. */
    private fun newImporterBlankMap(
        showImageLevel: Int = 3,
        extendedGeneral: Boolean = true,
    ): ScenarioImporter {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_mapless_legacy.json"))
        val cities = mapCitiesOf(scenario)
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
        val cities = mapCitiesOf(scenario)
        return ScenarioImporter(scenario = scenario, cities = cities, scenarioCode = "scenario_1030")
    }

    @Test
    fun `scenario 9200 seeds stable V3 ownership capitals and general locations`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_9200.json"))

        ScenarioImporter(
            scenario = scenario,
            cities = mapCitiesOf(scenario),
            scenarioCode = "scenario_9200",
        ).importAll(jdbc, canonicalWorldId)

        assertEquals(1, jdbc.queryForObject("SELECT nation_id FROM city WHERE id = 46", Int::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT nation_id FROM city WHERE id = 1", Int::class.java))
        assertEquals(46, jdbc.queryForObject("SELECT capital_city_id FROM nation WHERE name = '동탁'", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT capital_city_id FROM nation WHERE name = '원소'", Int::class.java))
        assertEquals(46, jdbc.queryForObject("SELECT city_id FROM general WHERE name = '동탁'", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT city_id FROM general WHERE name = '원소'", Int::class.java))
    }

    private fun newImporter2(): ScenarioImporter {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_2.json"))
        val cities = mapCitiesOf(scenario)
        return ScenarioImporter(scenario = scenario, cities = cities, scenarioCode = "scenario_2", scenarioNumber = 2)
    }

    @Test
    fun `importAll seeds the A-minimal world with the expected row counts`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val counts = newImporter().importAll(jdbc, canonicalWorldId)

        assertEquals(1, counts.worldState)
        assertTrue(counts.gameEnv > 0)
        assertEquals(2, counts.nation)
        // han 풀맵 774城 = 소유 710(후한 606 + 황건적 104) + 공백지 64(nation_id=0).
        assertEquals(774, counts.city)
        assertEquals(230, counts.general)
        assertEquals(230 * 30, counts.generalTurn)
        assertEquals(230 * 37, counts.rankData)
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
        val emperor = jdbc.queryForMap(
            "SELECT npc_state, meta ->> 'imperial' AS imperial FROM general WHERE name = '유굉'",
        )
        assertEquals(7, soi(emperor["npc_state"]))
        assertEquals("true", sso(emperor["imperial"]))

        assertEquals(1, count("world_state"))
        assertEquals(counts.gameEnv, jdbc.queryForObject("SELECT count(*) FROM game_kv WHERE \"table\" = 'game_env' AND namespace = 'game_env'", Int::class.java))
        assertEquals(
            "30000",
            jdbc.queryForObject(
                "SELECT value::text FROM game_kv WHERE \"table\" = 'game_env' AND namespace = 'game_env' AND key = 'refreshLimit'",
                String::class.java,
            ),
        )
        // game_env.fiction 은 설치 폼 값이 그대로 들어간다(`ResetHelper.php:297` `'fiction'=>$fiction`).
        // PHP 폼 기본값은 1이다(`install.php:98` `fiction_1` checked). 시나리오 JSON 최상위
        // "fiction" 키는 별개이며 PHP `Scenario.php` 도 우리 파서도 읽지 않는다.
        assertEquals(
            "1",
            jdbc.queryForObject(
                "SELECT value::text FROM game_kv WHERE \"table\" = 'game_env' AND namespace = 'game_env' AND key = 'fiction'",
                String::class.java,
            ),
        )
        assertEquals(2, count("nation"))
        assertEquals(774, count("city"))
        // 실효 지배지만 시나리오 소유로 칠하고, 나머지는 공백지로 둔다.
        assertEquals(547, jdbc.queryForObject("SELECT count(*) FROM city WHERE nation_id = 0", Int::class.java))
        assertEquals(123, jdbc.queryForObject("SELECT count(*) FROM city WHERE nation_id = 1", Int::class.java))
        assertEquals(104, jdbc.queryForObject("SELECT count(*) FROM city WHERE nation_id = 2", Int::class.java))
        // 공백지 초기스탯 = CityConstBase 베이스(점령지 70%max 부스트 없음).
        // 서성(id 75, 소도시) 은 1010 지배표에 없는 郡이라 공백지다: pop 100000·wall 2000·trust 50.
        val sd = jdbc.queryForMap("SELECT pop, wall, trust FROM city WHERE id = 75")
        assertEquals(100000, (sd["pop"] as Number).toInt())
        assertEquals(2000, (sd["wall"] as Number).toInt())
        assertEquals(50.0, (sd["trust"] as Number).toDouble())
        // 점령지는 70%max 불변(parity). 낙양(id 46): pop=ratio70(754800)=528360, trust 80.
        val ly = jdbc.queryForMap("SELECT pop, trust FROM city WHERE id = 46")
        assertEquals(528360, (ly["pop"] as Number).toInt())
        assertEquals(80.0, (ly["trust"] as Number).toDouble())
        assertEquals(230, count("general"))
        assertEquals(230 * 30, count("general_turn"))
        assertEquals(230 * 37, count("rank_data"))
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
            jdbc.queryForObject(
                "SELECT count(*) FROM general WHERE nation_id <> 0 AND npc_state <> 7 AND officer_level NOT IN (1, 11, 12)",
                Int::class.java,
            ),
            "PHP normalizes regular seeded officers while preserving rulers, commanders, and the imperial NPC",
        )
        assertEquals(12, jdbc.queryForObject("SELECT officer_level FROM general WHERE name = 'ⓝ장각'", Int::class.java))
        assertEquals(11, jdbc.queryForObject("SELECT officer_level FROM general WHERE name = 'ⓝ하진'", Int::class.java))
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

        // City x100 scaling + level/region int map: 낙양 (id 46) pop_max 754800, level 9 (경), region 1 (사예).
        val nak = jdbc.queryForMap("SELECT name, level, region, pop_max, nation_id FROM city WHERE id = 46")
        assertEquals("낙양", sso(nak["name"]))
        assertEquals(9, soi(nak["level"]))
        assertEquals(1, soi(nak["region"]))
        assertEquals(754800, soi(nak["pop_max"]))
        assertEquals(1, soi(nak["nation_id"]))

        // capital_city_id wired: 후한 (id 1) capital = first owned city 낙양 (id 46).
        val cap = jdbc.queryForObject("SELECT capital_city_id FROM nation WHERE id = 1", Int::class.java)
        assertEquals(46, cap)

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
        assertTrue(
            meta.contains("\"map\": \"han-world-v3\"") || meta.contains("\"map\":\"han-world-v3\""),
            "meta has map=han-world-v3: $meta",
        )
        assertTrue(meta.contains("\"unitSet\": \"han\"") || meta.contains("\"unitSet\":\"han\""), "meta has unitSet=han: $meta")

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
        // ng_games.env 는 game_env 미러 — 위 162행과 같은 근거(install.php:98 / ResetHelper.php:297).
        assertEquals("1", jdbc.queryForObject("SELECT env ->> 'fiction' FROM ng_games", String::class.java))
    }

    @Test
    fun `importAll defaults a mapless legacy scenario to the Han world`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val counts = newImporterBlankMap().importAll(jdbc, canonicalWorldId)

        assertEquals(1, counts.worldState)
        assertTrue(counts.gameEnv > 0)
        assertEquals(2, counts.nation)
        assertEquals(774, counts.city)
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
        // game_env.fiction 은 설치 폼 값이 그대로 들어간다(`ResetHelper.php:297` `'fiction'=>$fiction`).
        // PHP 폼 기본값은 1이다(`install.php:98` `fiction_1` checked). 시나리오 JSON 최상위
        // "fiction" 키는 별개이며 PHP `Scenario.php` 도 우리 파서도 읽지 않는다.
        assertEquals(
            "1",
            jdbc.queryForObject(
                "SELECT value::text FROM game_kv WHERE \"table\" = 'game_env' AND namespace = 'game_env' AND key = 'fiction'",
                String::class.java,
            ),
        )
        assertEquals(2, count("nation"))
        assertEquals(774, count("city"))
        assertEquals(761, jdbc.queryForObject("SELECT count(*) FROM city WHERE nation_id = 0", Int::class.java))
        assertEquals(7, jdbc.queryForObject("SELECT count(*) FROM city WHERE nation_id = 1", Int::class.java))
        assertEquals(6, jdbc.queryForObject("SELECT count(*) FROM city WHERE nation_id = 2", Int::class.java))
        // 공백지 초기스탯 = CityConstBase 베이스(점령지 70%max 부스트 없음). 성도: pop 150000·wall 5000·trust 50.
        val sd = jdbc.queryForMap("SELECT pop, wall, trust FROM city WHERE name = '성도'")
        assertEquals(150000, (sd["pop"] as Number).toInt())
        assertEquals(5000, (sd["wall"] as Number).toInt())
        assertEquals(50.0, (sd["trust"] as Number).toDouble())
        // 공백지도는 han 정본을 쓰므로 낙양도 han 도시 상한을 적용한다.
        val ly = jdbc.queryForMap("SELECT pop, trust FROM city WHERE name = '낙양'")
        assertEquals(528360, (ly["pop"] as Number).toInt())
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

        // mapName 누락 시 han 정본의 낙양현(id 46)을 사용한다.
        val nak = jdbc.queryForMap("SELECT name, level, region, pop_max, nation_id FROM city WHERE id = 46")
        assertEquals("낙양", sso(nak["name"]))
        assertEquals(9, soi(nak["level"]))
        assertEquals(1, soi(nak["region"]))
        assertEquals(754800, soi(nak["pop_max"]))
        assertEquals(1, soi(nak["nation_id"]))

        // capital_city_id wired: 후한 (id 1) capital = first owned city 낙양현 (id 46).
        val cap = jdbc.queryForObject("SELECT capital_city_id FROM nation WHERE id = 1", Int::class.java)
        assertEquals(46, cap)

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
        assertTrue(meta.contains("\"map\": \"han\"") || meta.contains("\"map\":\"han\""), "meta has map=han: $meta")

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
        // ng_games.env 는 game_env 미러 — 위 162행과 같은 근거(install.php:98 / ResetHelper.php:297).
        assertEquals("1", jdbc.queryForObject("SELECT env ->> 'fiction' FROM ng_games", String::class.java))
    }

    @Test
    fun `importAll writes the inserted canonical world id to every imported world-owned relation`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        newImporter().importAll(jdbc, canonicalWorldId)

        val worldId = jdbc.queryForObject("SELECT id FROM world_state", Int::class.java)
        assertEquals(1, worldId, "a fresh schema starts with canonical world_state.id=1")
        assertImportedWorldOwnedRows(worldId!!)
    }

    @Test
    fun `importAll carries a non-one configured world id through every imported world-owned relation`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        newImporter().importAll(jdbc, WorldId(701))

        assertEquals(701, jdbc.queryForObject("SELECT id FROM world_state", Int::class.java))
        assertEquals(701L, jdbc.queryForObject("SELECT last_value FROM world_state_id_seq", Long::class.java))
        assertEquals(702L, jdbc.queryForObject("SELECT nextval('world_state_id_seq')", Long::class.java))
        assertImportedWorldOwnedRows(701)
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
        val cities = mapCitiesOf(scenario)
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
    fun `general_ex provenance and extension quadrants preserve downstream neutral RNG order`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        fun importAndSnapshot(sourceProvenanced: Boolean, extendedGeneral: Boolean): Map<String, Map<String, Any?>> {
            ScenarioImporter(
                scenario = scenarioForGeneralExProvenanceRng(sourceProvenanced),
                cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
                scenarioCode = "scenario_general_ex_provenance_rng",
                scenarioNumber = 9008,
                extendedGeneral = extendedGeneral,
                installTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            ).importAll(jdbc, canonicalWorldId)
            return jdbc.queryForList(
                """
                SELECT name, affinity, city_id, personal_code, turn_time::text AS turn_time,
                       meta ->> 'killturn' AS killturn
                  FROM general
                 ORDER BY id
                """.trimIndent(),
            ).associateBy { it.getValue("name") as String }
        }

        val legacyEnabled = importAndSnapshot(sourceProvenanced = false, extendedGeneral = true)
        cleanRows()
        val legacyDisabled = importAndSnapshot(sourceProvenanced = false, extendedGeneral = false)
        cleanRows()
        val sourceEnabled = importAndSnapshot(sourceProvenanced = true, extendedGeneral = true)
        cleanRows()
        val sourceDisabled = importAndSnapshot(sourceProvenanced = true, extendedGeneral = false)

        val extensionName = "ⓝGeneralExCandidate"
        val baseName = "ⓝBaseLegacyCandidate"
        val neutralName = "ⓤNeutralLegacyCandidate"

        assertTrue(extensionName in legacyEnabled)
        assertTrue(extensionName !in legacyDisabled, "ordinary legacy extensions remain disabled")
        assertTrue(extensionName in sourceEnabled)
        assertTrue(extensionName in sourceDisabled, "source provenance retains the RTK14-enriched extension")
        assertEquals(
            legacyEnabled.getValue(neutralName),
            sourceEnabled.getValue(neutralName),
            "when extensions are enabled, source-provenanced and legacy general_ex rows keep PHP build order",
        )
        assertEquals(
            legacyDisabled.getValue(baseName),
            sourceDisabled.getValue(baseName),
            "the retained source row must not change preceding legacy build fields",
        )
        assertEquals(
            legacyDisabled.getValue(neutralName),
            sourceDisabled.getValue(neutralName),
            "a source-only retained general_ex row must not advance InitScenario before later neutral rows",
        )
        assertEquals(
            sourceEnabled.getValue(extensionName).filterKeys { it == "affinity" || it == "personal_code" },
            sourceDisabled.getValue(extensionName).filterKeys { it == "affinity" || it == "personal_code" },
            "the source-admitted row keeps its legacy InitScenario affinity and ego draws",
        )
    }

    @Test
    fun `appended RTK14 rows do not shift legacy InitScenario replay rows`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        fun importAndSnapshot(includeRtk14Addition: Boolean): List<Map<String, Any?>> {
            ScenarioImporter(
                scenario = scenarioForRtk14RngIsolation(includeRtk14Addition),
                cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
                scenarioCode = "scenario_rtk14_rng_isolation",
                scenarioNumber = 9005,
                installTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            ).importAll(jdbc, canonicalWorldId)
            return jdbc.queryForList(
                """
                SELECT id, name, affinity, city_id, personal_code, turn_time::text AS turn_time,
                       meta ->> 'killturn' AS killturn
                  FROM general
                 WHERE name IN ('ⓝBaseLegacyRng', 'ⓝExtendedLegacyRng', 'ⓤNeutralLegacyRng')
                 ORDER BY id
                """.trimIndent(),
            )
        }

        val baseline = importAndSnapshot(includeRtk14Addition = false)
        cleanRows()
        val withAddition = importAndSnapshot(includeRtk14Addition = true)

        assertEquals(3, baseline.size)
        assertEquals(baseline, withAddition, "added RTK14 draws use their own stream and leave legacy rows byte-stable")
    }

    @Test
    fun `matched RTK14 row becoming future phantom-consumes legacy build RNG for later unchanged row`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        fun importAndSnapshot(enrichEarlyRow: Boolean): Map<String, Any?> {
            ScenarioImporter(
                scenario = scenarioForRtk14LegacyRngLifecycle(
                    earlyRow = if (enrichEarlyRow) {
                        rtk14LifecycleTuple(
                            name = "MatchedBecomesFuture",
                            birth = 180,
                            death = 260,
                            appearance = 205,
                            officerNumber = 9101,
                            legacyActiveAtStart = true,
                        )
                    } else {
                        legacyLifecycleTuple("MatchedBecomesFuture", 180, 260)
                    },
                ),
                cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
                scenarioCode = "scenario_rtk14_future_rng",
                scenarioNumber = 9006,
                installTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            ).importAll(jdbc, canonicalWorldId)
            return laterLegacyRngSnapshot()
        }

        val baseline = importAndSnapshot(enrichEarlyRow = false)
        cleanRows()
        val enriched = importAndSnapshot(enrichEarlyRow = true)

        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM general WHERE name = 'ⓝMatchedBecomesFuture'", Int::class.java))
        assertEquals(
            "205",
            jdbc.queryForObject(
                "SELECT condition #>> '{2}' FROM event WHERE action #>> '{0,2}' = 'MatchedBecomesFuture'",
                String::class.java,
            ),
            "the enriched lifecycle still defers the matched row at its explicit appearance year",
        )
        assertEquals(
            baseline,
            enriched,
            "an old-active matched row must consume its legacy city, turn-time, and killturn draws before later legacy rows",
        )
    }

    @Test
    fun `matched RTK14 row newly active uses isolated build RNG without shifting later legacy row`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        fun importAndSnapshot(enrichEarlyRow: Boolean): Map<String, Any?> {
            ScenarioImporter(
                scenario = scenarioForRtk14LegacyRngLifecycle(
                    earlyRow = if (enrichEarlyRow) {
                        rtk14LifecycleTuple(
                            name = "MatchedNewlyActive",
                            birth = 190,
                            death = 260,
                            appearance = 200,
                            officerNumber = 9102,
                            legacyActiveAtStart = false,
                        )
                    } else {
                        legacyLifecycleTuple("MatchedNewlyActive", 190, 260)
                    },
                ),
                cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
                scenarioCode = "scenario_rtk14_newly_active_rng",
                scenarioNumber = 9007,
                installTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            ).importAll(jdbc, canonicalWorldId)
            return laterLegacyRngSnapshot()
        }

        val baseline = importAndSnapshot(enrichEarlyRow = false)
        cleanRows()
        val enriched = importAndSnapshot(enrichEarlyRow = true)

        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM general WHERE name = 'ⓝMatchedNewlyActive'", Int::class.java))
        assertEquals(
            baseline,
            enriched,
            "a newly active matched row must draw from InitScenarioRtk14 instead of advancing later legacy replay fields",
        )
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
    fun `RTK14 appearance lifecycle seeds active rows schedules exact appearances and preserves source metadata`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "rtk14 lifecycle",
              "startYear": 200,
              "const": {},
              "ignoreDefaultEvents": true,
              "nation": [],
              "general": [
                [1,"LegacyActive",null,0,null,10,11,12,0,186,240,"유지",null],
                [1,"LegacyFuture",null,0,null,10,11,12,0,190,240,"유지",null],
                [1,"Under14AtAppearance",null,0,null,10,11,12,0,199,240,"유지",null,null,50,50,200,101,"남",60,41,321,"유가",false,false],
                [1,"AtDeathAppearance",null,0,null,10,11,12,0,100,200,"유지",null,null,50,50,200,102,"여",70,55,322,"법가",false,false],
                [1,"LaterAppearance",null,0,null,10,11,12,0,100,240,"유지",null,null,50,50,205,103,"남",80,65,323,"도교",false,true],
                [1,"DeathEqualsLaterAppearance",null,0,null,10,11,12,0,100,205,"유지",null,null,50,50,205,104,"여",75,50,324,"유가",false,true],
                [1,"AlreadyDeceased",null,0,null,10,11,12,0,100,199,"유지",null,null,50,50,190,105,"남",90,70,325,"묵가",false,false]
              ],
              "general_ex": [],
              "diplomacy": []
            }
            """.trimIndent(),
        )
        ScenarioImporter(
            scenario = scenario,
            cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
            scenarioCode = "scenario_rtk14_lifecycle",
            scenarioNumber = 9004,
            installTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        ).importAll(jdbc, canonicalWorldId)

        assertEquals(
            3,
            jdbc.queryForObject("SELECT count(*) FROM general", Int::class.java),
            "legacy adult, under-14 explicit appearance, and death-equals-appearance start rows are active",
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM general WHERE name = 'ⓝUnder14AtAppearance'",
                Int::class.java,
            ),
            "an explicit appearance overrides the legacy birth-plus-adult-age gate",
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM general WHERE name = 'ⓝAtDeathAppearance'",
                Int::class.java,
            ),
            "an explicit appearance remains active when death equals the start appearance year",
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM general WHERE name = 'ⓝAlreadyDeceased'",
                Int::class.java,
            ),
        )

        val later = jdbc.queryForMap(
            """
            SELECT condition #>> '{2}' AS scheduled_year,
                   action #>> '{0,17}' AS appearance_year,
                   action #>> '{0,18}' AS officer_number,
                   action #>> '{0,19}' AS gender,
                   action #>> '{0,20}' AS lifespan,
                   action #>> '{0,21}' AS activity_years,
                   action #>> '{0,22}' AS total,
                   action #>> '{0,23}' AS ideology
              FROM event
             WHERE action #>> '{0,2}' = 'LaterAppearance'
            """.trimIndent(),
        )
        assertEquals("205", sso(later["scheduled_year"]))
        assertEquals("205", sso(later["appearance_year"]))
        assertEquals("103", sso(later["officer_number"]))
        assertEquals("남", sso(later["gender"]))
        assertEquals("80", sso(later["lifespan"]))
        assertEquals("65", sso(later["activity_years"]))
        assertEquals("323", sso(later["total"]))
        assertEquals("도교", sso(later["ideology"]))
        assertEquals(
            1,
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM event
                  CROSS JOIN LATERAL jsonb_array_elements(action) action_row
                 WHERE action_row #>> '{0}' = 'RegNPC'
                   AND action_row #>> '{2}' = 'DeathEqualsLaterAppearance'
                   AND condition #>> '{2}' = '205'
                """.trimIndent(),
                Int::class.java,
            ),
            "a future appearance at the death year is scheduled inclusively",
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT count(*) FROM event WHERE action #>> '{0,2}' = 'LegacyFuture' AND condition #>> '{2}' = '204'",
                Int::class.java,
            ),
            "legacy rows retain birth-plus-adult-age scheduling",
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM event WHERE action::text LIKE '%AlreadyDeceased%'",
                Int::class.java,
            ),
        )

        val meta = jdbc.queryForMap(
            """
            SELECT meta ->> 'rtk14_officer_number' AS officer_number,
                   meta ->> 'rtk14_gender' AS gender,
                   meta ->> 'rtk14_birth_year' AS birth_year,
                   meta ->> 'rtk14_appearance_year' AS appearance_year,
                   meta ->> 'rtk14_death_year' AS death_year,
                   meta ->> 'rtk14_lifespan' AS lifespan,
                   meta ->> 'rtk14_activity_years' AS activity_years,
                   meta ->> 'rtk14_total' AS total,
                   meta ->> 'rtk14_ideology' AS ideology
              FROM general
             WHERE name = 'ⓝUnder14AtAppearance'
            """.trimIndent(),
        )
        assertEquals("101", sso(meta["officer_number"]))
        assertEquals("남", sso(meta["gender"]))
        assertEquals("199", sso(meta["birth_year"]))
        assertEquals("200", sso(meta["appearance_year"]))
        assertEquals("240", sso(meta["death_year"]))
        assertEquals("60", sso(meta["lifespan"]))
        assertEquals("41", sso(meta["activity_years"]))
        assertEquals("321", sso(meta["total"]))
        assertEquals("유가", sso(meta["ideology"]))
        assertEquals(
            false,
            jdbc.queryForObject(
                "SELECT meta ? 'rtk14_appearance_year' FROM general WHERE name = 'ⓝLegacyActive'",
                Boolean::class.java,
            ),
            "legacy rows do not receive RTK14 source metadata",
        )
    }

    @Test
    fun `importAll rejects an RTK14 appearance after death instead of silently dropping the row`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "invalid rtk14 lifecycle",
              "startYear": 200,
              "const": {},
              "ignoreDefaultEvents": true,
              "nation": [],
              "general": [
                [1,"InvalidLifecycle",null,0,null,10,11,12,0,100,200,"유지",null,null,50,50,201,101,"남",70,50,300,"유가",false,false]
              ],
              "general_ex": [],
              "diplomacy": []
            }
            """.trimIndent(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            ScenarioImporter(
                scenario = scenario,
                cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
                scenarioCode = "scenario_invalid_rtk14_lifecycle",
                scenarioNumber = 9009,
                installTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            ).importAll(jdbc, canonicalWorldId)
        }

        assertEquals(
            "scenario general InvalidLifecycle has appearanceYear=201 after deathYear=200",
            error.message,
        )
        for (table in listOf(
            "world_state", "game_kv", "nation", "city", "general", "general_turn", "nation_turn",
            "diplomacy", "rank_data", "ng_games", "event",
        )) {
            assertEquals(0, count(table), "$table must remain empty after lifecycle validation fails")
        }
    }

    @Test
    fun `reviewed legacy-only tuple with a lifecycle marker omits RTK14 source metadata`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val scenario = ScenarioJson.loadScenario(
            """
            {
              "title": "reviewed legacy metadata",
              "startYear": 200,
              "const": {},
              "ignoreDefaultEvents": true,
              "nation": [],
              "general": [
                [1,"ReviewedLegacyOnly",null,0,null,10,11,12,0,186,240,"유지",null,null,61,62,null,null,null,null,null,null,null,false,true]
              ],
              "general_ex": [],
              "diplomacy": []
            }
            """.trimIndent(),
        )

        val counts = ScenarioImporter(
            scenario = scenario,
            cities = ScenarioJson.loadMapCities(readResource("map/che.json")),
            scenarioCode = "scenario_reviewed_legacy_metadata",
            scenarioNumber = 9008,
            installTime = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        ).importAll(jdbc, canonicalWorldId)

        assertEquals(1, counts.general)
        assertEquals(
            0,
            jdbc.queryForObject(
                """
                SELECT count(*)
                  FROM general
                  CROSS JOIN LATERAL jsonb_object_keys(meta) AS meta_key
                 WHERE name = 'ⓝReviewedLegacyOnly'
                   AND meta_key LIKE 'rtk14_%'
                """.trimIndent(),
                Int::class.java,
            ),
            "a 25-slot reviewed legacy tuple is not an RTK14 source row when officer number is absent",
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

    private fun scenarioForGeneralExProvenanceRng(sourceProvenanced: Boolean): Scenario {
        val extension = if (sourceProvenanced) {
            "[0,\"GeneralExCandidate\",null,0,null,20,21,22,0,180,260,null,null,null,50,50,200,9201,\"남\",70,50,300,\"유가\",false,true]"
        } else {
            "[0,\"GeneralExCandidate\",null,0,null,20,21,22,0,180,260,null,null,null]"
        }
        return ScenarioJson.loadScenario(
            """
            {
              "title": "general_ex provenance rng",
              "startYear": 200,
              "const": {},
              "ignoreDefaultEvents": true,
              "nation": [],
              "general": [[0,"BaseLegacyCandidate",null,0,null,10,11,12,0,180,260,null,null]],
              "general_ex": [$extension],
              "general_neutral": [[0,"NeutralLegacyCandidate",null,0,null,30,31,32,0,180,260,null,null]],
              "diplomacy": []
            }
            """.trimIndent(),
        )
    }

    private fun scenarioForRtk14RngIsolation(includeRtk14Addition: Boolean): Scenario =
        ScenarioJson.loadScenario(
            """
            {
              "title": "rtk14 rng isolation",
              "startYear": 200,
              "const": {},
              "ignoreDefaultEvents": true,
              "nation": [],
              "general": [
                [0,"BaseLegacyRng",null,0,null,10,11,12,0,180,260,null,null]${if (includeRtk14Addition) ",\n                [0,\"AppendedRtk14Rng\",null,0,null,20,21,22,0,180,260,null,null,null,50,50,200,9001,\"남\",70,50,300,\"유가\",true]" else ""}
              ],
              "general_ex": [[0,"ExtendedLegacyRng",null,0,null,30,31,32,0,180,260,null,null]],
              "general_neutral": [[0,"NeutralLegacyRng",null,0,null,40,41,42,0,180,260,null,null]],
              "diplomacy": []
            }
            """.trimIndent(),
        )

    private fun scenarioForRtk14LegacyRngLifecycle(earlyRow: String): Scenario =
        ScenarioJson.loadScenario(
            """
            {
              "title": "rtk14 legacy rng lifecycle",
              "startYear": 200,
              "const": {},
              "ignoreDefaultEvents": true,
              "nation": [],
              "general": [
                $earlyRow,
                ${legacyLifecycleTuple("LaterUnchangedLegacyRng", 180, 260)}
              ],
              "general_ex": [],
              "diplomacy": []
            }
            """.trimIndent(),
        )

    private fun legacyLifecycleTuple(name: String, birth: Int, death: Int): String =
        "[0,\"$name\",null,0,null,10,11,12,0,$birth,$death,null,null,null]"

    private fun rtk14LifecycleTuple(
        name: String,
        birth: Int,
        death: Int,
        appearance: Int,
        officerNumber: Int,
        legacyActiveAtStart: Boolean,
    ): String =
        "[0,\"$name\",null,0,null,10,11,12,0,$birth,$death,null,null,null,50,50,$appearance,$officerNumber,\"남\",70,50,300,\"유가\",false,$legacyActiveAtStart]"

    private fun laterLegacyRngSnapshot(): Map<String, Any?> =
        jdbc.queryForMap(
            """
            SELECT affinity, city_id, personal_code, turn_time::text AS turn_time,
                   meta ->> 'killturn' AS killturn
              FROM general
             WHERE name = 'ⓝLaterUnchangedLegacyRng'
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
        assertEquals(774, counts.city)             // han 풀맵 774城(소유+공백지) — 맵 공용
        assertEquals(327, counts.general)
        assertEquals(counts.general * 30, counts.generalTurn)
        assertEquals(counts.general * 37, counts.rankData)
        assertEquals(1, counts.ngGames)

        assertEquals(21, count("nation"))
        assertEquals(774, count("city"))
        assertTrue(count("diplomacy") > 0, "diplomacy seeded for 21 nations")

        // ── 도시 소유 정합 (보급-동결 버그 회귀 게이트) ──
        // 소유를 시나리오 nation.cities로 배정한다(cities_1010.json baked nation_id가 아님). baked로는
        // 국가 1·2만 소유 → 19국 무소유 → capital 보유인데 supplyCities 빔 → UpdateCitySupply 미시드 →
        // doNPC구출발령의 RandUtil.choice(빈 보급도시) throw → 빼섭 턴데몬 크래시-루프 동결.
        // (1) 실재 세력(level > 0)은 도시를 ≥1개 소유. level 0 = 방랑군은 정의상 무소유라 뺀다 —
        // 1030 의 공주는 사료상 領有한 郡이 없어 방랑군으로 시드된다(`apply_han_world.py`).
        val landlessNations = jdbc.queryForObject(
            "SELECT count(*) FROM nation n WHERE n.level > 0 AND NOT EXISTS (SELECT 1 FROM city c WHERE c.nation_id = n.id)",
            Int::class.java,
        )
        assertEquals(0, landlessNations, "방랑군이 아닌 1030 국가는 모두 도시를 소유해야 한다")
        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM nation WHERE level = 0", Int::class.java),
            "1030 방랑군은 공주 하나뿐이다",
        )
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
    fun `importAll seeds scenario_2 with Han world city catalog`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val counts = newImporter2().importAll(jdbc, canonicalWorldId)

        assertEquals(1, counts.worldState)
        assertEquals(0, counts.nation)
        assertEquals(774, counts.city)
        assertEquals(0, counts.general)
        assertEquals(0, counts.generalTurn)
        assertEquals(0, counts.rankData)
        assertEquals(1, counts.ngGames)
        assertEquals(774, jdbc.queryForObject("SELECT count(*) FROM city WHERE nation_id = 0", Int::class.java))

        val city = jdbc.queryForMap("SELECT name, level, pop_max, agri_max, comm_max FROM city WHERE id = 1")
        assertEquals("장안", sso(city["name"]))
        assertEquals(9, soi(city["level"]))
        assertEquals(754800, soi(city["pop_max"]))
        assertEquals(14000, soi(city["agri_max"]))
        assertEquals(14800, soi(city["comm_max"]))

        val config = jdbc.queryForObject("SELECT config::text FROM world_state WHERE id = 1", String::class.java)!!
        assertTrue(config.contains("\"mapName\":\"han-world-v3\"") || config.contains("\"mapName\": \"han-world-v3\""), config)
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

    private fun assertImportedWorldOwnedRows(worldId: Int) {
        val importedRelations = listOf(
            "game_kv" to "\"table\" <> 'inheritance'",
            "nation" to "TRUE",
            "city" to "TRUE",
            "general" to "TRUE",
            "general_turn" to "TRUE",
            "nation_turn" to "TRUE",
            "diplomacy" to "TRUE",
            "rank_data" to "TRUE",
            "ng_games" to "TRUE",
            "event" to "TRUE",
        )
        for ((table, importedRowPredicate) in importedRelations) {
            val total = jdbc.queryForObject(
                "SELECT count(*) FROM $table WHERE $importedRowPredicate",
                Int::class.java,
            ) ?: 0
            assertTrue(total > 0, "$table must contain imported world-owned rows")
            assertEquals(
                0,
                jdbc.queryForObject(
                    "SELECT count(*) FROM $table WHERE ($importedRowPredicate) AND world_id IS NULL",
                    Int::class.java,
                ),
                "$table imported rows must not have a NULL world_id",
            )
            assertEquals(
                total,
                jdbc.queryForObject(
                    "SELECT count(*) FROM $table WHERE ($importedRowPredicate) AND world_id = ?",
                    Int::class.java,
                    worldId,
                ),
                "$table imported rows must carry world_id=$worldId rather than a literal/default identity",
            )
        }
    }

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
