package opensamguk.engine.intake

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.GeneralAccessLog
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertIs
import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.wire.WireJson
import opensamguk.engine.campaign.TurnOutcome
import opensamguk.engine.turn.City
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.gameapi.admin.AdminGeneralModerationService
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.controller.AdminWriteController
import opensamguk.gameapi.dto.AdminGeneralModerationActionRequest
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.PrecheckBeans
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.security.GameApiJwtVerifier
import opensamguk.infra.persistence.CommandInboxRepository
import opensamguk.infra.persistence.CommandResultRepository
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.input.PersonalDesign
import opensamguk.logic.world.GeneralPositionSnapshot
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.StrategicNodeRef
import org.mockito.Mockito
import org.springframework.data.redis.core.StreamOperations
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionOperations

class AdminGeneralModerationHandlerTest {
    private val now = Instant.parse("0200-01-01T00:00:00Z")

    @Test fun `HWIHA API block2 is rejected before publication`() = assertApiBoundary("block2")
    @Test fun `HWIHA API block3 is rejected before publication`() = assertApiBoundary("block3")
    @Test fun `HWIHA API forceDeath is rejected before reserving a legacy turn`() = assertApiBoundary("forceDeath")

    @Test fun `HWIHA queued block2 is rejected without mutation`() = assertQueuedBoundary("block2")
    @Test fun `HWIHA queued block3 is rejected without mutation`() = assertQueuedBoundary("block3")
    @Test fun `HWIHA queued forceDeath is rejected without mutation`() = assertQueuedBoundary("forceDeath")

    private fun moderationWorld(): InMemoryTurnWorld {
        val people = listOf(general(1).copy(userId = "7", turnTime = now.plusSeconds(7200)),
            general(2).copy(userId = "77"))
        val hash = "a".repeat(64)
        val positions = people.fold(GeneralPositionSnapshot("fixture", hash, setOf("p1"), emptySet())) { snapshot, person ->
            snapshot.withState(GeneralPositionState("fixture", hash, person.id, StrategicNodeRef.LandProvince("p1"), 1))
        }
        return InMemoryTurnWorld(WorldSnapshot(
            state = TurnWorldState(1, 200, 1, 3600, now,
                config = mapOf("ruleProfile" to "HWIHA", "mapName" to "han-world-v3")),
            generals = people, cities = listOf(City(1, "검증현", 1, level = 5)),
            worldId = opensamguk.common.world.WorldId(1),
            generalPositionSnapshot = positions, cityLandProvinceById = mapOf(1 to "p1"),
        ))
    }

    private fun assertQueuedBoundary(action: String) {
        val world = moderationWorld()
        val before = world.listGenerals().toList()
        val recorder = ChangeRecorder()
        val result = AdminGeneralModerationHandler(world, recorder).handle(
            TurnDaemonCommand.AdminGeneralModeration(actorGeneralId = 1, generalIds = listOf(2), action = action))
        assertFalse(result.ok, "HWIHA must reject unconsumed sanction $action")
        assertEquals(before, world.listGenerals())
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
        assertTrue(recorder.accessLogUpserts().isEmpty())
    }

    /** Actual controller, moderation service and reservation policy; only persistence/Redis are fixtures. */
    private fun assertApiBoundary(action: String) {
        val world = moderationWorld()
        val before = world.listGenerals().toList()
        val recorder = ChangeRecorder()
        val wiring = PrecheckBeans()
        val registry = wiring.commandRegistry(wiring.generalActionPipeline())
        val worlds = Mockito.mock(WorldStateReadRepository::class.java)
        Mockito.`when`(worlds.findProcessWorld()).thenReturn(WorldStateReadEntity(config = world.getState().config))
        val inbox = ModerationInbox()
        val reservedTurns = Mockito.mock(ReservedTurnRepository::class.java)
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST")
        val streams = Mockito.mock(StreamOperations::class.java) as StreamOperations<String, Any, Any>
        Mockito.`when`(redis.opsForStream<Any, Any>()).thenReturn(streams)
        val commands = CommandReserveService(reservedTurns, inbox,
            Mockito.mock(CommandResultRepository::class.java), redis, registry,
            GameApiProcessWorld(1), "fixture", transactions = ModerationTransactions, worldStates = worlds)
        val verifier = Mockito.mock(GameApiJwtVerifier::class.java)
        Mockito.`when`(verifier.isValid("admin-fixture")).thenReturn(true)
        Mockito.`when`(verifier.getRole("admin-fixture")).thenReturn("ADMIN")
        Mockito.`when`(verifier.getUserId("admin-fixture")).thenReturn(7L)
        val resolver = Mockito.mock(GeneralResolver::class.java)
        Mockito.`when`(resolver.resolveGeneralId(7L)).thenReturn(1)
        val controller = AdminWriteController(verifier, commands, resolver, AdminGeneralModerationService(commands, worlds))
        val response = runCatching { controller.generalModerationAction("Bearer admin-fixture",
            AdminGeneralModerationActionRequest(action, listOf(2))) }

        // When the unsafe API accepts, observe the real published command and its next HWIHA turn.
        if (response.getOrNull()?.statusCode == HttpStatus.ACCEPTED) {
            val queued = WireJson.decodeFromString(TurnDaemonCommandEnvelope.serializer(),
                inbox.accepted.single().payloadJson).command
            val mutation = AdminGeneralModerationHandler(world, recorder).handle(
                assertIs<TurnDaemonCommand.AdminGeneralModeration>(queued))
            assertTrue(mutation.ok)
            val handler = ReservedTurnHandler(world, registry,
                hiddenSeed = "fixture", startYear = 200, recorder = recorder)
            val turn = TurnDaemonLifecycle(world, handler, reservedActionOf = {
                ReservedTurnRepository.ReservedTurn("action.selfTrain", "{\"stat\":\"strength\"}",
                    requestId = "moderation-training", reservationOwnerUserId = 77)
            }).runTick(now.plusSeconds(3600)).single()
            assertIs<TurnOutcome.Applied>(turn.inputOutcome)
            assertEquals(50 + PersonalDesign.CANON.trainingStatGain, world.getGeneralById(2)!!.stats.strength)
            assertEquals(24, world.getGeneralById(2)!!.meta["killturn"])
        }

        assertEquals(HttpStatus.BAD_REQUEST, response.getOrNull()?.statusCode,
            "$action must reject before writes; observed ${response.exceptionOrNull()?.javaClass?.simpleName ?: response.getOrNull()?.statusCode}")
        assertEquals(AdminGeneralModerationService.UNSUPPORTED_TURN_REASON,
            (response.getOrNull()?.body as? Map<*, *>)?.get("reason"))
        assertTrue(inbox.accepted.isEmpty())
        Mockito.verifyNoInteractions(reservedTurns)
        assertEquals(before, world.listGenerals())
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    @Test
    fun `supported current world moderation actions remain executable`() {
        for (action in listOf("unblock", "block1", "infiniteKillturn", "dex1", "allowAccess", "denyAccess")) {
            val world = moderationWorld()
            assertTrue(AdminGeneralModerationHandler(world, ChangeRecorder()).handle(
                TurnDaemonCommand.AdminGeneralModeration(actorGeneralId = 1, generalIds = listOf(2), action = action)).ok,
                action)
        }
    }

    private class ModerationInbox : CommandInboxRepository(Mockito.mock(NamedParameterJdbcTemplate::class.java)) {
        val accepted = mutableListOf<AcceptedCommand>()
        override fun insertAccepted(command: AcceptedCommand): InsertResult {
            accepted += command
            return InsertResult.Inserted
        }
        override fun markRedisWakePublished(worldId: opensamguk.common.world.WorldId, requestId: String, publishedAt: Instant) = Unit
    }

    private object ModerationTransactions : TransactionOperations {
        override fun <T : Any?> execute(action: TransactionCallback<T>): T? = action.doInTransaction(SimpleTransactionStatus())
        override fun executeWithoutResult(action: java.util.function.Consumer<TransactionStatus>) = action.accept(SimpleTransactionStatus())
    }

    @Test
    fun `access actions update only access-log score through recorder`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 1, 3600, now),
                generals = listOf(general(1), general(2)),
                accessLogs = listOf(GeneralAccessLog(2, 77, now, refreshScore = 12, refreshScoreTotal = 99)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(1, 200, 1, 3600, now)).id),
            ),
        )
        val recorder = ChangeRecorder()
        val handler = AdminGeneralModerationHandler(world, recorder)

        val denied = handler.handle(
            TurnDaemonCommand.AdminGeneralModeration(actorGeneralId = 1, generalIds = listOf(2), action = "denyAccess"),
        )
        assertTrue(denied.ok)
        assertEquals(1000, world.getAccessLog(2)!!.refreshScore)
        assertEquals(99, world.getAccessLog(2)!!.refreshScoreTotal)

        val allowed = handler.handle(
            TurnDaemonCommand.AdminGeneralModeration(actorGeneralId = 1, generalIds = listOf(2), action = "allowAccess"),
        )
        assertTrue(allowed.ok)
        assertEquals(0, world.getAccessLog(2)!!.refreshScore)
        assertEquals(0, recorder.accessLogUpserts().single().refreshScore)
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    @Test
    fun `block and dex actions mutate through recorder`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 1, 3600, now),
                generals = listOf(general(1), general(2)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(1, 200, 1, 3600, now)).id),
            ),
        )
        val recorder = ChangeRecorder()
        val handler = AdminGeneralModerationHandler(world, recorder)

        val blocked = handler.handle(
            TurnDaemonCommand.AdminGeneralModeration(
                actorGeneralId = 1,
                generalIds = listOf(2),
                action = "block2",
            ),
        )
        assertTrue(blocked.ok)
        assertEquals(0, world.getGeneralById(2)!!.gold)
        assertEquals(0, world.getGeneralById(2)!!.rice)
        assertEquals(2, world.getGeneralById(2)!!.meta["block"])
        assertEquals(24, world.getGeneralById(2)!!.meta["killturn"])

        handler.handle(
            TurnDaemonCommand.AdminGeneralModeration(
                actorGeneralId = 1,
                generalIds = listOf(2),
                action = "dex3",
            ),
        )
        assertEquals(10_000, world.getGeneralById(2)!!.meta["dex3"])
        assertTrue(2 in recorder.dirtyGeneralIds())
    }

    @Test
    fun `unsupported action fails without mutation`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 1, 3600, now),
                generals = listOf(general(1)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(1, 200, 1, 3600, now)).id),
            ),
        )
        val recorder = ChangeRecorder()
        val result = AdminGeneralModerationHandler(world, recorder).handle(
            TurnDaemonCommand.AdminGeneralModeration(
                actorGeneralId = 1,
                generalIds = listOf(1),
                action = "unknown",
            ),
        )
        assertFalse(result.ok)
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    @Test
    fun `missing target fails before any target is mutated`() {
        val world = InMemoryTurnWorld(
            WorldSnapshot(
                state = TurnWorldState(1, 200, 1, 3600, now),
                generals = listOf(general(1), general(2)),
                worldId = opensamguk.common.world.WorldId((TurnWorldState(1, 200, 1, 3600, now)).id),
            ),
        )
        val recorder = ChangeRecorder()
        val result = AdminGeneralModerationHandler(world, recorder).handle(
            TurnDaemonCommand.AdminGeneralModeration(
                actorGeneralId = 1,
                generalIds = listOf(2, 999),
                action = "block1",
            ),
        )

        assertFalse(result.ok)
        assertEquals(500, world.getGeneralById(2)!!.gold)
        assertTrue(recorder.dirtyGeneralIds().isEmpty())
    }

    private fun general(id: Int): TurnGeneral = TurnGeneral(
        id = id,
        name = "g$id",
        nationId = 1,
        cityId = 1,
        troopId = 0,
        stats = GeneralStats(50, 50, 50),
        experience = 0,
        dedication = 0,
        officerLevel = 1,
        gold = 500,
        rice = 500,
        turnTime = now,
    )
}
