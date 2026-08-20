package opensamguk.engine.intake

import opensamguk.common.wire.AcceptDiplomaticMessageOk
import opensamguk.common.wire.DeclineDiplomaticMessageOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.engine.config.DaemonLoopConfig
import opensamguk.engine.flush.DatabaseHooks
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.ProcessNationCommand
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.infra.read.ContactReader
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.nation.NationActionResolverRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.diplomacy.DiplomacyConst
import opensamguk.logic.diplomacy.DiplomacyState
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline
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
import kotlin.test.assertNotNull
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

    @Test
    fun `reserved cancellation proposal flushes through accept and enables declaration in the same world`() {
        NationActionResolverRegistry.clear()
        try {
            installDaemonResolvers()
            val worldId = 32_032
            seedDiplomacyLifecycleRows(worldId)
            val world = nonAggressionWorld(worldId)
            val recorder = ChangeRecorder()
            val registry = CommandRegistry(GeneralActionPipeline())
            val nationProcessor = ProcessNationCommand(
                world = world,
                recorder = recorder,
                hiddenSeed = "diplomacy-lifecycle-seed",
                registry = registry,
                startYear = 184,
            )
            val lifecycle = TurnDaemonLifecycle(
                world = world,
                handler = ReservedTurnHandler(
                    world = world,
                    registry = registry,
                    hiddenSeed = "diplomacy-lifecycle-seed",
                    startYear = 184,
                    recorder = recorder,
                ),
                nationProcessor = nationProcessor,
                reservedNationActionOf = { _, _ ->
                    ReservedTurn("che_불가침파기제의", """{"destNationID":2}""")
                },
                reservedActionOf = { ReservedTurn("휴식", "") },
            )

            lifecycle.runTick(DIPLOMACY_LIFECYCLE_TIME.plusSeconds(1))

            val receiverFirst = recorder.createdMessages()
            assertEquals(2, receiverFirst.size)
            assertEquals(9_002, receiverFirst.first().mailbox)
            assertEquals("diplomacy", receiverFirst.first().type)
            assertEquals(9_001, receiverFirst[1].mailbox)
            val receiverMessageId = receiverFirst.first().id
            flushExecutor.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))

            val reader = ContactReader(jdbc)
            val persistedMessage = assertNotNull(reader.findMessage(WorldId(worldId), receiverMessageId))
            assertEquals("cancel_na", persistedMessage.option["action"])
            assertEquals(false, persistedMessage.option["deletable"])
            assertEquals("#0000ff", assertNotNull(persistedMessage.destArray)["color"])
            assertEquals(2, persistedMessage.destNationId)
            assertEquals(1, persistedMessage.srcNationId)
            recorder.clear()

            val diplomaticHandler = DiplomaticMessageHandler(
                world = world,
                recorder = recorder,
                processNationCommand = nationProcessor,
                messageReader = { id -> reader.findMessage(world.worldId, id)?.toSnapshot() },
            )
            val accepted = diplomaticHandler.handleAccept(
                TurnDaemonCommand.AcceptDiplomaticMessage(messageId = receiverMessageId, generalId = 20),
            )

            assertIs<AcceptDiplomaticMessageOk>(accepted)
            assertEquals(
                listOf(2 to 1, 1 to 2),
                recorder.diplomacyUpdateDirty().map { it.fromNationId to it.toNationId },
            )
            assertTrue(recorder.diplomacyUpdateDirty().all { it.state == DiplomacyState.TRADE && it.term == 0 })
            flushExecutor.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
            assertEquals(DiplomacyState.TRADE to 0, diplomacyState(worldId, 1, 2))
            assertEquals(DiplomacyState.TRADE to 0, diplomacyState(worldId, 2, 1))
            recorder.clear()

            nationProcessor.process(
                generalId = 20,
                officerLevel = 12,
                nationCommand = ChosenCommand("che_선전포고", linkedMapOf("destNationID" to 1)),
                lastTurn = LastTurn(),
                year = 200,
                month = 3,
                date = "12:00",
            )

            assertEquals(DiplomacyState.DECLARATION, world.getDiplomacy(1, 2)?.state)
            assertEquals(DiplomacyState.DECLARATION, world.getDiplomacy(2, 1)?.state)
            flushExecutor.flush(DatabaseHooks.toFlushPayload(world, recorder, world.consumeDirtyState()))
            assertEquals(DiplomacyState.DECLARATION to DiplomacyConst.DEFAULT_DECLARE_WAR_TERM, diplomacyState(worldId, 1, 2))
            assertEquals(DiplomacyState.DECLARATION to DiplomacyConst.DEFAULT_DECLARE_WAR_TERM, diplomacyState(worldId, 2, 1))
        } finally {
            NationActionResolverRegistry.clear()
        }
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

    private fun diplomacyState(worldId: Int, fromNationId: Int, toNationId: Int): Pair<Int, Int> = jdbc.query(
        """
        SELECT state_code, term
          FROM diplomacy
         WHERE world_id = :world_id
           AND src_nation_id = :src_nation_id
           AND dest_nation_id = :dest_nation_id
        """.trimIndent(),
        MapSqlParameterSource()
            .addValue("world_id", worldId)
            .addValue("src_nation_id", fromNationId)
            .addValue("dest_nation_id", toNationId),
    ) { rs, _ -> rs.getInt("state_code") to rs.getInt("term") }.single()

    private fun seedDiplomacyLifecycleRows(worldId: Int) {
        jdbc.update(
            """
            INSERT INTO world_state (id, scenario_code, current_year, current_month, tick_seconds, config)
            VALUES (:world_id, 'diplomacy-lifecycle', 200, 3, 3600, '{"startYear":184}'::jsonb)
            """.trimIndent(),
            MapSqlParameterSource().addValue("world_id", worldId),
        )
        jdbc.update(
            """
            INSERT INTO nation (world_id, id, name, color, capital_city_id, level, type_code)
            VALUES
              (:world_id, 1, '촉', '#00ff00', 1, 2, 'None'),
              (:world_id, 2, '위', '#0000ff', 9, 2, 'None')
            """.trimIndent(),
            MapSqlParameterSource().addValue("world_id", worldId),
        )
        jdbc.update(
            """
            INSERT INTO city
                (world_id, id, name, level, nation_id, supply_state, front_state, pop, pop_max,
                 agri, agri_max, comm, comm_max, secu, secu_max, def, def_max, wall, wall_max, region)
            VALUES
              (:world_id, 1, '업', 6, 1, 1, 0, 100, 1000, 100, 1000, 100, 1000, 100, 1000, 100, 1000, 100, 1000, 1),
              (:world_id, 9, '남피', 6, 2, 1, 0, 100, 1000, 100, 1000, 100, 1000, 100, 1000, 100, 1000, 100, 1000, 1)
            """.trimIndent(),
            MapSqlParameterSource().addValue("world_id", worldId),
        )
        jdbc.update(
            """
            INSERT INTO general
                (world_id, id, name, nation_id, city_id, troop_id, leadership, strength, intel,
                 experience, dedication, officer_level, gold, rice, turn_time, meta)
            VALUES
              (:world_id, 10, '유비', 1, 1, 0, 80, 70, 60, 0, 0, 12, 1000, 1000, :proposer_turn_time,
               '{"killturn":80}'::jsonb),
              (:world_id, 20, '조조', 2, 9, 0, 80, 70, 60, 0, 0, 12, 1000, 1000, :receiver_turn_time,
               '{"killturn":80}'::jsonb)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId)
                .addValue("proposer_turn_time", java.sql.Timestamp.from(DIPLOMACY_LIFECYCLE_TIME))
                .addValue("receiver_turn_time", java.sql.Timestamp.from(DIPLOMACY_LIFECYCLE_TIME.plusSeconds(3600))),
        )
        jdbc.update(
            """
            INSERT INTO diplomacy (world_id, id, src_nation_id, dest_nation_id, state_code, term)
            VALUES
              (:world_id, 1, 1, 2, :state, 9),
              (:world_id, 2, 2, 1, :state, 9)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId)
                .addValue("state", DiplomacyState.NON_AGGRESSION),
        )
    }

    private fun nonAggressionWorld(worldId: Int): InMemoryTurnWorld = InMemoryTurnWorld(
        WorldSnapshot(
            state = TurnWorldState(
                id = worldId,
                currentYear = 200,
                currentMonth = 3,
                tickSeconds = 3600,
                lastTurnTime = DIPLOMACY_LIFECYCLE_TIME,
                config = linkedMapOf("mapName" to "che"),
            ),
            generals = listOf(
                TurnGeneral(
                    id = 10,
                    name = "유비",
                    nationId = 1,
                    cityId = 1,
                    troopId = 0,
                    stats = GeneralStats(80, 70, 60),
                    experience = 0,
                    dedication = 0,
                    officerLevel = 12,
                    gold = 1000,
                    rice = 1000,
                    turnTime = DIPLOMACY_LIFECYCLE_TIME,
                    meta = linkedMapOf("killturn" to 80),
                ),
                TurnGeneral(
                    id = 20,
                    name = "조조",
                    nationId = 2,
                    cityId = 9,
                    troopId = 0,
                    stats = GeneralStats(80, 70, 60),
                    experience = 0,
                    dedication = 0,
                    officerLevel = 12,
                    gold = 1000,
                    rice = 1000,
                    turnTime = DIPLOMACY_LIFECYCLE_TIME.plusSeconds(3600),
                    meta = linkedMapOf("killturn" to 80),
                ),
            ),
            cities = listOf(
                City(id = 1, name = "업", nationId = 1, level = 6, supplyState = 1),
                City(id = 9, name = "남피", nationId = 2, level = 6, supplyState = 1),
            ),
            nations = listOf(
                Nation(id = 1, name = "촉", color = "#00ff00", capitalCityId = 1, level = 2),
                Nation(id = 2, name = "위", color = "#0000ff", capitalCityId = 9, level = 2),
            ),
            diplomacy = listOf(
                TurnDiplomacy(1, 2, DiplomacyState.NON_AGGRESSION, 9),
                TurnDiplomacy(2, 1, DiplomacyState.NON_AGGRESSION, 9),
            ),
            worldId = WorldId(worldId),
        ),
    )

    private fun installDaemonResolvers() {
        val method = DaemonLoopConfig::class.java.getDeclaredMethod(
            "installNationActionResolvers",
            GeneralActionPipeline::class.java,
        )
        method.isAccessible = true
        method.invoke(DaemonLoopConfig(), GeneralActionPipeline())
    }

    private fun worldOne(): InMemoryTurnWorld {
        val now = Instant.parse("0200-03-01T12:00:00Z")
        return InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(
                    1, 200, 3, 3600, now,
                    config = linkedMapOf("mapName" to "che"),
                ),
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

    private companion object {
        val DIPLOMACY_LIFECYCLE_TIME: Instant = Instant.parse("2026-07-30T00:00:00Z")
    }
}
