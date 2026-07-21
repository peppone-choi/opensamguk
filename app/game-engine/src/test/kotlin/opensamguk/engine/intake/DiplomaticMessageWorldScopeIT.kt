package opensamguk.engine.intake

import opensamguk.common.wire.DeclineDiplomaticMessageOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.read.ContactReader
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import java.time.Instant
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DiplomaticMessageWorldScopeIT {

    private lateinit var postgres: PostgreSQLContainer<*>
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var flushExecutor: JdbcFlushExecutor

    @BeforeAll
    fun setUp() {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        org.junit.jupiter.api.Assumptions.assumeTrue(
            runCatching { org.testcontainers.DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker unavailable — Testcontainers IT skipped (not failed)",
        )
        postgres.start()
        val dataSource: DataSource = DriverManagerDataSource().apply {
            setDriverClassName("org.postgresql.Driver")
            url = postgres.jdbcUrl
            username = postgres.username
            password = postgres.password
        }
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/migration")
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
            .migrate()
        jdbc = NamedParameterJdbcTemplate(dataSource)
        flushExecutor = JdbcFlushExecutor(jdbc, TransactionTemplate(DataSourceTransactionManager(dataSource)))
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds)
            VALUES (1, 'scope-one', 200, 3, 3600), (2, 'scope-two', 200, 3, 3600)
            """.trimIndent(),
            MapSqlParameterSource(),
        )
    }

    @AfterAll
    fun tearDown() {
        if (this::postgres.isInitialized) postgres.stop()
    }

    @Test
    fun `decline reads handles and flushes only the selected world when local ids collide`() {
        insertMessage(worldId = 1, text = "world-one")
        insertMessage(worldId = 2, text = "world-two")
        val world = worldOne()
        val recorder = ChangeRecorder()
        val reader = ContactReader(jdbc)
        val handler = DiplomaticMessageHandler(
            world = world,
            recorder = recorder,
            processNationCommand = null,
            messageReader = { id -> reader.findMessage(world.worldId, id)?.toSnapshot() },
        )
        val worldOneBefore = rowBytes(1)
        val worldTwoBefore = rowBytes(2)

        val result = handler.handleDecline(
            TurnDaemonCommand.DeclineDiplomaticMessage(messageId = 77, generalId = 10),
        )
        val payload = DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState())
        flushExecutor.flush(payload)

        assertIs<DeclineDiplomaticMessageOk>(result)
        val worldOneAfter = rowBytes(1)
        val worldTwoAfter = rowBytes(2)
        assertNotEquals(worldOneBefore, worldOneAfter)
        assertEquals(worldTwoBefore, worldTwoAfter)
        assertTrue(validUntilEquals(1, "2000-12-31 00:00:00"))
        assertTrue(validUntilEquals(2, "9999-12-31 00:00:00+00"))
        assertEquals(true, optionFlag(1, "used"))
        assertEquals(true, optionFlag(1, "invalid"))
        assertEquals(null, optionFlag(2, "used"))
        assertEquals(null, optionFlag(2, "invalid"))
    }

    private fun insertMessage(worldId: Int, text: String) {
        jdbc.update(
            """
            INSERT INTO message (world_id, id, mailbox, type, src, dest, time, valid_until, message)
            VALUES (
                :world_id, 77, 9001, CAST('diplomacy' AS message_type), 9002, 9001,
                CAST('0200-03-01 11:59:00+00' AS timestamptz),
                CAST('9999-12-31 00:00:00+00' AS timestamptz),
                CAST(:body AS jsonb)
            )
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId)
                .addValue(
                    "body",
                    """{"src":{"id":20,"name":"조조","nation_id":2},"dest":{"id":0,"name":"","nation_id":1},"text":"$text","option":{"action":"stop_war"}}""",
                ),
        )
    }

    private fun rowBytes(worldId: Int): String = jdbc.queryForObject(
        """
        SELECT message::text || '|' || extract(epoch FROM valid_until)::text
          FROM message
         WHERE world_id = :world_id AND id = 77
        """.trimIndent(),
        MapSqlParameterSource().addValue("world_id", worldId),
        String::class.java,
    )!!

    private fun validUntilEquals(worldId: Int, expected: String): Boolean = jdbc.queryForObject(
        """
        SELECT valid_until = CAST(:expected AS timestamptz)
          FROM message
         WHERE world_id = :world_id AND id = 77
        """.trimIndent(),
        MapSqlParameterSource().addValue("world_id", worldId).addValue("expected", expected),
        Boolean::class.java,
    )!!

    private fun optionFlag(worldId: Int, key: String): Boolean? = jdbc.queryForObject(
        """
        SELECT CAST(message -> 'option' ->> :key AS boolean)
          FROM message
         WHERE world_id = :world_id AND id = 77
        """.trimIndent(),
        MapSqlParameterSource().addValue("world_id", worldId).addValue("key", key),
        Boolean::class.java,
    )

    private fun worldOne(): InMemoryTurnWorld {
        val now = Instant.parse("0200-03-01T12:00:00Z")
        return InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 3, 3600, now),
                generals = listOf(
                    TurnGeneral(
                        id = 10,
                        name = "유비",
                        nationId = 1,
                        cityId = 5,
                        troopId = 0,
                        stats = GeneralStats(80, 70, 60),
                        experience = 0,
                        dedication = 0,
                        officerLevel = 12,
                        turnTime = now,
                    ),
                    TurnGeneral(
                        id = 20,
                        name = "조조",
                        nationId = 2,
                        cityId = 8,
                        troopId = 0,
                        stats = GeneralStats(80, 70, 60),
                        experience = 0,
                        dedication = 0,
                        officerLevel = 12,
                        turnTime = now,
                    ),
                ),
                nations = listOf(
                    Nation(id = 1, name = "촉", color = "#0f0"),
                    Nation(id = 2, name = "위", color = "#00f"),
                ),
                worldId = WorldId(1),
            ),
        )
    }

    private fun opensamguk.infra.read.MessageReadRow.toSnapshot() = MessageSnapshot(
        id = id,
        mailbox = mailbox,
        hasAction = hasAction,
        type = type,
        srcGeneralId = srcGeneralId,
        srcNationId = srcNationId,
        destGeneralId = destGeneralId,
        destNationId = destNationId,
        time = time,
        validUntil = validUntil,
        deletable = deletable,
        receiverMessageId = receiverMessageId,
        text = text,
        srcArray = srcArray,
        destArray = destArray,
        option = option,
    )
}
