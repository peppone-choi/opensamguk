package opensamguk.infra.seed

import java.nio.file.Path
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.infra.persistence.MetaJson
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.input.RuleProfile
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HwihaWarehouseSeedIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private val root = Path.of("..")
    private val cities by lazy {
        ScenarioJson.loadMapCities(checkNotNull(javaClass.classLoader.getResourceAsStream("map/han-world-v3.json"))
            .bufferedReader().use { it.readText() })
    }
    // 임포터는 런타임 지도의 城 집합으로 판을 고른다(ScenarioImporter 의 resolve). 픽스처도 같은 길로
    // 고른다 — 판을 박아 두면 지도 릴리스마다 씨앗 topology 핀이 어긋나 이 IT 가 빨개진다(2026-09-21 실측).
    private val bundle by lazy { HanWorldArtifactsResolver(root).resolve(cities.map { it.id }, emptyList()) }
    private val counties get() = bundle.projection.administrativeCountyIds.sorted()

    @BeforeAll fun setup() {
        assumeTrue(DockerClientFactory.instance().isDockerAvailable)
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
    }
    @AfterAll fun stop() { if (this::postgres.isInitialized) postgres.stop() }
    @AfterTest fun clean() {
        if (this::jdbc.isInitialized) jdbc.execute(
            "TRUNCATE world_state, nation, city, general, general_turn, nation_turn, diplomacy, rank_data, ng_games, event, game_kv RESTART IDENTITY CASCADE")
    }
    private fun declaration(): Map<String, Any?> = linkedMapOf(
        "version" to 1, "units" to "game-resource-v1", "source" to "GAME_DESIGN",
        "topologyRevision" to bundle.projection.topology.topologyRevision,
        "topologyHash" to bundle.projection.topology.contentHash,
        "warehouses" to counties.mapIndexed { index, id -> mapOf("countyId" to id,
            "stock" to HwihaResources(100L+index, 3_000_000_000L+index, 20, 30, 40).toMetaValue()) },
    )
    private fun scenario(): Scenario {
        val raw = HwihaSyntheticScenario.root().toMutableMap()
        raw["nation"] = listOf(listOf("QA 세력", "#123456", 0, 0, "synthetic QA", 0, null, 1, listOf("허창")))
        raw["hwihaWarehouses"] = declaration()
        return ScenarioJson.loadScenario(MetaJson.encode(raw))
    }
    private fun importer(scenario: Scenario) = ScenarioImporter(scenario, cities, artifactsRoot=root)
    private fun stored(id: Int): HwihaCountyWarehouse? = HwihaCountyWarehouse.read(MetaJson.decode(
        jdbc.queryForObject("SELECT meta::text FROM city WHERE world_id=1 AND id=?", String::class.java, id)!!), id)

    @Test fun `fresh import preserves explicit county stock only and reboot cannot refill consumed inventory`() {
        val scenario = scenario()
        importer(scenario).importAll(jdbc, WorldId(1))
        val seed = assertNotNull(scenario.hwihaWarehouses)
        for ((id, stock) in seed.warehouses) assertEquals(HwihaCountyWarehouse(id, 0, stock), stored(id))
        for (city in cities.filter { it.id !in counties }) assertNull(stored(city.id))
        assertEquals(0L, jdbc.queryForObject("SELECT sum(gold+rice)::bigint FROM nation", Long::class.java))
        val meta = MetaJson.decode(jdbc.queryForObject("SELECT meta::text FROM world_state WHERE id=1", String::class.java)!!)
        val provenance = meta["hwihaWarehouseSeed"] as Map<*, *>
        assertEquals(seed.topologyHash, provenance["topologyHash"])
        assertEquals("GAME_DESIGN", provenance["source"])
        val id = counties.first()
        jdbc.update("UPDATE city SET meta=jsonb_set(meta,'{hwihaCountyWarehouse,stock,grain}','0') WHERE world_id=1 AND id=?", id)
        assertFalse(ScenarioSeedCoordinator(jdbc).ensureSeeded(WorldId(1)) { error("existing world must not reconstruct inventory") }.seeded)
        assertEquals(0L, stored(id)!!.stock.grain)
    }

    @Test fun `wrong pins incomplete or noncounty inventory and legacy treasury fail before any writes`() {
        val scenario = scenario()
        val seed = scenario.hwihaWarehouses!!
        val invalid = listOf(
            scenario.copy(hwihaWarehouses=seed.copy(topologyHash="0".repeat(64))),
            scenario.copy(hwihaWarehouses=seed.copy(warehouses=seed.warehouses-counties.first())),
            scenario.copy(hwihaWarehouses=seed.copy(warehouses=seed.warehouses+(Int.MAX_VALUE to HwihaResources()))),
            scenario.copy(nations=scenario.nations.map { it.copy(gold=1) }),
            scenario.copy(nations=scenario.nations.map { it.copy(rice=1) }),
            scenario.copy(ruleProfile=RuleProfile.SAMMO),
        )
        for (bad in invalid) {
            assertFailsWith<IllegalArgumentException> { importer(bad).importAll(jdbc, WorldId(1)) }
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM world_state", Int::class.java))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM city", Int::class.java))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM nation", Int::class.java))
        }
    }

    @Test fun `later seed failure rolls back warehouse rows with the whole new world`() {
        jdbc.execute("""CREATE FUNCTION reject_warehouse_test_general() RETURNS trigger LANGUAGE plpgsql AS
            'BEGIN RAISE EXCEPTION ''warehouse fixture later failure''; END;'""")
        jdbc.execute("CREATE TRIGGER reject_warehouse_test_general BEFORE INSERT ON general FOR EACH ROW EXECUTE FUNCTION reject_warehouse_test_general()")
        try {
            val failure = assertFailsWith<RuntimeException> { importer(scenario()).importAll(jdbc, WorldId(1)) }
            assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it.message.orEmpty().contains("warehouse fixture later failure") })
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM world_state", Int::class.java))
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM city", Int::class.java))
        } finally {
            jdbc.execute("DROP TRIGGER reject_warehouse_test_general ON general")
            jdbc.execute("DROP FUNCTION reject_warehouse_test_general()")
        }
    }
}
