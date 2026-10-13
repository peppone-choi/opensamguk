package opensamguk.engine.campaign

import kotlin.test.*
import opensamguk.common.world.WorldId
import opensamguk.engine.boot.EnlistmentFixture
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.*
import opensamguk.infra.persistence.*
import opensamguk.logic.input.*
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

/** Storage boundary only; catalog activation and same-drain cancellation remain explicitly withheld. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DissolvePersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture
    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: dissolve PostgreSQL cold reload NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source), TransactionTemplate(DataSourceTransactionManager(source)))
        fixture = EnlistmentFixture(jdbc, flush)
    }
    @AfterAll fun cleanup() { if (this::postgres.isInitialized) postgres.stop() }
    private fun catalog(): InputCatalog {
        val original = checkNotNull(javaClass.classLoader.getResource("command-catalog/input-catalog.json")).readText()
        val row = Regex("""("inputId":\s*"action\.dissolve"[\s\S]*?"deliveryState":\s*")(PLANNED|DOMAIN_READY|HANDLER_READY|UI_READY)(")""")
        return InputCatalog.parse(row.replace(original, "${'$'}1HANDLER_READY${'$'}3"))
    }

    @Test fun `extinction and remaining reservation cancellation survive a real flush and cold reload`() {
        val id = 7481
        fixture.seed(id)
        fixture.seed(7482)
        val otherWorld = fixture.load(7482)
        jdbc.update("UPDATE nation SET level=0,capital_city_id=NULL WHERE world_id=? AND id=1", id)
        jdbc.update("INSERT INTO nation(world_id,id,name,color) VALUES (?,2,'untouched','#222222')", id)
        jdbc.update("INSERT INTO diplomacy(world_id,src_nation_id,dest_nation_id,state_code,term) VALUES (?,1,2,2,0),(?,2,1,2,0)", id, id)
        jdbc.update("UPDATE general SET nation_id=1,gold=5000,rice=6000 WHERE world_id=? AND id IN (1,2)", id)
        jdbc.update("UPDATE general SET nation_id=2 WHERE world_id=? AND id=10", id)
        jdbc.update("UPDATE general SET user_id=42,officer_level=12,troop_id=1,meta=jsonb_set(meta,'{belong}','9') WHERE world_id=? AND id=1", id)
        jdbc.update("UPDATE general SET troop_id=1 WHERE world_id=? AND id=2", id)
        jdbc.update("INSERT INTO troop(world_id,troop_leader,nation,name) VALUES (?,1,1,'former military')", id)
        val reservations = ReservedTurnRepository(NamedParameterJdbcTemplate(jdbc))
        reservations.reserve(WorldId(id), 1, 1, PoliticalInput.DISSOLVE, "{}", requestId = "future-ruler")
        reservations.reserve(WorldId(id), 2, 0, PoliticalInput.RESIGN, "{}", requestId = "future-member")
        val before = fixture.load(id)
        val world = InMemoryTurnWorld(before)
        val recorder = ChangeRecorder()
        assertIs<TurnOutcome.Applied>(PoliticalHandler(world, recorder, DomesticContext(), catalog())
            .handle(PoliticalInput.DISSOLVE, 1, "{}", "dissolve-storage", 42))
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        val after = fixture.load(id)
        assertEquals(before.nations.filter { it.id != 1 }, after.nations)
        assertEquals(before.cities, after.cities)
        assertEquals(before.generals.single { it.id == 10 }, after.generals.single { it.id == 10 })
        assertTrue(after.diplomacy.isEmpty())
        assertTrue(after.troops.isEmpty())
        assertTrue(after.retainers.isEmpty())
        assertEquals(before.bugoks.map { it.copy(commanderRetainerId = null) }, after.bugoks)
        for (person in after.generals.filter { it.id in setOf(1, 2) }) {
            assertEquals(0, person.nationId)
            assertEquals(0, person.officerLevel)
            assertEquals(0, person.troopId)
            assertEquals("normal", person.meta["permission"])
            assertFalse(LordStatus.read(person.meta))
            assertEquals(1000, person.gold)
        }
        assertEquals(1000, after.generals.single { it.id == 1 }.rice)
        assertEquals(6000, after.generals.single { it.id == 2 }.rice)
        assertEquals(9, ((after.generals.single { it.id == 1 }.meta["aux"] as Map<*, *>)["max_belong"] as Number).toInt())
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM ng_old_nations WHERE world_id=? AND id=1", Int::class.java, id))
        for ((actor, slot) in listOf(1 to 1, 2 to 0)) {
            val reservation = reservations.readReserved(WorldId(id), actor, slot)
            assertEquals("휴식", reservation.actionCode)
            assertNull(reservation.requestId)
        }
        val cold = InMemoryTurnWorld(fixture.load(id))
        val repeated = ChangeRecorder()
        assertIs<TurnOutcome.Applied>(PoliticalHandler(cold, repeated, DomesticContext(), catalog())
            .handle(PoliticalInput.DISSOLVE, 1, "{}", "dissolve-storage", 42))
        assertTrue(repeated.deletedNationIds().isEmpty())
        assertTrue(repeated.generalTurnSlotWrites().isEmpty())
        assertEquals(otherWorld, fixture.load(7482))
    }
}
