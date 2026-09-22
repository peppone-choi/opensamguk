package opensamguk.engine.boot

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.read.LiveCityOwnership
import opensamguk.gameapi.read.MapAdministrativeOwnership
import opensamguk.engine.world.HanSpatialSupplyProvider
import opensamguk.engine.world.SpatialSupplyCity
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.infra.seed.HanWorldArtifactsResolver
import opensamguk.infra.seed.MapJson
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.military.UnitSetTable
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.HanWorldVariant
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Real city/general command persistence; authenticated intake and spatial-row writes have separate coverage. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HanExpandedCityCommandRoundTripIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var admin: JdbcTemplate
    private lateinit var jdbc: JdbcTemplate
    private lateinit var executor: JdbcFlushExecutor
    private val artifacts = HanWorldArtifactsResolver(Path.of("../.."))

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(org.testcontainers.DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: expanded city command persistence not verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        // 시드는 한 번만 한다. 마이그레이션 + scenario_1020 시드를 끝낸 SEEDED DB 를 틀로 두고, 반복마다
        // `CREATE DATABASE … TEMPLATE` 로 WORK DB 를 통째로 다시 찍는다. 실측(2026-09-18, 596 반복)에서
        // 반복당 TRUNCATE+재시드가 벽시계의 66% 였다. 행 단위 되돌리기가 아니라 DB 복제라서 앞 반복의
        // 흔적이 남을 길이 없다. DriverManagerDataSource 는 작업마다 연결을 열고 닫으므로 틀에 세션이 남지 않는다.
        fun source(database: String) = DriverManagerDataSource(
            "jdbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/$database", postgres.username, postgres.password)
        admin = JdbcTemplate(source(postgres.databaseName))
        admin.execute("CREATE DATABASE $SEEDED")
        Flyway.configure().dataSource(source(SEEDED)).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        assertTrue(bootstrap.ensureSeeded(JdbcTemplate(source(SEEDED))))
        val source = source(WORK)
        jdbc = JdbcTemplate(source)
        executor = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
    }

    private val worldId = WorldId(1)
    private val bootstrap = SeedBootstrap(scenarioCode = "scenario_1020", worldId = worldId)

    private fun restoreSeededWorld() {
        admin.execute("DROP DATABASE IF EXISTS $WORK WITH (FORCE)")
        admin.execute("CREATE DATABASE $WORK TEMPLATE $SEEDED")
    }

    private fun load() = WorldSnapshotLoader(jdbc, bootstrap, worldId,
        waterTopologyLoader = { artifacts.artifacts(it).projection.topology },
        hanVariantSelector = { ids, pins -> artifacts.resolve(ids, pins).variant }).buildSnapshot()

    private companion object {
        const val SEEDED = "expanded_city_seeded"
        const val WORK = "expanded_city_work"
    }

    @AfterAll fun cleanup() { if (this::postgres.isInitialized) postgres.stop() }

    @Test fun `each city added since historical roster supports move conquest and cold reload`() {
        val currentIds = MapJson.loadFromClasspath("han-world-v3").cities.map { it.id }.toSet()
        // 길 없는 섬 城(956 東部侯官 — 閩 해안)은 이동·출병의 출발지가 없다. 격리 원장은 연결성 테스트
        // (KNOWN_ISOLATED)가 지키고, 여기서는 길로 닿는 새 城만 명령 왕복을 잰다.
        val isolated = MapJson.loadCityDetailsFromClasspath("han-world-v3").filter { it.connections.isEmpty() }.map { it.id }.toSet()
        val olderIds = artifacts.artifacts(HanWorldVariant.V3_835).cityConst.all().keys
        val additions = (currentIds - olderIds - isolated).sorted()
        assertTrue(additions.isNotEmpty(), "expansion evidence must exercise added cities")
        // 틀에서 찍은 DB 는 매번 같은 내용이므로 시드 직후 스냅샷과 그것에만 의존하는 투영 입력은 한 번만 만든다.
        // 끝에서 새로 찍은 DB 를 다시 읽어 baseline 과 같은지 대조한다 — 공유 객체가 도중에 변형됐다면 거기서 빨개진다.
        restoreSeededWorld()
        val baseline = load()
        val bundle = artifacts.artifacts(assertNotNull(baseline.state.hanWorldVariant))
        val mapper = ObjectMapper()
        val apiOwnership = MapAdministrativeOwnership(mapper, "unused", "unused", "unused")
        val supplyProvider = HanSpatialSupplyProvider(mapper, "unused", "unused")
        val coords = MapJson.loadMap(bundle.artifactBytes("infra/src/main/resources/map/han-world-v3.json")
            .toString(Charsets.UTF_8)).cities.associateBy { it.id }
        val provinces = mapper.readTree(bundle.artifactBytes("data/map/han-tiles.json")).path("provinceRecords")
        val canonicalOwners = apiOwnership.project("1020", emptyList(), bundle).provinceOccupancy.map { it.nationId }
        var restoredFirst = false
        for (destination in additions) for (command in listOf("che_이동", "che_출병")) {
            // 첫 반복은 위에서 찍은 DB 를 그대로 쓴다(아직 아무도 쓰지 않았다).
            if (restoredFirst) restoreSeededWorld()
            restoredFirst = true
            val sourceId = assertNotNull(bundle.cityConst.byId(destination)).path.keys.sorted().first()
            assertTrue(destination in assertNotNull(bundle.cityConst.byId(sourceId)).path)
            val actor = baseline.generals.first { it.nationId > 0 }
            val nationId = actor.nationId
            val enemyId = baseline.nations.first { it.id != nationId }.id
            val reserveId = currentIds.sorted().first { it != sourceId && it != destination }
            val attack = command == "che_출병"
            val fixture = InMemoryTurnWorld(baseline)
            // Controlled resources/opposition; real route constraints and combat remain enabled.
            baseline.cities.forEach { city ->
                fixture.updateCity(city.copy(
                    nationId = when { city.id == reserveId -> enemyId; attack && city.id == destination -> enemyId; else -> nationId },
                    supplyState = 1, frontState = 3,
                    population = 100_000, populationMax = 100_000,
                    agriculture = 20_000, agricultureMax = 20_000,
                    commerce = 20_000, commerceMax = 20_000,
                    security = 20_000, securityMax = 20_000,
                    defence = if (city.id == destination) 1 else 20_000,
                    wall = if (city.id == destination) 1 else 20_000,
                ))
            }
            val unitSet = baseline.state.meta["unitSet"] as? String ?: UnitSetTable.CHE_UNIT_SET
            val crewType = assertNotNull(UnitSetTable.defaultCrewTypeId(unitSet))
            baseline.generals.forEach { general ->
                fixture.updateGeneral(if (general.id == actor.id) general.copy(
                    cityId = sourceId, officerLevel = 1, troopId = 0,
                    crew = 50_000, crewTypeId = crewType, train = 100, atmos = 100,
                    gold = 1_000_000, rice = 1_000_000,
                    stats = GeneralStats(leadership = 100, strength = 100, intelligence = 90),
                ) else general.copy(cityId = reserveId, nationId = 0, officerLevel = 1, troopId = 0))
            }
            baseline.nations.forEach { nation ->
                fixture.updateNation(nation.copy(capitalCityId = if (nation.id == nationId) sourceId else reserveId,
                    gold = 1_000_000, rice = 1_000_000))
            }
            val prepRecorder = ChangeRecorder()
            baseline.generals.forEach { before -> prepRecorder.diffGeneral(
                PerTurnOverlay.toLogicGeneral(before), PerTurnOverlay.toLogicGeneral(fixture.getGeneralById(before.id)!!)) }
            baseline.cities.forEach { before -> prepRecorder.diffCity(
                PerTurnOverlay.toLogicCity(before), PerTurnOverlay.toLogicCity(fixture.getCityById(before.id)!!)) }
            baseline.nations.forEach { before -> prepRecorder.diffNation(
                PerTurnOverlay.toLogicNation(before), PerTurnOverlay.toLogicNation(fixture.getNationById(before.id)!!)) }
            for ((from, to) in listOf(nationId to enemyId, enemyId to nationId)) {
                val previous = fixture.getDiplomacy(from, to)
                if (previous == null) fixture.createDiplomacy(TurnDiplomacy(from, to, state = 0, term = 0))
                else {
                    val next = assertNotNull(fixture.updateDiplomacy(from, to, 0, 0))
                    prepRecorder.diffDiplomacy(previous, next)
                }
            }
            executor.flush(DatabaseHooks.toFlushPayload(fixture, prepRecorder, fixture.consumeDirtyState()))
            val world = InMemoryTurnWorld(load())
            assertEquals(sourceId, world.getGeneralById(actor.id)!!.cityId)
            assertEquals(if (attack) enemyId else nationId, world.getCityById(destination)!!.nationId)
            val seat = assertNotNull(coords.getValue(destination).provinceId)
            val jurisdiction = provinces[seat].path("jurisdictionId").asText()
            val affected = (0 until provinces.size()).filter { index ->
                provinces[index].path("jurisdictionId").asText() == jurisdiction &&
                    canonicalOwners[index] == canonicalOwners[seat]
            }.toSet()
            assertTrue(seat in affected)
            fun owners(cities: List<City>): List<Int> {
                val live = cities.mapNotNull { city -> coords.getValue(city.id).provinceId?.let {
                    LiveCityOwnership(city.id, it, city.nationId)
                } }
                val api = apiOwnership.project("1020", live, bundle)
                assertEquals(cities.single { it.id == destination }.nationId,
                    api.jurisdictionOwnership.single { it.jurisdictionId == jurisdiction }.nationId)
                val network = supplyProvider.network("han-world-v3", 1020, live.map {
                    val coord = coords.getValue(it.cityId)
                    SpatialSupplyCity(it.cityId, it.provinceIndex, it.nationId, coord.physicalPlaceRef, coord.routeNodeKey)
                }, world.waterControlSnapshot(), artifacts = bundle)
                return api.provinceOccupancy.map { it.nationId }.also {
                    assertEquals(it, network.provinceOwners.toList(), "API/supply parity destination=$destination")
                }
            }
            val beforeOwners = owners(world.listCities())
            val handler = ReservedTurnHandler(world, CommandRegistry(GeneralActionPipeline()),
                world.getState().meta["hiddenSeed"] as String,
                (world.getState().meta["startYear"] as Number).toInt())
            val handled = handler.handle(actor.id, ReservedTurn(command, "{\"destCityID\":$destination}"),
                year = world.getState().currentYear + 5, month = 1, date = "12:00")
            assertFalse(handled.fellBack, "$command destination=$destination")
            assertEquals(command, handled.definition!!.key)
            assertEquals(destination, world.getGeneralById(actor.id)!!.cityId, "$command movement destination=$destination")
            assertEquals(nationId, world.getCityById(destination)!!.nationId, "$command owner destination=$destination")
            val afterOwners = owners(world.listCities())
            for (index in beforeOwners.indices) {
                assertEquals(if (index in affected) nationId else beforeOwners[index], afterOwners[index],
                    "$command destination=$destination province=$index")
                if (attack && index in affected) assertEquals(enemyId, beforeOwners[index])
            }
            val payload = DatabaseHooks.toFlushPayload(world, handler.recorder, world.consumeDirtyState())
            assertTrue(payload.updatedGenerals.any { it.id == actor.id })
            if (attack) assertTrue(payload.updatedCities.any { it.id == destination })
            executor.flush(payload)
            val restored = load()
            assertEquals(destination, restored.generals.single { it.id == actor.id }.cityId)
            assertEquals(nationId, restored.cities.single { it.id == destination }.nationId)
            assertEquals(afterOwners, owners(restored.cities), "cold reload API/supply owners destination=$destination")
            assertEquals(currentIds, restored.cities.map { it.id }.toSet())
            assertEquals(baseline.state.hanWorldVariant, restored.state.hanWorldVariant)
            assertEquals(bundle.projection.topology.contentHash, assertNotNull(restored.waterControlSnapshot).topologyHash)
            println("EXPANDED_CITY_COMMAND destination=$destination command=$command persisted=true")
        }
        restoreSeededWorld()
        // 세 제어 스냅샷은 equals 가 없는 클래스라 참조 비교가 된다. 나머지 전 필드를 값으로 대조한다.
        fun comparable(snapshot: WorldSnapshot) = snapshot.copy(
            waterControlSnapshot = null, provinceControlSnapshot = null, generalPositionSnapshot = null)
        assertEquals(comparable(baseline), comparable(load()),
            "shared seeded baseline must still equal a fresh clone of the seeded template")
    }
}
