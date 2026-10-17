package opensamguk.engine.campaign

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.infra.battle.realtime.*
import opensamguk.infra.persistence.*
import opensamguk.logic.battle.realtime.*
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.*
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Connection
import kotlin.test.*
import org.junit.jupiter.api.Test

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
internal class BattleOriginProducerIT : BattleOriginProducerFixture() {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var source: DriverManagerDataSource
    private lateinit var jdbc: JdbcTemplate
    private lateinit var named: NamedParameterJdbcTemplate
    private lateinit var tx: TransactionTemplate
    private lateinit var executor: JdbcFlushExecutor
    private val log = LoggerFactory.getLogger(JdbcCampaignBattleOriginWriter::class.java) as Logger
    private lateinit var messages: ListAppender<ILoggingEvent>

    @BeforeAll fun setup() {
        postgres = PostgreSQLContainer("postgres:16-alpine").apply { start() }
        source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source); named = NamedParameterJdbcTemplate(source)
        tx = TransactionTemplate(DataSourceTransactionManager(source)); executor = JdbcFlushExecutor(named, tx)
    }
    @AfterAll fun stop() { if (this::postgres.isInitialized) postgres.stop() }
    @BeforeEach fun seed() {
        jdbc.execute("TRUNCATE world_state CASCADE")
        jdbc.update("INSERT INTO world_state (id,scenario_code,current_year,current_month,tick_seconds,world_version,writer_epoch) " +
            "VALUES (1,'scenario_2',200,1,3600,0,7)")
        for (g in world().listGenerals()) jdbc.update(
            "INSERT INTO general (world_id,id,name,nation_id,city_id,user_id,npc_state,leadership,strength,intel,turn_time) " +
                "VALUES (1,?,?,?,1,?,?,110,70,60,now())", g.id, g.name, g.nationId, g.userId, g.npcState)
        jdbc.update("INSERT INTO general_retainers (world_id,id,master_general_id,origin,general_id,name,relation,release_policy) " +
            "VALUES (1,9,2,'EXISTING',3,'deputy','lieutenant','MUTUAL')")
        for (b in world().listBugoks()) jdbc.update(
            "INSERT INTO general_bugok (world_id,id,master_general_id,name,troops,crew_type_id,training,morale,fatigue,provisions,commander_retainer_id) " +
                "VALUES (1,?,?,?,?,?,?,?,?,?,?)", b.id, b.masterGeneralId, b.name, b.troops, b.crewTypeId,
            b.training, b.morale, b.fatigue, b.provisions, b.commanderRetainerId)
        messages = ListAppender<ILoggingEvent>().apply { start() }; log.addAppender(messages)
    }
    @AfterEach fun detach() { if (this::messages.isInitialized) { log.detachAppender(messages); messages.stop() } }
    private fun payload(world: InMemoryTurnWorld = world(), expected: Long? = 0): FlushPayload {
        val e = encounter(); val recorder = ChangeRecorder()
        recorder.recordCampaignBattleOrigin(BattleOriginProducer.draft(e, forces(e), relations(e), world, "b".repeat(64)).draft!!)
        val base = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        return if (expected == null) base else base.copy(worldStateUpdate = base.worldStateUpdate +
            mapOf("expected_world_version" to expected, "writer_epoch" to 7L))
    }
    private fun count() = jdbc.queryForObject("SELECT count(*) FROM campaign_battle_origin", Int::class.java)
    private fun version() = jdbc.queryForObject("SELECT world_version FROM world_state WHERE id=1", Long::class.java)
    private fun read(): CampaignBattleCurrentRead = source.connection.use { connection ->
        connection.isReadOnly = true; connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
        connection.autoCommit = false
        try { JdbcCampaignBattleOriginReader().readCommitted(connection, WorldId(1), encounter().encounterId)!! }
        finally { connection.rollback() }
    }
    private fun assertReason(reason: String, detail: String? = null) {
        assertTrue(messages.list.any { it.formattedMessage.contains("reason=$reason") &&
            (detail == null || it.formattedMessage.contains("detail=$detail")) }, messages.list.joinToString { it.formattedMessage })
    }

    @Test fun `CAS flush writes raw forces revisions authority and reader round trip binding`() {
        val world = world()
        world.updateBugok(world.getBugokById(11)!!.copy(provisions = 601))
        executor.flush(payload(world))
        assertEquals(1, count()); assertEquals(1L, version())
        val current = read(); val origin = current.origin
        assertEquals(version(), origin.committedWorldVersion); assertEquals(7L, origin.writerEpoch)
        assertEquals(origin.committedWorldVersion, origin.lockGeneration); assertEquals(1L, origin.lockSetRevision)
        assertEquals(110, assertNotNull(current.forces).commanders.first().leadership)
        assertEquals(600, current.forces!!.units.first().provisions)
        assertEquals(3, origin.units.size)
        assertEquals(listOf(3, 3), origin.units.filter { it.ownerGeneralId == 2 }.map { it.commanderGeneralId })
        assertEquals(current.currentBugokRevisions, origin.units.associate { it.sourceKey.sourceId.toInt() to it.sourceRevision })
        assertEquals(2L, origin.units.first { it.sourceKey.sourceId == "11" }.sourceRevision)
        assertEquals(CampaignBattleOriginCodec.sha256(CampaignBattleOriginCodec.encode(origin)), current.sourceSha256)
        val native = world.listGenerals().filter { it.id in setOf(1, 2) }.map {
            BattleOwnerNativeGeneral(1, it.id, it.userId, it.npcState) }
        val expected = BattleOwnerAuthorityBinding.fromOrigin(origin, native).single()
        val loaded = assertNotNull(JdbcBattleOwnerOwnershipSource(WorldId(1), source, JdbcCampaignBattleOriginReader()).read(expected))
        assertTrue(expected.matches(loaded)); assertEquals(origin.owners, current.currentOwners)
    }
    @Test fun `absent or partial fence skips only origin and commits native content`() {
        for (fence in listOf(emptyMap(), mapOf("expected_world_version" to 0L), mapOf("writer_epoch" to 7L))) {
            val world = world(); world.updateBugok(world.getBugokById(11)!!.copy(provisions = 602))
            val base = payload(world, null)
            executor.flush(base.copy(worldStateUpdate = base.worldStateUpdate + fence))
            assertEquals(0, count()); assertEquals(602, jdbc.queryForObject("SELECT provisions FROM general_bugok WHERE id=11", Int::class.java))
            assertReason("NO_WORLD_FENCE")
        }
    }
    @Test fun `stale CAS rolls back all native writes and cannot leave an origin`() {
        val world = world(); world.updateBugok(world.getBugokById(11)!!.copy(provisions = 999))
        assertFailsWith<StaleWorldWriterException> { executor.flush(payload(world, expected = 99)) }
        assertEquals(0, count()); assertEquals(0L, version())
        assertEquals(600, jdbc.queryForObject("SELECT provisions FROM general_bugok WHERE id=11", Int::class.java))
    }
    @Test fun `post draft owner boundary table is reclassified from DB with exact detail and flush commits`() {
        val cases = listOf(Triple(null, 0, "HUMAN_METADATA_MISSING"), Triple(null, 1, "HUMAN_METADATA_MISSING"),
            Triple("42", 2, "NPC_WITH_ACCOUNT")) + listOf("042", "", " 42", "42 ", "+42", "0", "-1", "4.2", "2147483648")
            .map { Triple(it, 0, "NONCANONICAL_ACCOUNT") }
        for ((user, npc, detail) in cases) {
            val p = payload(expected = version())
            val changed = PerTurnOverlay.toLogicGeneral(world().getGeneralById(1)!!.copy(userId = user, npcState = npc, gold = 777))
            executor.flush(p.copy(updatedGenerals = listOf(changed)))
            assertEquals(0, count()); assertReason("OWNER_AUTHORITY_INVALID", detail)
            assertEquals(777, jdbc.queryForObject("SELECT gold FROM general WHERE id=1", Int::class.java))
            jdbc.update("UPDATE general SET user_id='42',npc_state=0 WHERE id=1")
        }
    }
    @Test fun `accepted native boundary rows retain exact current authority classification`() {
        for ((user, npc) in listOf(null to 2, null to 9, "1" to 0, "42" to 1, "2147483647" to 0)) {
            jdbc.update("DELETE FROM campaign_battle_origin")
            val p = payload(expected = version())
            val changed = PerTurnOverlay.toLogicGeneral(world().getGeneralById(1)!!.copy(userId = user, npcState = npc))
            executor.flush(p.copy(updatedGenerals = listOf(changed)))
            assertEquals(1, count())
            val owner = read().origin.owners.first { it.ownerGeneralId == 1 }
            assertEquals(user?.toInt(), owner.accountId); assertEquals(npc, owner.npcState)
            assertEquals(user != null, owner.playable)
            jdbc.update("UPDATE general SET user_id='42',npc_state=0 WHERE id=1")
        }
    }
    @Test fun `binding rejection omits whole origin after successful native classification`() {
        val e = encounter().let { it.copy(defenders = it.defenders.map { d -> d.copy(ownerGeneralId = 1) }) }
        val original = forces(encounter())
        val f = opensamguk.logic.input.EncounterForces(e.encounterId,
            original.units.map { if (it.bugokId != 11) it.copy(ownerGeneralId = 1) else it }, original.commanders)
        val draft = BattleOriginProducer.draft(e, f, relations(e), world(), "b".repeat(64)).draft!!
        val p = payload().copy(campaignBattleOriginDrafts = listOf(draft))
        executor.flush(p)
        assertEquals(0, count()); assertEquals(1L, version())
        assertReason("OWNER_AUTHORITY_INVALID", "BINDING_REJECTED")
    }
    @Test fun `duplicate nonowner and duplicate owner accounts fail without dropping other writes`() {
        for (id in listOf(2, 3)) {
            val p = payload(expected = version())
            val changed = PerTurnOverlay.toLogicGeneral(world().getGeneralById(id)!!.copy(userId = "42", npcState = 0, gold = 555))
            executor.flush(p.copy(updatedGenerals = listOf(changed)))
            assertEquals(0, count()); assertReason("OWNER_AUTHORITY_INVALID", "DUPLICATE_ACCOUNT")
            assertEquals(555, jdbc.queryForObject("SELECT gold FROM general WHERE id=?", Int::class.java, id))
            jdbc.update("UPDATE general SET user_id=NULL,npc_state=2 WHERE id=?", id)
        }
    }
    @Test fun `identical retry is idempotent and different bytes keep immutable row while flush commits`() {
        val p = payload(); executor.flush(p)
        val original = jdbc.queryForObject("SELECT source_sha256 FROM campaign_battle_origin", String::class.java)
        val retry = tx.execute { JdbcCampaignBattleOriginWriter().finalizeAndInsert(named, WorldId(1), p.worldStateUpdate, p.campaignBattleOriginDrafts).single() }!!
        assertNull(retry.reason); assertEquals(1, count())
        executor.flush(p.copy(worldStateUpdate = p.worldStateUpdate + mapOf("expected_world_version" to 1L)))
        assertReason("ORIGIN_CONFLICT"); assertEquals(1, count()); assertEquals(2L, version())
        assertEquals(original, jdbc.queryForObject("SELECT source_sha256 FROM campaign_battle_origin", String::class.java))
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM campaign_battle_owner_authority", Int::class.java))
    }
    @Test fun `older bugok revision cannot overwrite a newer DB row and rolls back CAS`() {
        jdbc.update("UPDATE general_bugok SET revision=5 WHERE id=11")
        val world = world(); world.updateBugok(world.getBugokById(11)!!.copy(provisions = 999))
        assertFailsWith<IllegalStateException> { executor.flush(payload(world)) }
        assertEquals(0L, version()); assertEquals(0, count())
        assertEquals(5L, jdbc.queryForObject("SELECT revision FROM general_bugok WHERE id=11", Long::class.java))
    }
    @Test fun `forces JSON tampering cannot be read as committed origin`() {
        executor.flush(payload())
        jdbc.update("UPDATE campaign_battle_origin SET forces_json=jsonb_set(forces_json,'{snapshotId}','\"bad\"'::jsonb)")
        assertFailsWith<IllegalArgumentException> { read() }
    }
}
