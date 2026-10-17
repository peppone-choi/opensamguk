package opensamguk.engine.campaign

import java.time.Instant
import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.boot.EnlistmentFixture
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.*
import opensamguk.logic.input.*
import opensamguk.logic.council.CurrentRulerBinding
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

/** #1456 baseline: real reserved personal turns and PostgreSQL cold readback, not product QA. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IndependencePersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var fixture: EnlistmentFixture
    private lateinit var reservations: ReservedTurnRepository
    private val due = Instant.parse("0200-01-01T00:00:01Z")

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: independence persistence NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        val named = NamedParameterJdbcTemplate(source)
        fixture = EnlistmentFixture(jdbc, JdbcFlushExecutor(named,
            TransactionTemplate(DataSourceTransactionManager(source))))
        reservations = ReservedTurnRepository(named)
    }

    @AfterAll fun cleanup() { if (this::postgres.isInitialized) postgres.stop() }

    private fun seed(id: Int) {
        fixture.seed(id)
        val initial = fixture.load(id)
        val seat = initial.generals.single { it.id == 1 }.cityId
        // This case preserves the former nation, so give it a county outside the transferred seat.
        val retainedCounty = checkNotNull(InMemoryTurnWorld(initial).administrativeCountyIds
            .filter { it != seat }.minOrNull()) { "fixture needs a retained administrative county" }
        val office = CountyAssignment("former-office", 10, 1, seat)
        jdbc.update("UPDATE city SET nation_id=1 WHERE world_id=? AND id IN (?,?)", id, seat, retainedCounty)
        jdbc.update("UPDATE nation SET capital_city_id=? WHERE world_id=? AND id=1", retainedCounty, id)
        val rulerMeta = CurrentRulerBinding.with(mapOf("gennum" to 3, "keep" to 42),
            10, "independence-fixture", CurrentRulerBinding.SCENARIO_SEED_SOURCE)
        jdbc.update("UPDATE nation SET meta=?::jsonb WHERE world_id=? AND id=1", MetaJson.encode(rulerMeta), id)
        jdbc.update("""UPDATE general SET nation_id=1,user_id=42,officer_level=3,meta=?::jsonb
            WHERE world_id=? AND id=1""", MetaJson.encode(mapOf(
            LordStatus.META_KEY to false, "keep" to "unchanged", "officer_city" to seat,
            "belong" to 10, "permission" to "normal",
            PersonPolicyState.META_KEY to PersonPolicyState(60, true, "synthetic-storage-fixture", "fixture-v1", 1).toMetaValue(),
            CountyAssignment.META_KEY to office.toMetaValue())), id)
        jdbc.update("UPDATE general SET nation_id=1 WHERE world_id=? AND id=2", id)
        jdbc.update("UPDATE general SET turn_time='0200-01-02T00:00:00Z' WHERE world_id=? AND id<>1", id)
        // Incoming parent link must be removed; the existing 1 -> 2 personal retinue must not dangle.
        jdbc.update("""INSERT INTO general_retainers
            (world_id,id,master_general_id,origin,general_id,name,relation,has_own_bugok,release_policy)
            VALUES (?,5,10,'EXISTING',1,'G1','staff',true,'MUTUAL')""", id)
        // The real personal turn supplies a hand before even a captive/delivery rejection.
        // Seed that legitimate phase metadata; keep the full political-state equality oracle below.
        val active = InMemoryTurnWorld(fixture.load(id))
        val state = active.getState()
        val hand = StratagemHand.initial(1, Phase(state.currentYear, state.currentMonth, state.currentPhase))
            .withContributions(PersonDeckProjection.forHolder(active, 1))
        jdbc.update("UPDATE general SET meta=jsonb_set(meta,ARRAY[?],?::jsonb) WHERE world_id=? AND id=1",
            StratagemHand.META_KEY, MetaJson.encode(hand.toMetaValue()), id)
    }

    private fun reserve(id: Int, request: String) {
        val inbox = CommandInboxRepository(NamedParameterJdbcTemplate(jdbc))
        assertEquals(CommandInboxRepository.InsertResult.Inserted, inbox.insertAccepted(
            CommandInboxRepository.AcceptedCommand(WorldId(id), request,
                commandKind = CommandInboxRepository.CommandKind.RESERVED_TURN, intentFingerprint = request,
                generalId = 1, turnIdx = 0, actionCode = PoliticalInput.INDEPENDENCE,
                payloadJson = "{}", ownerUserId = 42)))
        reservations.reserve(WorldId(id), 1, 0, PoliticalInput.INDEPENDENCE, "{}", requestId = request)
        val saved = reservations.readReserved(WorldId(id), 1, 0)
        assertEquals(request, saved.requestId)
        assertEquals(42, saved.reservationOwnerUserId)
    }

    private fun receipt(id: Int, request: String, type: String) {
        assertEquals(listOf(type), jdbc.queryForList(
            "SELECT result_type FROM command_result WHERE world_id=? AND request_id=?",
            String::class.java, id, request))
    }

    /** Exercise the implementation without opening the still-PLANNED product command. */
    private fun deliveredCatalog(): InputCatalog {
        val original = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val row = Regex("""("inputId":\s*"action\.independence"[\s\S]*?"deliveryState":\s*")(PLANNED|DOMAIN_READY|HANDLER_READY|UI_READY)(")""")
        assertEquals(1, row.findAll(original).count())
        return InputCatalog.parse(row.replace(original, "${'$'}1HANDLER_READY${'$'}3"))
    }

    @Test fun `reserved independence creates one ruler and removes former authority after cold reload`() {
        val id = 7451
        seed(id)
        reserve(id, "independence-once")
        val before = fixture.load(id)
        val published = mutableListOf<String>()
        val active = InMemoryTurnWorld(before)
        val outcome = fixture.service(WorldId(id), active, published, inputCatalogSource = ::deliveredCatalog)
            .runDueGeneralTurns(due).handled.single().inputOutcome
        assertIs<TurnOutcome.Applied>(outcome, "actual personal-turn outcome: $outcome")
        val after = fixture.load(id)
        val actor = after.generals.single { it.id == 1 }
        val nation = after.nations.single { it.id != 1 }
        assertEquals(nation.id, actor.nationId)
        assertEquals(1, nation.chiefGeneralId)
        assertEquals(actor.cityId, nation.capitalCityId)
        assertEquals(12, actor.officerLevel)
        assertTrue(LordStatus.read(actor.meta))
        assertEquals(nation.id, after.cities.single { it.id == actor.cityId }.nationId)
        assertEquals(setOf(1, 2), after.generals.filter { it.nationId == nation.id }.map { it.id }.toSet())
        assertEquals(10, after.nations.single { it.id == 1 }.chiefGeneralId)
        assertEquals(1, (after.nations.single { it.id == 1 }.meta["gennum"] as Number).toInt())
        assertFalse(after.retainers.any { it.generalId == 1 })
        assertEquals(before.retainers.filterNot { it.generalId == 1 }, after.retainers)
        assertEquals(before.bugoks, after.bugoks)
        assertTrue(after.retainers.all { card -> card.generalId?.let { child ->
            after.generals.single { it.id == child }.nationId ==
                after.generals.single { it.id == card.masterGeneralId }.nationId } ?: true })
        receipt(id, "independence-once", "executionApplied")
        assertEquals(listOf("independence-once"), published)
        val cold = InMemoryTurnWorld(fixture.load(id))
        assertTrue(fixture.service(WorldId(id), cold, published, inputCatalogSource = ::deliveredCatalog).runDueGeneralTurns(due).handled.isEmpty())
        val repeated = fixture.load(id)
        assertEquals(after.generals, repeated.generals)
        assertEquals(after.nations, repeated.nations)
        assertEquals(after.cities, repeated.cities)
        assertEquals(after.retainers, repeated.retainers)
        assertEquals(after.bugoks, repeated.bugoks)
        receipt(id, "independence-once", "executionApplied")
        // Former county authority must never survive allegiance changes, including cold reload.
        for (person in after.generals.filter { it.nationId == nation.id }) {
            assertFalse(CountyAssignment.META_KEY in person.meta, "former county authority retained by ${person.id}")
            assertEquals(0, (person.meta["officer_city"] as? Number)?.toInt() ?: 0)
            assertEquals(0, (person.meta["belong"] as? Number)?.toInt() ?: 0)
        }
    }

    private fun rejected(id: Int, request: String, change: () -> Unit, expected: String? = null,
        catalogSource: () -> InputCatalog = ::deliveredCatalog,
    ) {
        seed(id)
        reserve(id, request)
        change() // authority/state changes after reservation, before the canonical turn reload
        val before = fixture.load(id)
        val published = mutableListOf<String>()
        val outcome = assertIs<TurnOutcome.Rejected>(fixture.service(WorldId(id), InMemoryTurnWorld(before), published, inputCatalogSource = catalogSource)
            .runDueGeneralTurns(due).handled.single().inputOutcome)
        if (expected != null) assertEquals(expected, outcome.code)
        assertTrue(outcome.code.isNotBlank())
        val after = fixture.load(id)
        // Turn scheduling and rejection receipts advance; domain state and political authority must not.
        assertEquals(before.generals.map { it.copy(turnTime = if (it.id == 1) after.generals.single { next -> next.id == it.id }.turnTime else it.turnTime,
            initialTurns = if (it.id == 1) it.initialTurns.drop(1) else it.initialTurns,
            meta = if (it.id == 1) PersonalTurn.after(it.meta, before.state) else it.meta) }, after.generals)
        assertEquals(before.nations, after.nations)
        assertEquals(before.cities, after.cities)
        assertEquals(before.retainers, after.retainers)
        assertEquals(before.bugoks, after.bugoks)
        assertEquals(before.troops, after.troops)
        assertEquals(before.diplomacy, after.diplomacy)
        assertEquals(before.generalPositionSnapshot!!.statesByGeneralId, after.generalPositionSnapshot!!.statesByGeneralId)
        receipt(id, request, "executionRejected")
        assertEquals(listOf(request), published)
        assertTrue(fixture.service(WorldId(id), InMemoryTurnWorld(fixture.load(id)), published, inputCatalogSource = catalogSource)
            .runDueGeneralTurns(due).handled.isEmpty())
        receipt(id, request, "executionRejected")
    }

    @Test fun `production catalog still rejects independence without political mutation`() =
        rejected(7457, "independence-still-planned", {}, "NOT_DELIVERED", InputCatalog::load)

    @Test fun `ruler cannot reserve authority then execute independence`() = rejected(7452, "independence-lord", {
        jdbc.update("UPDATE general SET meta=jsonb_set(meta,'{lord}','true'),officer_level=12 WHERE world_id=7452 AND id=1")
    }, PoliticalFailure.ALREADY_LORD.name)

    @Test fun `free person cannot execute independence`() = rejected(7453, "independence-free", {
        jdbc.update("UPDATE general SET nation_id=0 WHERE world_id=7453 AND id=1")
    }, PoliticalFailure.NOT_A_SUBJECT.name)

    @Test fun `countyless land province rejects without political mutation`() = rejected(7454, "independence-no-county", {
        val projection = DomesticContext().projection(InMemoryTurnWorld(fixture.load(7454)))
        val landProvinces = assertNotNull(projection.landProvinceIds, "fixture: authoritative land provinces unavailable")
        val emptyProvince = assertNotNull(landProvinces.sorted().firstOrNull { node ->
            projection.counties.none { it.provinceId == node }
        }, "fixture: no countyless land province exists")
        jdbc.update("UPDATE general_spatial_position SET node_id=? WHERE world_id=7454 AND general_id=1", emptyProvince)
    }, PoliticalFailure.COUNTY_UNAVAILABLE.name)

    @Test fun `captive cannot change nations at personal turn`() = rejected(7455, "independence-captive", {
        val projection = DomesticContext().projection(InMemoryTurnWorld(fixture.load(7455)))
        val heldProvince = assertNotNull(projection.people.single { it.id == 1 }.node,
            "fixture: captive has no authoritative land position")
        assertTrue(assertNotNull(projection.landProvinceIds).contains(heldProvince),
            "fixture: captive position is not a known land province")
        val marker = CaptiveState(10, heldProvince, Phase(200, 1, 1), "independence-capture").toMetaValue()
        jdbc.update("UPDATE general SET meta=jsonb_set(meta,ARRAY[?],?::jsonb) WHERE world_id=7455 AND id=1",
            CaptiveState.META_KEY, MetaJson.encode(marker))
    }, "STATE_UNAVAILABLE")

    @Test fun `reservation owner change rejects before political mutation`() = rejected(7456, "independence-owner", {
        jdbc.update("UPDATE general SET user_id=43 WHERE world_id=7456 AND id=1")
    }, "FORBIDDEN")
}
