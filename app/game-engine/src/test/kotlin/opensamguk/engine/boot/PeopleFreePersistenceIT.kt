package opensamguk.engine.boot

import kotlin.test.*
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.engine.campaign.DomesticContext
import opensamguk.engine.campaign.PeopleHandler
import opensamguk.engine.campaign.TurnOutcome
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.logic.input.*
import opensamguk.logic.renown.RenownRules
import opensamguk.logic.world.StrategicNodeRef
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.Assumptions
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Normal unaffiliated people actions through the real flush and cold-load boundary. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PeopleFreePersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: unaffiliated people action database roundtrip NOT verified")
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
        val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        Flyway.configure().dataSource(source).locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false")).load().migrate()
        jdbc = JdbcTemplate(source)
        flush = JdbcFlushExecutor(NamedParameterJdbcTemplate(source),
            TransactionTemplate(DataSourceTransactionManager(source)))
        fixture = EnlistmentFixture(jdbc, flush)
    }

    @AfterAll fun teardown() { if (this::postgres.isInitialized) postgres.stop() }

    @Test fun `unaffiliated discovery and retinue survive separate cold reloads without double charge`() {
        val id = 1691
        fixture.seed(id)
        val initial = InMemoryTurnWorld(fixture.load(id))
        val countyId = initial.administrativeCountyIds.sorted().first { initial.landNodeOfCity(it) != null }
        val province = initial.landNodeOfCity(countyId) as StrategicNodeRef.LandProvince
        jdbc.update("UPDATE general SET user_id=42,city_id=?,meta=jsonb_set(meta,'{lord}','false'::jsonb) WHERE world_id=? AND id=1",
            countyId, id)
        jdbc.update("UPDATE general SET city_id=? WHERE world_id=? AND id=2", countyId, id)
        jdbc.update("UPDATE general SET nation_id=0,officer_level=0,city_id=?,meta=jsonb_set(meta,'{lord}','false'::jsonb) WHERE world_id=? AND id=10",
            countyId, id)
        jdbc.update("UPDATE general_spatial_position SET node_id=? WHERE world_id=? AND general_id IN (1,2,10)",
            province.id, id)
        jdbc.update("UPDATE general_bugok SET commander_retainer_id=NULL WHERE world_id=? AND commander_retainer_id=4", id)
        jdbc.update("DELETE FROM general_retainers WHERE world_id=? AND id=4", id)
        jdbc.update("""INSERT INTO general_retainers(world_id,id,master_general_id,origin,general_id,name,relation,has_own_bugok,release_policy)
            VALUES (?,5,2,'EXISTING',10,'G10','guest',true,'MUTUAL')""", id)

        val design = PeopleDesign.CANON.copy(status = PeopleDesign.CONFIRMED)
        var world = InMemoryTurnWorld(fixture.load(id))
        var recorder = ChangeRecorder()
        val search = PeopleHandler(world, recorder, DomesticContext(), "test", design) {
            error("only one unbound local person needs no random draw")
        }
        val discovered = assertIs<TurnOutcome.Applied>(search.handle(PeopleInput.SEARCH, 1,
            "{}", "search-1691", 42))
        assertTrue(discovered.effects.contains("discoveredGeneralId:2"))
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))

        world = InMemoryTurnWorld(fixture.load(id))
        assertEquals(setOf(2), TalentDiscovery.read(world.getGeneralById(1)!!.meta))
        jdbc.update("UPDATE general SET turn_time='0200-01-01T01:00:00Z' WHERE world_id=? AND id=1", id)
        world = InMemoryTurnWorld(fixture.load(id))
        recorder = ChangeRecorder()
        val employ = PeopleHandler(world, recorder, DomesticContext(), "test", design) { seed ->
            object : RandUtil(LiteHashDrbg(seed)) {
                override fun nextInt(minInclusive: Int, maxExclusive: Int) = minInclusive
            }
        }
        val args = """{"targetGeneralId":2}"""
        val accepted = assertIs<TurnOutcome.Applied>(employ.handle(PeopleInput.EMPLOY, 1,
            args, "employ-1691", 42))
        assertTrue(accepted.effects.contains("joinedGeneralId:2"))
        assertEquals(accepted, employ.handle(PeopleInput.EMPLOY, 1, args, "employ-1691", 42))
        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))

        val after = fixture.load(id)
        assertEquals(setOf(2), TalentDiscovery.read(after.generals.single { it.id == 1 }.meta))
        assertEquals(0, after.generals.single { it.id == 2 }.nationId)
        assertEquals(0, after.generals.single { it.id == 10 }.nationId)
        assertEquals(1, after.retainers.count { it.masterGeneralId == 1 && it.generalId == 2 })
        assertEquals(1, after.retainers.count { it.masterGeneralId == 2 && it.generalId == 10 })
        assertEquals(2, after.retainers.size)
        val owner = after.generals.single { it.id == 1 }
        val target = after.generals.single { it.id == 2 }
        val budget = assertIs<RenownBudgetResult.Ready>(EnlistmentBudget.assess(2, RuleProfile.HWIHA,
            after.generals.map { person -> PersonPolicyInput(person.id, person.nationId,
                person.stats.leadership, person.stats.strength, person.stats.intelligence,
                person.stats.politics, person.stats.charm, person.meta) },
            after.retainers.map { card -> DirectPersonCard(card.id, card.masterGeneralId, card.generalId) }))
        val targetCost = RenownRules.personCost(target.stats.leadership, target.stats.strength,
            target.stats.intelligence, target.stats.politics, target.stats.charm)
        assertEquals(PersonPolicyState.read(owner.meta)!!.renownCapacity - targetCost,
            budget.freeRenownByOwner[owner.id])
    }
}
