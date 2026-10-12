package opensamguk.engine.boot

import java.nio.file.Path
import kotlin.test.*
import opensamguk.common.wire.TurnDaemonCommand.ImmediateInput
import opensamguk.common.world.WorldId
import opensamguk.engine.campaign.*
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.gameapi.read.DomesticSnapshot
import opensamguk.gameapi.read.DomesticViews
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.CountyGeographyJson
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.logic.domestic.*
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.*
import opensamguk.logic.world.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Synthetic owners/stock, real archived 1428 road geometry and production handler/flush/cold loader.
 * The older EnlistmentFixture's 1133 bundle has no strategic road gates. This fixture pins 1428 explicitly.
 * This exercises core/PG and the pure works DTO projection, not authenticated HTTP or browser UI.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RoadFortWorkPersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private val bundle by lazy { WorldArtifactsResolver(Path.of("../..")).artifacts(WorldMapVariant.V3_1428) }
    private val topology get() = bundle.projection.topology
    private val geography by lazy { CountyGeographyJson.load(bundle) }
    private val context by lazy { DomesticContext(geography = geography, topology = topology,
        metrics = bundle.landMarchMetrics, roadGates = bundle.projection.presentation!!.roadGates,
        cityConst = bundle.cityConst) }
    private val site by lazy {
        context.roadGates.sortedBy { it.edgeId }.firstNotNullOf { gate ->
            if (!gate.buildable || gate.initiallyBuilt) null else gate.fortCells.firstNotNullOfOrNull { cell ->
                val county = bundle.projection.administrativeCountyIds.sorted().firstOrNull { id ->
                    val provinces = geography.provincesOfCounty(id) +
                        listOfNotNull(bundle.projection.bindingsByCityId[id]?.landProvinceId)
                    cell.provinceId in provinces && bundle.projection.bindingsByCityId[id]?.landProvinceId != null
                }
                county?.let { Triple(it, gate, cell) }
            }
        }
    }
    private val stock = Resources(1_000_000, 1_000_000, 1_000_000, 1_000_000, 1_000_000)

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable; actual PostgreSQL work flow NOT_RUN")
        postgres = PostgreSQLContainer("postgres:16-alpine").apply {
            withImagePullPolicy { false }
            withLabel("opensamguk.road-fort-test", "a320-20261012")
            withCreateContainerCmdModifier { it.hostConfig?.withMemory(256L * 1024 * 1024) }
            start()
        }
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
    }
    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private fun cold(id: Int, now: Phase): InMemoryTurnWorld {
        val loaded = WorldSnapshotLoader(jdbc, SeedBootstrap(seedEnabled = false, worldId = WorldId(id)), WorldId(id),
            waterTopologyLoader = { topology }, mapVariantSelector = { ids, _ ->
                require(ids.toSet() == bundle.cityConst.all().keys); WorldMapVariant.V3_1428
            }, administrativeCountyIdsLoader = { bundle.projection.administrativeCountyIds },
            cityLandProvinceLoader = { bundle.projection.bindingsByCityId.mapNotNull { (id, binding) ->
                binding.landProvinceId?.let { id to it }
            }.toMap() }).buildSnapshot()
        return InMemoryTurnWorld(loaded).also {
            it.setCurrentDate(now.year, now.month, now.phase)
            assertEquals(topology.contentHash, it.generalPositionSnapshot()!!.topologyHash)
        }
    }
    private fun save(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))

    private fun seed(id: Int): InMemoryTurnWorld {
        val county = site.first
        jdbc.update("""INSERT INTO world_state(id,scenario_code,current_year,current_month,current_phase,tick_seconds,config,meta)
            VALUES (?,'road-fort-storage-test',200,1,1,3600,
            '{"mapName":"han-world-v3","worldFormat":"GENERAL_RETAINER_CAMPAIGN"}'::jsonb,
            '{"lastTurnTime":"0200-01-01T00:00:00Z"}'::jsonb)""", id)
        jdbc.batchUpdate("""INSERT INTO city(world_id,id,name,level,nation_id,pop,pop_max,agri,agri_max,comm,comm_max,
            secu,secu_max,def,def_max,wall,wall_max,region) VALUES (?,?,?,1,0,100,1000,10,1000,10,1000,10,1000,10,1000,10,1000,1)""",
            bundle.cityConst.all().keys.sorted().map { arrayOf<Any>(id, it, "fixture-$it") })
        for (nation in listOf(1, 2)) jdbc.update(
            "INSERT INTO nation(world_id,id,name,color,capital_city_id) VALUES (?,?,'fixture','#000000',?)", id, nation, county)
        for ((actor, nation, owner) in listOf(Triple(10, 1, 42), Triple(11, 2, 43), Triple(12, 1, 44))) {
            jdbc.update("""INSERT INTO general(world_id,id,name,nation_id,city_id,npc_state,user_id,officer_level,
                leadership,strength,intel,politics,charm,turn_time,meta)
                VALUES (?,?,?,?,?,0,?,?,60,60,60,60,60,'0200-01-01T00:00:00Z',?::jsonb)""",
                id, actor, "fixture-$actor", nation, county, owner.toString(), if (actor == 12) 0 else 12,
                """{"lord":${actor != 12}}""")
            jdbc.update("""INSERT INTO general_spatial_position(world_id,general_id,topology_revision,topology_hash,node_kind,node_id,revision)
                VALUES (?,?,?,?,'LAND_PROVINCE',?,1)""", id, actor, topology.topologyRevision, topology.contentHash,
                bundle.projection.bindingsByCityId.getValue(county).landProvinceId)
        }
        jdbc.update("UPDATE city SET nation_id=1,meta=?::jsonb WHERE world_id=? AND id=?",
            MetaJson.encode(mapOf(CountyWarehouse.META_KEY to CountyWarehouse(county, 0, Resources()).toMetaValue())), id, county)
        val world = cold(id, Phase(200, 1, 1)); val recorder = ChangeRecorder()
        env(world, recorder, LandPassageState.META_KEY, LandPassageState.initialMetaValue(topology))
        save(world, recorder)
        return cold(id, Phase(200, 1, 1))
    }
    private fun env(world: InMemoryTurnWorld, recorder: ChangeRecorder, key: String, value: Any) {
        world.setGameEnvValue(key, value); recorder.recordKv("game_env", "game_env", key, value)
    }
    private fun args(work: DomesticWork) = """{"countyId":${site.first},"work":"${work.name}","edgeId":"${site.second.edgeId}"${
        if (work == DomesticWork.FORTIFICATION) ",\"row\":${site.third.row},\"col\":${site.third.col}" else ""}}"""
    private fun submit(world: InMemoryTurnWorld, recorder: ChangeRecorder, work: DomesticWork,
        request: String = work.name, actor: Int = 10, owner: Int = 42, body: String = args(work)) =
        CourtHandler(world, recorder, context).handle(ImmediateInput(request, actor, owner, "work.start", body))
    private fun works(world: InMemoryTurnWorld) = CountyWorks.read(world.getCityById(site.first)!!.meta)!!
    private fun warehouse(world: InMemoryTurnWorld) = CountyWarehouse.read(world.getCityById(site.first)!!.meta, site.first)!!
    private fun storedEnv(id: Int, key: String) = MetaJson.decode(jdbc.queryForObject(
        "SELECT value::text FROM game_kv WHERE world_id=? AND \"table\"='game_env' AND namespace='game_env' AND key=?",
        String::class.java, id, key)!!)
    private fun expectedCharge(cost: Resources, progress: Int, required: Int) = Resources(
        cost.money * progress / required, cost.grain * progress / required, cost.iron * progress / required,
        cost.timber * progress / required, cost.horses * progress / required)

    @Test fun `road and specified fort admission installments completion and DTO survive postgres cold reload`() {
        val id = 811; var now = Phase(200, 1, 1); var world = seed(id)
        val original = world.getCityById(site.first)!!
        for (work in listOf(DomesticWork.ROAD, DomesticWork.FORTIFICATION)) {
            val intakeStock = warehouse(world).stock
            var recorder = ChangeRecorder()
            val result = submit(world, recorder, work)
            assertTrue(result.ok, result.toString()); assertEquals("reservationAccepted", result.type)
            save(world, recorder); world = cold(id, now)
            val admitted = works(world).active!!
            assertEquals(0, admitted.progress); assertEquals(Resources(), admitted.charged)
            assertEquals(work.name, admitted.requestId); assertEquals(site.second.edgeId, admitted.edgeId)
            if (work == DomesticWork.FORTIFICATION) {
                assertEquals(site.third.row, admitted.row); assertEquals(site.third.col, admitted.col)
            }
            assertEquals(intakeStock, warehouse(world).stock)
            recorder = ChangeRecorder(); DomesticBoundary(world, recorder, context).run(); save(world, recorder)
            world = cold(id, now)
            assertEquals(0, works(world).active!!.progress); assertEquals(intakeStock, warehouse(world).stock)
            if (work == DomesticWork.ROAD) {
                now = now.plus(1); world.setCurrentDate(now.year, now.month, now.phase)
                recorder = ChangeRecorder(); DomesticBoundary(world, recorder, context).run(); save(world, recorder)
                world = cold(id, now)
                assertEquals("INSUFFICIENT_STOCK", works(world).active!!.stopReason)
                assertEquals(0, works(world).active!!.progress); assertEquals(Resources(), warehouse(world).stock)
                recorder = ChangeRecorder()
                assertEquals(WarehouseSettlement.Result.APPLIED, WarehouseSettlement(world, recorder)
                    .settle(site.first, 1, warehouse(world).revision, Resources(), stock))
                save(world, recorder); world = cold(id, now)
            }
            val opening = warehouse(world).stock; val spec = context.design.works.getValue(work)
            repeat(100) {
                if (works(world).active != null) {
                    val previousProgress = works(world).active!!.progress
                    now = now.plus(1); world.setCurrentDate(now.year, now.month, now.phase)
                    recorder = ChangeRecorder(); DomesticBoundary(world, recorder, context).run(); save(world, recorder)
                    world = cold(id, now)
                    val active = works(world).active
                    val speed = (context.design.progressPerPhase * context.design.scaling.emptySeatPermille / 1000).coerceAtLeast(1)
                    assertEquals(minOf(spec.requiredProgress, previousProgress + speed), active?.progress ?: spec.requiredProgress)
                    val cumulative = expectedCharge(spec.cost, active?.progress ?: spec.requiredProgress, spec.requiredProgress)
                    assertEquals(opening.debit(cumulative), warehouse(world).stock)
                    if (active != null) { assertEquals(cumulative, active.charged); assertNull(active.stopReason) }
                    val before = world.getCityById(site.first)
                    assertTrue(DomesticBoundary(world, ChangeRecorder(), context).run()!!.alreadyStamped)
                    assertEquals(before, world.getCityById(site.first))
                }
            }
            assertNull(works(world).active); assertEquals(opening.debit(spec.cost), warehouse(world).stock)
            val completion = works(world).completed.single { it.work == work }
            assertEquals(site.second.edgeId, completion.edgeId)
            val response = DomesticViews.works(10, DomesticSnapshot(state = context.projection(world),
                warehouseStocks = mapOf(site.first to warehouse(world).stock)))
            assertEquals("READY", response.status)
            assertEquals(if (work == DomesticWork.ROAD) "도로 개척" else "보루",
                response.counties.single { it.countyId == site.first }.completed.single { it.work == work.name }.label)
            if (work == DomesticWork.ROAD) {
                val meta = mapOf(LandPassageState.META_KEY to storedEnv(id, LandPassageState.META_KEY))
                assertTrue(LandPassageState.read(meta, topology)!!.edgeStates.getValue(site.second.edgeId).active)
            } else {
                val fort = RoadFortState.read(mapOf(RoadFortState.META_KEY to storedEnv(id, RoadFortState.META_KEY))).single()
                assertEquals(RoadFort.siteId(site.second.edgeId, site.third.row, site.third.col), fort.id)
                assertEquals(site.third.provinceId, fort.provinceId)
                assertEquals(listOf(site.third.row, site.third.col, 1), listOf(fort.row, fort.col, fort.ownerNationId))
                assertEquals(fort, RoadFortState.read(world.getState().meta).single())
            }
            val before = world.getCityById(site.first)
            assertFalse(submit(world, ChangeRecorder(), work).ok)
            assertEquals(before, world.getCityById(site.first))
        }
        val built = world.getCityById(site.first)!!
        assertEquals(original.commerce, built.commerce); assertEquals(original.defence, built.defence)
        assertEquals(original.wall, built.wall)
        assertFalse(DomesticRules.assessReduce(WorkRequest(10, site.first, DomesticWork.FORTIFICATION),
            context.projection(world)) is DomesticAssessment.Eligible)
    }

    @Test fun `invalid foreign unauthorized duplicate and occupied inputs leave postgres state unchanged`() {
        val id = 812; val now = Phase(200, 1, 1); var world = seed(id)
        fun deny(code: String, actor: Int = 10, owner: Int = 42, body: String = args(DomesticWork.ROAD),
            work: DomesticWork = DomesticWork.ROAD) {
            val before = world.getCityById(site.first)
            val result = submit(world, ChangeRecorder(), work, actor = actor, owner = owner, body = body)
            assertFalse(result.ok); assertEquals(code, result.code)
            assertEquals(before, world.getCityById(site.first))
            assertEquals(before!!.meta, cold(id, now).getCityById(site.first)!!.meta)
        }
        deny("FORBIDDEN", owner = 43)
        deny("INVALID_COUNTY", actor = 11, owner = 43)
        deny("NOT_COUNTY_AUTHORITY", actor = 12, owner = 44)
        deny("INVALID_INFRASTRUCTURE_SITE", body = args(DomesticWork.ROAD).replace(site.second.edgeId, "not-a-gate"))
        var recorder = ChangeRecorder()
        assertTrue(submit(world, recorder, DomesticWork.ROAD).ok); save(world, recorder); world = cold(id, now)
        deny("WORK_IN_PROGRESS")
        // A fresh isolated world checks normal already-open and occupied-site rejection, without deleting an order.
        val openId = 813; world = seed(openId); recorder = ChangeRecorder()
        env(world, recorder, LandPassageState.META_KEY, LandPassageState.activate(world.getState().meta, topology, site.second.edgeId))
        save(world, recorder); world = cold(openId, now)
        val before = world.getCityById(site.first)
        assertFalse(submit(world, ChangeRecorder(), DomesticWork.ROAD).ok)
        assertFalse(submit(world, ChangeRecorder(), DomesticWork.FORTIFICATION,
            body = args(DomesticWork.FORTIFICATION).replace("\"row\":${site.third.row}", "\"row\":99999")).ok)
        recorder = ChangeRecorder()
        val fort = RoadFort(RoadFort.siteId(site.second.edgeId, site.third.row, site.third.col),
            site.second.edgeId, site.third.provinceId, site.third.row, site.third.col, 1, 100, 0)
        env(world, recorder, RoadFortState.META_KEY, RoadFortState.toMetaValue(listOf(fort)))
        save(world, recorder); world = cold(openId, now)
        assertFalse(submit(world, ChangeRecorder(), DomesticWork.FORTIFICATION).ok)
        assertEquals(before!!.meta, world.getCityById(site.first)!!.meta)
        assertEquals(before.meta, cold(openId, now).getCityById(site.first)!!.meta)
    }

    @Test fun `road closed after fort admission stops without debit and survives postgres reload`() {
        val id = 814; val now = Phase(200, 1, 1); var world = seed(id); var recorder = ChangeRecorder()
        env(world, recorder, LandPassageState.META_KEY, LandPassageState.activate(world.getState().meta, topology, site.second.edgeId))
        assertEquals(WarehouseSettlement.Result.APPLIED, WarehouseSettlement(world, recorder)
            .settle(site.first, 1, warehouse(world).revision, Resources(), stock))
        assertTrue(submit(world, recorder, DomesticWork.FORTIFICATION).ok)
        save(world, recorder); world = cold(id, now)
        val before = warehouse(world)
        recorder = ChangeRecorder(); env(world, recorder, LandPassageState.META_KEY, LandPassageState.initialMetaValue(topology))
        save(world, recorder); world = cold(id, now.plus(1))
        recorder = ChangeRecorder()
        assertEquals(1, DomesticBoundary(world, recorder, context).run()!!.worksStopped)
        save(world, recorder); world = cold(id, now.plus(1))
        val active = works(world).active!!
        assertEquals("INVALID_FORT_SITE", active.stopReason)
        assertEquals(0, active.progress); assertEquals(Resources(), active.charged)
        assertEquals(before, warehouse(world)); assertTrue(RoadFortState.read(world.getState().meta).isEmpty())
    }

    @Test fun `opened road occupied fort and captured county preserve installments and strategic state`() {
        for ((index, change) in listOf("ROAD_OPEN", "FORT_OCCUPIED", "COUNTY_CAPTURED").withIndex()) {
            val id = 815 + index; val now = Phase(200, 1, 1)
            var world = seed(id); var recorder = ChangeRecorder()
            assertEquals(WarehouseSettlement.Result.APPLIED, WarehouseSettlement(world, recorder)
                .settle(site.first, 1, warehouse(world).revision, Resources(), stock))
            val work = if (change == "ROAD_OPEN") DomesticWork.ROAD else DomesticWork.FORTIFICATION
            if (work == DomesticWork.FORTIFICATION) env(world, recorder, LandPassageState.META_KEY,
                LandPassageState.activate(world.getState().meta, topology, site.second.edgeId))
            assertTrue(submit(world, recorder, work).ok); save(world, recorder); world = cold(id, now)
            val before = warehouse(world); recorder = ChangeRecorder()
            when (change) {
                "ROAD_OPEN" -> env(world, recorder, LandPassageState.META_KEY,
                    LandPassageState.activate(world.getState().meta, topology, site.second.edgeId))
                "FORT_OCCUPIED" -> env(world, recorder, RoadFortState.META_KEY, RoadFortState.toMetaValue(listOf(
                    RoadFort(RoadFort.siteId(site.second.edgeId, site.third.row, site.third.col),
                        site.second.edgeId, site.third.provinceId, site.third.row, site.third.col, 2, 100, 0))))
                else -> {
                    val city = world.getCityById(site.first)!!; val captured = city.copy(nationId = 2)
                    world.applyCityDirtyFree(captured)
                    recorder.diffCity(PerTurnOverlay.toLogicCity(city), PerTurnOverlay.toLogicCity(captured))
                }
            }
            save(world, recorder); world = cold(id, now.plus(1))
            val passage = world.getState().meta[LandPassageState.META_KEY]
            val forts = RoadFortState.read(world.getState().meta)
            recorder = ChangeRecorder()
            assertEquals(1, DomesticBoundary(world, recorder, context).run()!!.worksStopped)
            save(world, recorder); world = cold(id, now.plus(1))
            val active = CountyWorks.read(world.getCityById(site.first)!!.meta)?.active
            if (change == "COUNTY_CAPTURED") assertNull(active) else {
                assertEquals(if (change == "ROAD_OPEN") "ROAD_ALREADY_OPEN" else "FORT_SITE_OCCUPIED", active!!.stopReason)
                assertEquals(0, active.progress); assertEquals(Resources(), active.charged)
            }
            assertEquals(before, warehouse(world)); assertEquals(passage, world.getState().meta[LandPassageState.META_KEY])
            assertEquals(forts, RoadFortState.read(world.getState().meta))
        }
    }
}
