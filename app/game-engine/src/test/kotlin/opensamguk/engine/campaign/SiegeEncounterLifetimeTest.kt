package opensamguk.engine.campaign

import kotlin.test.*
import java.sql.Timestamp
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.engine.boot.EnlistmentFixture
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.retainer.RetainerMonthlyService
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.economy.Resources
import opensamguk.logic.input.CorpsEncounter
import opensamguk.logic.input.CityMilitaryState
import opensamguk.logic.input.DeploymentState
import opensamguk.logic.war.BattleJournal
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

private val metadataJson = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
private fun assertMetadataJsonEquals(expected: String, actual: String) =
    assertEquals(metadataJson.readTree(expected), metadataJson.readTree(actual))

class SiegeMetadataJsonComparisonTest {
    @Test fun `only object key order is ignored at every depth`() {
        assertMetadataJsonEquals(
            """{"root":{"first":1,"second":null},"other":[{"x":1,"y":2}]}""",
            """{"other":[{"y":2,"x":1}],"root":{"second":null,"first":1}}""")
    }

    @Test fun `values missing keys null arrays and numeric differences fail equality`() {
        val differences = listOf(
            """{"v":1}""" to """{"v":2}""",
            """{"v":null}""" to """{}""",
            """{"v":null}""" to """{"v":0}""",
            """{"v":[1,2]}""" to """{"v":[2,1]}""",
            """{"v":[1]}""" to """{"v":[1,null]}""",
            """{"v":1.5}""" to """{"v":1.6}""",
            """{"v":1}""" to """{"v":1.0}""",
            """{"v":9223372036854775807}""" to """{"v":9223372036854775806}""",
            """{"v":0.12345678901234567890}""" to """{"v":0.12345678901234567891}""",
            """{"v":1}""" to """{"v":"1"}""",
        )
        for ((expected, actual) in differences) assertFailsWith<AssertionError>("$expected != $actual") {
            assertMetadataJsonEquals(expected, actual)
        }
    }
}

/** Deploy, arrive, besiege, and march a relief corps: no encounter or siege metadata is injected. */
private class SiegeEncounterLifetimeFixture {
    val campaign = CampaignWorldFixture()
    val route = campaign.route()
    val county = route.destinationCounty
    val outcomes = CampaignWorldFixture.RecordingOutcomes()

    data class Pending(val world: InMemoryTurnWorld, val recorder: ChangeRecorder)

    fun boundary(world: InMemoryTurnWorld, recorder: ChangeRecorder) {
        campaign.nextPhase(world)
        runBoundary(world, recorder)
    }

    fun runBoundary(world: InMemoryTurnWorld, recorder: ChangeRecorder) =
        PhaseBoundary(campaign.topology, campaign.metrics, campaign.cells, outcomes = outcomes).run(world, recorder)

    fun projection(pending: Pending) = DeploymentExecutor(pending.world, pending.recorder,
        campaign.topology, campaign.metrics).projection()

    fun pendingRelief(provisions: Int, settledTurns: Int, warehouseGrain: Long = 0L,
        seed: (InMemoryTurnWorld) -> InMemoryTurnWorld = { it }): Pending {
        val initialWorld = campaign.world(
            listOf(
                campaign.person(1, 1, route.startCity, userId = "42").copy(npcState = 0) to route.first,
                campaign.person(2, 2, route.startCity, userId = "43").copy(npcState = 0) to route.first,
            ),
            bugoks = listOf(campaign.unit(7, 1, 1000, provisions = provisions), campaign.unit(8, 2, 100)),
            cityChanges = { city -> if (city.id != county) city else city.copy(nationId = 2,
                meta = city.meta + mapOf(
                    CountyWarehouse.META_KEY to CountyWarehouse(county, 0, Resources(grain = warehouseGrain)).toMetaValue(),
                    CityMilitaryState.META_KEY to CityMilitaryState(100, 0, 100).toMetaValue(),
                )) },
        )
        val world = seed(initialWorld)
        val recorder = ChangeRecorder()
        campaign.deploy(world, recorder, 1, listOf(7), route.destination)
        campaign.nextPhase(world)
        campaign.movement(world, recorder).onTurn(1, CampaignWorldFixture.NO_INPUT)
        assertEquals(route.destination, world.positionOf(1))
        assertEquals(SiegeService.ACTIVE, world.getSiege(county)?.status, "normal arrival starts the siege")

        repeat(settledTurns - 1) { boundary(world, recorder) }
        campaign.deploy(world, recorder, 2, listOf(8), route.destination)
        boundary(world, recorder)
        assertEquals(settledTurns, world.getSiege(county)?.turns)
        assertTrue(CorpsMarchTurn(world, recorder, campaign.topology, campaign.metrics, campaign.cells).onTurn(2))
        val encounter = assertNotNull(CorpsEncounter.read(world.getGeneralById(2)!!.meta, campaign.topology))
        assertEquals(2, encounter.attacker.commanderGeneralId)
        assertEquals(listOf(1), encounter.defenders.map { it.commanderGeneralId })
        assertNotNull(BattleJournal.read(world.getGeneralById(2)!!.meta), "normal entry prepares combat")
        assertNotNull(DeploymentExecutor(world, recorder, campaign.topology, campaign.metrics).projection(),
            "the real producer starts with a valid deployment projection")
        return Pending(world, recorder)
    }

    fun assertPendingIntact(pending: Pending, deploymentBefore: Any?) {
        val (world, recorder) = pending
        assertNotNull(DeploymentExecutor(world, recorder, campaign.topology, campaign.metrics).projection(),
            "a phase boundary must not leave an encounter participant without its deployment; endReason=" +
                world.getSiege(county)?.endReason)
        assertEquals(deploymentBefore, world.getGeneralById(1)!!.meta[DeploymentState.META_KEY])
        assertEquals(SiegeService.ACTIVE, world.getSiege(county)?.status)
        assertEquals(2, world.getCityById(county)?.nationId, "the relief battle is still unresolved")
        for (id in listOf(1, 2)) assertNotNull(CorpsEncounter.read(world.getGeneralById(id)!!.meta, campaign.topology))
    }

    fun crossPendingBoundary(pending: Pending, monthly: Boolean) {
        val (world, recorder) = pending
        val before = world.getSiege(county)
        val deploymentBefore = world.getGeneralById(1)!!.meta[DeploymentState.META_KEY]
        val seals = listOf(1, 2).associateWith { id ->
            EncounterResolver.SEALED_KEYS.associateWith { world.getGeneralById(id)!!.meta[it] }
        }
        campaign.nextPhase(world)
        if (monthly) {
            assertEquals(1, world.getState().currentPhase)
            RetainerMonthlyService().settle(world, recorder)
            assertEquals(0, world.getBugokById(7)?.provisions)
        }
        assertNotNull(projection(pending), "the normal monthly consumer does not break the encounter")
        val units = world.listBugoks()
        runBoundary(world, recorder)
        assertPendingIntact(pending, deploymentBefore)
        assertEquals(before, world.getSiege(county), "pending settlement must not consume its phase stamp")
        assertEquals(units, world.listBugoks(), "the boundary cannot change pending combat units")
        for ((id, seal) in seals) for ((key, value) in seal) assertEquals(value, world.getGeneralById(id)!!.meta[key])
        runBoundary(world, recorder)
        assertEquals(before, world.getSiege(county), "repeated pending boundary stays deferred")
    }

    fun resolveRelief(pending: Pending) {
        val (world, recorder) = pending
        campaign.movement(world, recorder, outcomes).onTurn(2, CampaignWorldFixture.NO_INPUT)
        assertEquals(listOf(listOf(1) to listOf(2)), outcomes.encounters)
        for (id in listOf(1, 2)) {
            val meta = world.getGeneralById(id)!!.meta
            assertTrue(EncounterResolver.SEALED_KEYS.none { it in meta })
            assertEquals("DEFENDER_VICTORY", (meta[EncounterResolver.BATTLE_RECORD_KEY] as Map<*, *>)["outcome"])
        }
        assertNotNull(DeploymentState.read(world.getGeneralById(1)!!.meta))
        assertNull(DeploymentState.read(world.getGeneralById(2)!!.meta))
        assertNotNull(projection(pending))
    }

    fun finishSiege(pending: Pending, monthly: Boolean) {
        val (world, recorder) = pending
        boundary(world, recorder)
        val siege = assertNotNull(world.getSiege(county))
        assertEquals(if (monthly) SiegeService.LIFTED else SiegeService.FALLEN, siege.status)
        assertEquals(if (monthly) "BESIEGER_UNFED" else "STARVED", siege.endReason)
        assertEquals(if (monthly) 2 else 1, world.getCityById(county)?.nationId)
        assertNull(DeploymentState.read(world.getGeneralById(1)!!.meta))
        assertNotNull(projection(pending))
        assertEquals(if (monthly) 0 else 1, outcomes.captures.size)
        val units = world.listBugoks()
        val city = world.getCityById(county)
        runBoundary(world, recorder)
        assertEquals(siege, world.getSiege(county))
        assertEquals(units, world.listBugoks(), "capture must not detach a second garrison")
        assertEquals(city, world.getCityById(county))
        assertEquals(if (monthly) 0 else 1, outcomes.captures.size)
    }
}

class SiegeEncounterLifetimeTest {
    private val fixture = SiegeEncounterLifetimeFixture()

    @Test fun `fed pending battle still consumes a normal ration and records its boundary once`() = with(fixture) {
        val pending = pendingRelief(provisions = 100_000, settledTurns = 1, warehouseGrain = 100_000)
        val (world, recorder) = pending
        val before = assertNotNull(world.getSiege(county))
        val grainBefore = CountyWarehouse.read(world.getCityById(county)!!.meta, county)!!.stock.grain
        val deploymentBefore = world.getGeneralById(1)!!.meta[DeploymentState.META_KEY]
        val unitsBefore = world.listBugoks()
        val seals = listOf(1, 2).associateWith { id ->
            EncounterResolver.SEALED_KEYS.associateWith { world.getGeneralById(id)!!.meta[it] }
        }
        boundary(world, recorder)
        assertPendingIntact(pending, deploymentBefore)
        val after = assertNotNull(world.getSiege(county))
        assertEquals(before.turns + 1, after.turns)
        assertEquals(before.morale, after.morale)
        assertEquals(10_000L, grainBefore - CountyWarehouse.read(world.getCityById(county)!!.meta, county)!!.stock.grain)
        val now = world.getState()
        assertEquals(listOf(now.currentYear, now.currentMonth, now.currentPhase),
            listOf(after.settledYear, after.settledMonth, after.settledPhase))
        assertEquals("TURN", after.timeline.last()["event"])
        assertEquals(10_000, assertIs<Int>(after.timeline.last()["rationServed"]))
        assertEquals(unitsBefore, world.listBugoks())
        for ((id, seal) in seals) for ((key, value) in seal) assertEquals(value, world.getGeneralById(id)!!.meta[key])
        val warehouseAfter = CountyWarehouse.read(world.getCityById(county)!!.meta, county)
        runBoundary(world, recorder)
        assertEquals(after, world.getSiege(county))
        assertEquals(warehouseAfter, CountyWarehouse.read(world.getCityById(county)!!.meta, county))
    }

    @Test fun `starved county cannot dissolve a commander awaiting the relief battle`() = with(fixture) {
        val pending = pendingRelief(provisions = 100_000, settledTurns = 3)
        val deploymentBefore = pending.world.getGeneralById(1)!!.meta[DeploymentState.META_KEY]
        boundary(pending.world, pending.recorder)
        assertPendingIntact(pending, deploymentBefore)
    }

    @Test fun `normal monthly ration exhaustion cannot dissolve a pending battle participant`() = with(fixture) {
        val pending = pendingRelief(provisions = 1000, settledTurns = 1)
        val deploymentBefore = pending.world.getGeneralById(1)!!.meta[DeploymentState.META_KEY]
        campaign.nextPhase(pending.world)
        assertEquals(1, pending.world.getState().currentPhase, "the next boundary is monthly")
        // MonthlyPostUpdateHook invokes this production consumer before PhaseBoundary in runMonth.
        RetainerMonthlyService().settle(pending.world, pending.recorder)
        assertEquals(0, pending.world.getBugokById(7)?.provisions)
        PhaseBoundary(campaign.topology, campaign.metrics, campaign.cells).run(pending.world, pending.recorder)
        assertPendingIntact(pending, deploymentBefore)
    }

    @Test fun `starvation capture resumes once after relief is resolved`() = with(fixture) {
        val pending = pendingRelief(provisions = 100_000, settledTurns = 3)
        crossPendingBoundary(pending, monthly = false)
        resolveRelief(pending)
        finishSiege(pending, monthly = false)
    }

    @Test fun `fresh county supplies after combat cancel the deferred starvation condition`() = with(fixture) {
        val pending = pendingRelief(provisions = 100_000, settledTurns = 3)
        crossPendingBoundary(pending, monthly = false)
        resolveRelief(pending)
        val (world, recorder) = pending
        val before = assertNotNull(world.getSiege(county))
        val warehouse = CountyWarehouse.read(world.getCityById(county)!!.meta, county)!!
        assertEquals(WarehouseSettlement.Result.APPLIED,
            WarehouseSettlement(world, recorder).settle(county, 2, warehouse.revision,
                Resources(), Resources(grain = 10_000)))
        boundary(world, recorder)
        val after = assertNotNull(world.getSiege(county))
        assertEquals(SiegeService.ACTIVE, after.status)
        assertNull(after.endReason)
        assertEquals(before.turns + 1, after.turns)
        assertEquals(3750, after.morale, "feeding recovers the county morale from 2500")
        assertEquals(10_000, assertIs<Int>(after.timeline.last()["rationServed"]))
        assertEquals(2, world.getCityById(county)?.nationId)
        assertNotNull(DeploymentState.read(world.getGeneralById(1)!!.meta))
        assertTrue(outcomes.captures.isEmpty())
        runBoundary(world, recorder)
        assertEquals(after, world.getSiege(county))
    }

    @Test fun `ration exhaustion ends the expedition once after relief is resolved`() = with(fixture) {
        val pending = pendingRelief(provisions = 1000, settledTurns = 1)
        crossPendingBoundary(pending, monthly = true)
        resolveRelief(pending)
        finishSiege(pending, monthly = true)
    }
}

/** The same normal producers, committed through the daemon payload and reloaded from PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SiegeEncounterLifetimePersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable, "Docker unavailable")
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source),
            TransactionTemplate(DataSourceTransactionManager(source)))
    }

    @BeforeEach fun clearWorld() { jdbc.execute("TRUNCATE world_state CASCADE") }
    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    /** Seed only an idle world. Siege, deployment and combat seals are written by the real producers. */
    private fun seed(world: InMemoryTurnWorld): InMemoryTurnWorld {
        val state = world.getState()
        jdbc.update("""INSERT INTO world_state(id,scenario_code,current_year,current_month,current_phase,
            tick_seconds,config,meta) VALUES (1,'siege-encounter-it',?,?,?,?,?::jsonb,?::jsonb)""",
            state.currentYear, state.currentMonth, state.currentPhase, state.tickSeconds,
            MetaJson.encode(state.config), MetaJson.encode(state.meta))
        jdbc.update("""INSERT INTO ng_games(world_id,server_id,date,season,scenario,scenario_name,env)
            VALUES (1,'siege-encounter-it','2000-01-01T00:00:00Z',1,0,'Siege encounter test','{}'::jsonb)""")
        for (city in world.listCities()) {
            jdbc.update("""INSERT INTO city(world_id,id,name,level,nation_id,pop,pop_max,agri,agri_max,comm,comm_max,
                secu,secu_max,def,def_max,wall,wall_max,region,meta)
                VALUES (1,?,?,?,?,?,?,?,?,?,?,100,1000,?,?,?,?,1,?::jsonb)""",
                city.id, city.name, city.level, city.nationId, city.population, city.populationMax,
                city.agriculture, city.agricultureMax, city.commerce, city.commerceMax,
                city.defence, city.defenceMax, city.wall, city.wallMax, MetaJson.encode(city.meta))
        }
        for (nation in world.listNations()) {
            jdbc.update("""INSERT INTO nation(world_id,id,name,color,gold,level,capital_city_id,meta)
                VALUES (1,?,?,?,?,?,?,?::jsonb)""", nation.id, nation.name, nation.color, nation.gold,
                nation.level, nation.capitalCityId, MetaJson.encode(nation.meta))
        }
        for (general in world.listGenerals()) {
            jdbc.update("""INSERT INTO general(world_id,id,user_id,name,nation_id,city_id,npc_state,officer_level,
                gold,rice,crew,leadership,strength,intel,politics,charm,turn_time,meta,last_turn)
                VALUES (1,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)""",
                general.id, general.userId, general.name, general.nationId, general.cityId,
                general.npcState, general.officerLevel, general.gold, general.rice, general.crew,
                general.stats.leadership, general.stats.strength, general.stats.intelligence,
                general.stats.politics, general.stats.charm, Timestamp.from(general.turnTime),
                MetaJson.encode(general.meta), MetaJson.encode(opensamguk.logic.domain.LastTurn().toRaw()))
            val position = world.generalPositionSnapshot()!!.statesByGeneralId.getValue(general.id)
            jdbc.update("""INSERT INTO general_spatial_position(world_id,general_id,topology_revision,
                topology_hash,node_kind,node_id,revision) VALUES (1,?,?,?,'LAND_PROVINCE',?,?)""",
                general.id, position.topologyRevision, position.topologyHash,
                (position.node as StrategicNodeRef.LandProvince).id, position.revision)
        }
        for (unit in world.listBugoks()) {
            jdbc.update("""INSERT INTO general_bugok(world_id,id,master_general_id,name,troops,crew_type_id,
                training,morale,fatigue,provisions) VALUES (1,?,?,?,?,?,?,?,?,?)""",
                unit.id, unit.masterGeneralId, unit.name, unit.troops, unit.crewTypeId,
                unit.training, unit.morale, unit.fatigue, unit.provisions)
        }
        for (relation in world.listDiplomacy()) {
            jdbc.update("""INSERT INTO diplomacy(world_id,src_nation_id,dest_nation_id,state_code,term)
                VALUES (1,?,?,?,?)""", relation.fromNationId, relation.toNationId, relation.state, relation.term)
        }
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM siege WHERE world_id=1", Int::class.java))
        // Bootstrap before any producer, including the loader's column-backed general metadata.
        return InMemoryTurnWorld(EnlistmentFixture(jdbc, flush).load(1))
    }

    private fun roundTrip(fixture: SiegeEncounterLifetimeFixture,
        pending: SiegeEncounterLifetimeFixture.Pending): SiegeEncounterLifetimeFixture.Pending {
        val (world, recorder) = pending
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        flush.flush(payload.copy(worldStateUpdate = payload.worldStateUpdate + mapOf(
            "expected_world_version" to world.getState().worldVersion,
            "writer_epoch" to world.getState().writerEpoch,
        )))
        world.advanceWorldVersionAfterCommit()
        recorder.clear()
        val loaded = InMemoryTurnWorld(EnlistmentFixture(jdbc, flush).load(1))
        assertEquals(world.getState().worldVersion, loaded.getState().worldVersion)
        assertEquals(world.getSiege(fixture.county), loaded.getSiege(fixture.county))
        assertEquals(world.listBugoks().sortedBy { it.id }, loaded.listBugoks().sortedBy { it.id })
        assertEquals(world.getCityById(fixture.county)?.nationId, loaded.getCityById(fixture.county)?.nationId)
        assertMetadataJsonEquals(MetaJson.encode(world.getCityById(fixture.county)!!.meta),
            MetaJson.encode(loaded.getCityById(fixture.county)!!.meta))
        for (id in listOf(1, 2)) {
            assertMetadataJsonEquals(MetaJson.encode(world.getGeneralById(id)!!.meta),
                MetaJson.encode(loaded.getGeneralById(id)!!.meta))
            assertEquals(world.positionOf(id), loaded.positionOf(id))
        }
        return SiegeEncounterLifetimeFixture.Pending(loaded, ChangeRecorder()).also {
            assertNotNull(fixture.projection(it), "the committed world must reload a valid deployment projection")
        }
    }

    private fun persistsAcrossBoundaryAndResolution(monthly: Boolean) {
        val fixture = SiegeEncounterLifetimeFixture()
        var pending = fixture.pendingRelief(if (monthly) 1000 else 100_000, if (monthly) 1 else 3, seed = ::seed)
        pending = roundTrip(fixture, pending) // Actual arrival, siege and relief encounter producer flush.
        fixture.crossPendingBoundary(pending, monthly)
        pending = roundTrip(fixture, pending)
        fixture.resolveRelief(pending)
        pending = roundTrip(fixture, pending)
        fixture.finishSiege(pending, monthly)
        pending = roundTrip(fixture, pending)
        val rows = jdbc.queryForObject("SELECT count(*) FROM log_entry WHERE world_id=1", Int::class.java)
        fixture.runBoundary(pending.world, pending.recorder)
        roundTrip(fixture, pending)
        assertEquals(rows, jdbc.queryForObject("SELECT count(*) FROM log_entry WHERE world_id=1", Int::class.java),
            "a committed terminal siege must not apply or record its capture or dissolution twice")
    }

    @Test fun `starvation pending and resolved states survive actual flush and reload`() =
        persistsAcrossBoundaryAndResolution(monthly = false)

    @Test fun `ration exhaustion pending and resolved states survive actual flush and reload`() =
        persistsAcrossBoundaryAndResolution(monthly = true)

    @Test fun `TURN grain at Int boundary and above survives actual flush and reload exactly`() {
        for (remaining in listOf(Int.MAX_VALUE.toLong(), Int.MAX_VALUE.toLong() + 1, Long.MAX_VALUE - 10_000)) {
            clearWorld()
            val fixture = SiegeEncounterLifetimeFixture()
            val pending = fixture.pendingRelief(100_000, 1, warehouseGrain = remaining + 10_000, seed = ::seed)
            val turn = pending.world.getSiege(fixture.county)!!.timeline.last()
            assertEquals(10_000, assertIs<Int>(turn["rationDemand"]))
            assertEquals(10_000, assertIs<Int>(turn["rationServed"]))
            if (remaining <= Int.MAX_VALUE) assertEquals(remaining.toInt(), assertIs<Int>(turn["grainAfter"]))
            else assertEquals(remaining, assertIs<Long>(turn["grainAfter"]))
            roundTrip(fixture, pending)
        }
    }
}
