package opensamguk.infra.seed

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.charset.StandardCharsets
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * F1a gate — the scenario-seed importer IT (Testcontainers `postgres:16-alpine` + Flyway baseline).
 *
 * Asserts the seed counts (`world_state`=1, `nation`=2, `city`=94[소유24+공백지70], `general`=678,
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
            "TRUNCATE world_state, nation, city, general, general_turn, nation_turn, diplomacy, rank_data, ng_games RESTART IDENTITY CASCADE",
        )
    }

    private fun newImporter(): ScenarioImporter {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_1010.json"))
        val cities = ScenarioJson.loadCities(readResource("scenario/cities_1010.json"))
        return ScenarioImporter(scenario = scenario, cities = cities)
    }

    // 빼섭(2번째 서버)용 scenario_1030 군웅할거. cities는 che 풀맵 공용(맵 바운드, 시나리오 무관).
    private fun newImporter1030(): ScenarioImporter {
        val scenario = ScenarioJson.loadScenario(readResource("scenario/scenario_1030.json"))
        val cities = ScenarioJson.loadCities(readResource("scenario/cities_1010.json"))
        return ScenarioImporter(scenario = scenario, cities = cities, scenarioCode = "scenario_1030")
    }

    @Test
    fun `importAll seeds the A-minimal world with the expected row counts`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        val counts = newImporter().importAll(jdbc)

        assertEquals(1, counts.worldState)
        assertEquals(2, counts.nation)
        // che 풀맵 94도시 = 소유 24(후한 14 + 황건적 10) + 공백지 70(nation_id=0).
        assertEquals(94, counts.city)
        assertEquals(678, counts.general)
        assertEquals(678 * 30, counts.generalTurn)
        assertEquals(678 * 37, counts.rankData)
        assertEquals(1, counts.ngGames)

        assertEquals(1, count("world_state"))
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
        assertEquals(678, count("general"))
        assertEquals(678 * 30, count("general_turn"))
        assertEquals(678 * 37, count("rank_data"))
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

        // STAT MAPPING (parity): 소제1 = [..., leadership 20, strength 11, intel 48].
        val so = jdbc.queryForMap("SELECT name, leadership, strength, intel, nation_id FROM general WHERE id = 1001")
        assertEquals("소제1", sso(so["name"]))
        assertEquals(20, soi(so["leadership"]))
        assertEquals(11, soi(so["strength"]))
        assertEquals(48, soi(so["intel"]))
        assertEquals(1, soi(so["nation_id"]))

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

        // world_state.meta carries the hiddenSeed/startYear/startTime EngineEventConfig reads.
        val meta = jdbc.queryForObject("SELECT meta::text FROM world_state WHERE id = 1", String::class.java)!!
        assertTrue(meta.contains("hiddenSeed"), "meta has hiddenSeed: $meta")
        assertTrue(meta.contains("\"startYear\""), "meta has startYear: $meta")

        // world_state.config carries entrance-gating values used by ServerBasicInfoController/FrontInfoController.
        val config = jdbc.queryForObject("SELECT config::text FROM world_state WHERE id = 1", String::class.java)!!
        assertTrue(config.contains("\"npcmode\""), "config has npcmode: $config")
        assertTrue(config.contains("\"block_general_create\""), "config has block_general_create: $config")
    }

    @Test
    fun `importAll seeds scenario_1030 (군웅할거, 21 nations) without error`() {
        assumeTrue(dockerAvailable, "Docker unavailable — scenario-seed IT skipped (not failed)")

        // 빼섭 시나리오 일반성 게이트 — importer가 1010(2세력) 외 다세력 시나리오도 무에러 시드하는지.
        val counts = newImporter1030().importAll(jdbc)

        assertEquals(1, counts.worldState)
        assertEquals(21, counts.nation)            // 군웅할거 21세력
        assertEquals(94, counts.city)              // che 풀맵 94도시(소유+공백지) — 맵 공용
        assertTrue(counts.general > 400, "generals seeded: ${counts.general}")
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
            newImporter().importAll(jdbc)
            return true
        }
    }
}
