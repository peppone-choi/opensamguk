package opensamguk.engine.boot

import opensamguk.common.wire.CreateGeneral

import opensamguk.common.wire.CreationCustomChoice
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.TurnDaemonEvent
import opensamguk.common.wire.TurnDaemonEventEnvelope
import opensamguk.common.wire.CreateGeneralResult
import opensamguk.common.wire.WireJson
import opensamguk.common.wire.encodeCommandPayload
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.intake.CreationHandler
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.logic.creation.CreationNameRule
import opensamguk.logic.creation.CreationRequestFingerprint
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The daemon orders accepted commands; V71 also prevents duplicate committed CUSTOM name keys. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreationNamePersistenceIT {
    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: JdbcTemplate
    private lateinit var flush: JdbcFlushExecutor
    private lateinit var fixture: EnlistmentFixture

    @BeforeAll fun setup() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable,
            "Docker unavailable: creation name flush/cold reload NOT verified")
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

    private fun command(account: Int, county: Int, name: String, world: Int = 191,
        uuid: String = "92d9244b-6eb5-4f89-971d-d1b1247e0ff6") = CreateGeneral(
        accountId = account, worldId = world, clientRequestId = uuid,
        choiceKind = "CUSTOM", custom = CreationCustomChoice(name, county,
            60, 60, 60, 60, 60, "WANGDO", "DISCIPLINE", role = "RETAINER"),
    )

    private fun seedCreationWorld(id: Int) {
        fixture.seed(id)
        jdbc.update("UPDATE world_state SET config = config || jsonb_build_object('maxgeneral', 50) WHERE id = ?", id)
    }

    @Test fun `same-name accepted commands yield one created general and cold reload keeps the key`() {
        seedCreationWorld(191)
        val world = InMemoryTurnWorld(fixture.load(191))
        val county = world.administrativeCountyIds.first { world.landNodeOfCity(it) != null }
        val recorder = ChangeRecorder()
        val handler = CreationHandler(world, recorder)
        val first = handler.handle(command(77, county, "張·遼"))
        val second = handler.handle(command(78, county, "張·遼"))
        assertEquals(true, first.ok)
        assertEquals("NAME_ALREADY_USED", second.errorCode)
        val id = assertNotNull(first.generalId)
        assertEquals(CreationNameRule.APPROVED.uniqueKey("張·遼"),
            world.getGeneralById(id)?.meta?.get("creationNameKeyV1"))

        flush.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
        val cold = InMemoryTurnWorld(fixture.load(191))
        assertEquals(1, cold.listGenerals().count { it.name == "張·遼" && it.userId in setOf("77", "78") })
        assertEquals(CreationNameRule.APPROVED.uniqueKey("張·遼"),
            cold.getGeneralById(id)?.meta?.get("creationNameKeyV1"))
        assertEquals("NAME_ALREADY_USED", CreationHandler(cold, ChangeRecorder())
            .handle(command(79, county, "張·遼")).errorCode)

        // A second writer bypassing the actor still cannot commit a duplicate new-name key.
        assertFailsWith<DuplicateKeyException> {
            jdbc.update("""
                INSERT INTO general(world_id,id,name,city_id,turn_time,meta)
                SELECT world_id,9999,name,city_id,turn_time,meta
                  FROM general WHERE world_id=191 AND id=?
            """.trimIndent(), id)
        }
        assertTrue(jdbc.queryForObject(
            "SELECT count(*) FROM general WHERE world_id=191 AND meta ->> 'creationNameKeyV1' = ?",
            Int::class.java, CreationNameRule.APPROVED.uniqueKey("張·遼")) == 1)
    }

    @Test fun `two durable same-name requests produce one CREATED and one REJECTED terminal result`() {
        seedCreationWorld(192)
        val world = InMemoryTurnWorld(fixture.load(192))
        val county = world.administrativeCountyIds.first { world.landNodeOfCity(it) != null }
        val worldId = WorldId(192)
        val inbox = CommandInboxRepository(NamedParameterJdbcTemplate(jdbc))
        val resultStore = CommandResultRepository(NamedParameterJdbcTemplate(jdbc))
        val requests = listOf(
            77 to "92d9244b-6eb5-4f89-971d-d1b1247e0ff6",
            78 to "713deefa-245e-48cd-bd6f-7f6d4eb96a62",
        ).map { (account, uuid) ->
            val internalId = CreationRequestFingerprint.commandRequestId(account.toLong(), 192, uuid)
            val envelope = TurnDaemonCommandEnvelope(internalId, "2026-10-01T00:00:00Z",
                command(account, county, "同名", 192, uuid))
            assertEquals(CommandInboxRepository.InsertResult.Inserted, inbox.insertAccepted(
                CommandInboxRepository.AcceptedCommand(worldId, internalId,
                    commandKind = CommandInboxRepository.CommandKind.IMMEDIATE,
                    intentFingerprint = "name-race-$account", generalId = null, turnIdx = 0,
                    actionCode = "createGeneral", payloadJson = encodeCommandPayload(envelope),
                    ownerUserId = account)))
            internalId
        }
        val published = mutableListOf<String>()
        assertEquals(2, fixture.service(worldId, world, published, intake = true).runIntakeCommands())
        assertEquals(1, InMemoryTurnWorld(fixture.load(192)).listGenerals().count { it.name == "同名" })
        val terminal = requests.map { requestId ->
            val payload = assertNotNull(resultStore.findResultPayload(worldId, requestId))
            val envelope = WireJson.decodeFromString(TurnDaemonEventEnvelope.serializer(), payload)
            assertEquals(requestId, envelope.requestId)
            assertNotNull(envelope.committedWorldVersion)
            (envelope.event as TurnDaemonEvent.CommandResult).result as CreateGeneralResult
        }
        assertEquals(listOf(true, false), terminal.map { it.ok })
        assertEquals("NAME_ALREADY_USED", terminal[1].errorCode)
    }
}
