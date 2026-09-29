package opensamguk.engine.campaign

import java.time.Instant
import java.sql.Timestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.engine.boot.EnlistmentFixture
import opensamguk.engine.flush.FlushRecoveryGate
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.persistence.StaleWorldWriterException
import opensamguk.logic.input.CorpsEncounter
import opensamguk.logic.world.StrategicNodeRef
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.TestInstance
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.springframework.dao.QueryTimeoutException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** QA-only commit boundary for a sealed battle. The production daemon keeps its default NOOP observer. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BattleEvidencePostFlushIT {
    private val worldId = WorldId(1)
    private val start = Instant.parse("0200-01-01T00:00:00Z")
    private val runTime = start.plusSeconds(7_200)
    private val campaign = CampaignWorldFixture()
    private val route = campaign.route()
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var named: NamedParameterJdbcTemplate
    private lateinit var tx: TransactionTemplate

    @BeforeAll
    fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable — DB evidence IT skipped")
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        named = NamedParameterJdbcTemplate(source)
        tx = TransactionTemplate(DataSourceTransactionManager(source))
    }

    @BeforeEach
    fun clearWorld() { jdbc.execute("TRUNCATE world_state CASCADE") }

    @AfterAll
    fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    private class PendingEvidence(private val drop: Boolean = false) : BattleOutcomeObserver {
        val pending = mutableListOf<BattleOutcomeObservation>()
        val published = mutableListOf<BattleOutcomeObservation>()
        private val seen = mutableSetOf<Pair<Int, String>>()
        var duplicate = false
            private set
        var quarantined = false
            private set

        override fun onResolved(observation: BattleOutcomeObservation) {
            if (drop) return
            val key = observation.worldId to observation.encounterId
            if (!seen.add(key)) duplicate = true
            else pending += observation
        }

        fun publishAfterCommit() {
            check(!quarantined && !duplicate) { "duplicate or quarantined battle evidence" }
            published += pending
            pending.clear()
        }

        fun quarantine() {
            quarantined = true
            pending.clear()
        }
    }

    private data class RunFixture(
        val world: InMemoryTurnWorld,
        val service: TurnRunService,
        val evidence: PendingEvidence,
        val encounterId: String,
    )

    private fun fixture(
        evidence: PendingEvidence = PendingEvidence(),
        failFirstFlush: Boolean = false,
        failCas: Boolean = false,
        failAfterCommit: Boolean = false,
    ): RunFixture {
        val attacker = campaign.person(1, 1, route.startCity).copy(npcState = 0)
        val defender = campaign.person(100, 2, route.startCity)
            .copy(npcState = 0, turnTime = start.plusSeconds(86_400)) // Only the attacker resolves this tick.
        val world = campaign.world(
            listOf(attacker to route.start, defender to route.first),
            bugoks = listOf(campaign.unit(7, 1, 1_000), campaign.unit(1100, 100, 100)),
        )
        val recorder = ChangeRecorder()
        campaign.deploy(world, recorder, 1, listOf(7), route.destination)
        campaign.deploy(world, recorder, 100, listOf(1100), route.first)
        campaign.nextPhase(world)
        assertTrue(CorpsMarchTurn(world, recorder, campaign.topology, campaign.metrics, campaign.cells).onTurn(1))
        val encounterId = CorpsEncounter.read(world.getGeneralById(1)!!.meta, campaign.topology)!!.encounterId
        seedCommittedEncounter(world, encounterId)
        world.consumeDirtyState() // The seed is the committed pre-tick state, not another pending batch.
        recorder.clear()

        val redis = mock(StringRedisTemplate::class.java)
        if (failAfterCommit) doThrow(IllegalStateException("post-commit realtime publish failed"))
            .`when`(redis).convertAndSend(anyString(), anyString())
        var flushCalls = 0
        val flush = object : JdbcFlushExecutor(named, tx) {
            override fun flush(payload: FlushPayload) {
                flushCalls++
                if (failCas) throw StaleWorldWriterException(1, 0L, 1L)
                if (failFirstFlush && flushCalls == 1) throw QueryTimeoutException("transient QA flush failure")
                super.flush(payload)
            }
        }
        val service = EnlistmentFixture(jdbc, flush).service(worldId, world, mutableListOf(),
            movementFactory = { movementRecorder -> AssignmentMarchTurn(world, movementRecorder,
                campaign.topology, campaign.metrics, campaign.cells, observations = evidence)::onTurn },
            redisTemplate = redis)
        return RunFixture(world, service, evidence, encounterId)
    }

    private fun seedCommittedEncounter(world: InMemoryTurnWorld, encounterId: String) {
        val state = world.getState()
        jdbc.update("""INSERT INTO world_state(id,scenario_code,current_year,current_month,current_phase,tick_seconds,config,meta)
            VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb)""", worldId.value, "battle-evidence-it", state.currentYear,
            state.currentMonth, state.currentPhase, state.tickSeconds, MetaJson.encode(state.config),
            MetaJson.encode(state.meta + ("lastTurnTime" to start.toString())))
        val city = world.getCityById(route.startCity)!!
        jdbc.update("""INSERT INTO city(world_id,id,name,level,nation_id,pop,pop_max,agri,agri_max,comm,comm_max,
            secu,secu_max,def,def_max,wall,wall_max,region)
            VALUES (?,?,?,?,?,1000,10000,100,1000,100,1000,100,1000,100,1000,100,1000,1)""",
            worldId.value, city.id, city.name, city.level, city.nationId)
        for (nation in world.listNations()) {
            jdbc.update("INSERT INTO nation(world_id,id,name,color,gold) VALUES (?,?,?,?,?)",
                worldId.value, nation.id, nation.name, nation.color, nation.gold)
        }
        for (general in world.listGenerals()) {
            jdbc.update("""INSERT INTO general(world_id,id,name,nation_id,city_id,npc_state,officer_level,
                gold,rice,crew,leadership,strength,intel,politics,charm,turn_time,last_turn,meta)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'{"command":"휴식"}'::jsonb,?::jsonb)""",
                worldId.value, general.id, general.name, general.nationId, general.cityId,
                general.npcState, general.officerLevel, general.gold, general.rice, general.crew,
                general.stats.leadership, general.stats.strength, general.stats.intelligence,
                general.stats.politics, general.stats.charm, Timestamp.from(general.turnTime),
                MetaJson.encode(general.meta))
            val position = world.generalPositionSnapshot()!!.statesByGeneralId.getValue(general.id)
            val node = position.node as StrategicNodeRef.LandProvince
            jdbc.update("""INSERT INTO general_spatial_position(world_id,general_id,topology_revision,
                topology_hash,node_kind,node_id,revision) VALUES (?,?,?,?,'LAND_PROVINCE',?,?)""",
                worldId.value, general.id, position.topologyRevision, position.topologyHash,
                node.id, position.revision)
        }
        for (unit in world.listBugoks()) {
            jdbc.update("""INSERT INTO general_bugok(world_id,id,master_general_id,name,troops,crew_type_id,
                training,morale,provisions) VALUES (?,?,?,?,?,?,?,?,?)""",
                worldId.value, unit.id, unit.masterGeneralId, unit.name, unit.troops,
                unit.crewTypeId, unit.training, unit.morale, unit.provisions)
        }
        jdbc.update("""INSERT INTO log_entry(world_id,scope,category,year,month,phase,text,general_id,
            event_kind,meta) VALUES (?,'GENERAL','ACTION',200,1,2,'sealed encounter',1,'march.corps',?::jsonb)""",
            worldId.value, MetaJson.encode(mapOf("refs" to mapOf("stop" to "ENCOUNTER",
                "encounterId" to encounterId))))
    }

    private fun committedVersion(): Long = jdbc.queryForObject(
        "SELECT world_version FROM world_state WHERE id=?", Long::class.java, worldId.value)!!

    private fun sealedIds(): Set<String> = jdbc.queryForList("""SELECT DISTINCT meta->'refs'->>'encounterId'
        FROM log_entry WHERE world_id=? AND event_kind='march.corps'
          AND meta->'refs'->>'stop'='ENCOUNTER'""", String::class.java, worldId.value).toSet()

    private fun requireFullCoverage(evidence: PendingEvidence) {
        check(sealedIds() == evidence.published.map { it.encounterId }.toSet()) {
            "DB sealed encounter IDs differ from published QA battle outcomes"
        }
    }

    @Test
    fun `normal tick publishes one result only after real JDBC commit`() {
        val run = fixture()
        run.service.runTick(runTime)
        assertEquals(1L, committedVersion())
        assertEquals(listOf(run.encounterId), run.evidence.pending.map { it.encounterId })
        assertTrue(run.evidence.published.isEmpty())
        run.evidence.publishAfterCommit()
        requireFullCoverage(run.evidence)
        assertEquals(1, run.evidence.published.size)
    }

    @Test
    fun `failed flush keeps observation private until retained retry commits once`() {
        val run = fixture(failFirstFlush = true)
        assertFailsWith<QueryTimeoutException> { run.service.runTick(runTime) }
        assertEquals(0L, committedVersion())
        assertEquals(FlushRecoveryGate.Mode.FLUSH_RETRY, run.service.recoverySnapshot().mode)
        assertEquals(1, run.evidence.pending.size)
        assertTrue(run.evidence.published.isEmpty())
        assertTrue(run.service.retryRetainedFlush())
        assertEquals(1L, committedVersion())
        run.evidence.publishAfterCommit()
        run.evidence.publishAfterCommit() // Empty batch: never publishes the retained result twice.
        requireFullCoverage(run.evidence)
        assertEquals(1, run.evidence.published.size)
    }

    @Test
    fun `stale writer forces reload and quarantines unpublished result`() {
        val run = fixture(failCas = true)
        assertFailsWith<StaleWorldWriterException> { run.service.runTick(runTime) }
        assertEquals(0L, committedVersion())
        assertEquals(FlushRecoveryGate.Mode.RELOAD_REQUIRED, run.service.recoverySnapshot().mode)
        assertFailsWith<IllegalStateException> { run.service.retryRetainedFlush() }
        run.evidence.quarantine()
        assertTrue(run.evidence.published.isEmpty())
        assertTrue(run.evidence.pending.isEmpty())
    }

    @Test
    fun `duplicate encounter observation makes QA publication fail`() {
        val run = fixture()
        run.service.runTick(runTime)
        val first = run.evidence.pending.single()
        run.evidence.onResolved(first)
        assertFailsWith<IllegalStateException> { run.evidence.publishAfterCommit() }
        assertTrue(run.evidence.published.isEmpty())
        assertEquals(1L, committedVersion())
    }

    @Test
    fun `missing observer row fails independent DB sealed ID comparison`() {
        val run = fixture(evidence = PendingEvidence(drop = true))
        run.service.runTick(runTime)
        assertEquals(1L, committedVersion())
        run.evidence.publishAfterCommit()
        assertEquals(setOf(run.encounterId), sealedIds())
        assertFailsWith<IllegalStateException> { requireFullCoverage(run.evidence) }
    }

    @Test
    fun `seeded seal cannot hide missing committed battle settlement`() {
        val run = fixture()
        run.service.runTick(runTime)
        run.evidence.publishAfterCommit()
        assertEquals(1L, committedVersion())
        val battleRows = jdbc.queryForObject(
            "SELECT count(*) FROM general WHERE world_id=? AND meta->'lastBattle' IS NOT NULL",
            Long::class.java, worldId.value)!!
        assertTrue(battleRows > 0, "the tick must persist a battle before this probe removes it")
        jdbc.update("UPDATE general SET meta=meta - 'lastBattle' WHERE world_id=?", worldId.value)
        assertEquals(setOf(run.encounterId), sealedIds(), "the pre-tick seed remains after settlement removal")
        assertFailsWith<IllegalStateException> { requireFullCoverage(run.evidence) }
    }

    @Test
    fun `post commit exception quarantines result and exposes missing export`() {
        val run = fixture(failAfterCommit = true)
        assertFailsWith<IllegalStateException> { run.service.runTick(runTime) }
        assertEquals(1L, committedVersion(), "JDBC commit happened before realtime publication")
        assertTrue(run.service.recoverySnapshot().ready)
        assertFalse(run.evidence.pending.isEmpty())
        run.evidence.quarantine()
        assertFailsWith<IllegalStateException> { requireFullCoverage(run.evidence) }
        assertTrue(run.evidence.published.isEmpty())
    }
}
