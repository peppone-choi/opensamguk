package opensamguk.engine.boot

import opensamguk.common.wire.TurnDaemonCommandEnvelope
import opensamguk.common.world.WorldId
import opensamguk.engine.redis.RealtimePublisher
import opensamguk.engine.redis.RedisCommandStream
import opensamguk.engine.run.TurnRunService
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.ReservedTurnHandler
import opensamguk.engine.turn.TurnDaemonLifecycle
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.ReservedTurnRepository
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.stats.GeneralActionPipeline
import org.mockito.Mockito.mock
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Instant
import kotlin.test.assertEquals

internal data class FullRehydrateFixtureConfig(
    val generalId: Int,
    val cityId: Int,
    val nationId: Int,
    val secondNationId: Int,
    val hiddenSeed: String,
    val startYear: Int,
    val start: Instant,
)

internal class FullRehydrateFixtureFactory(
    private val jdbc: JdbcTemplate,
    private val namedJdbc: NamedParameterJdbcTemplate,
    private val executor: JdbcFlushExecutor,
    private val config: FullRehydrateFixtureConfig,
) {
    fun create(worldId: WorldId): FullRehydrateFixture {
        val world = InMemoryTurnWorld(loader(worldId).buildSnapshot())
        val loadedState = world.getState()
        val hiddenSeed = loadedState.meta["hiddenSeed"] as? String
            ?: error("world ${worldId.value} must load hiddenSeed from world_state.meta")
        val startYear = (loadedState.meta["startYear"] as? Number)?.toInt()
            ?: error("world ${worldId.value} must load startYear from world_state.meta")
        assertEquals(config.hiddenSeed, hiddenSeed, "fixture precondition: loader-provided hidden seed")
        assertEquals(config.startYear, startYear, "fixture precondition: loader-provided start year")
        assertEquals(config.start.toString(), loadedState.meta["startTime"], "fixture precondition: loader-provided start time")
        assertEquals(loadedState.currentYear, (loadedState.meta["currentYear"] as? Number)?.toInt())
        assertEquals(loadedState.currentMonth, (loadedState.meta["currentMonth"] as? Number)?.toInt())
        assertEquals(loadedState.currentPhase, (loadedState.meta["currentPhase"] as? Number)?.toInt())
        assertEquals(loadedState.lastTurnTime.toString(), loadedState.meta["lastTurnTime"])
        val handler = ReservedTurnHandler(
            world = world,
            registry = CommandRegistry(GeneralActionPipeline()),
            hiddenSeed = hiddenSeed,
            startYear = startYear,
        )
        val reservedTurns = ReservedTurnRepository(namedJdbc)
        val redis = mock(StringRedisTemplate::class.java)
        val lifecycle = TurnDaemonLifecycle(
            world = world,
            handler = handler,
            pullGeneralTurnOf = { id -> handler.recorder.recordGeneralTurnPull(id) },
            reservedActionOf = { id -> reservedTurns.readReserved(worldId, id, 0) },
        )
        val commandStream = object : RedisCommandStream(redis, "rehydrate-${worldId.value}", worldId) {
            override fun readEnvelopes(blockMs: Long): List<TurnDaemonCommandEnvelope> = emptyList()
        }
        return FullRehydrateFixture(
            world = world,
            service = TurnRunService(
                world = world,
                commandStream = commandStream,
                lifecycle = lifecycle,
                handler = handler,
                flushExecutor = executor,
                realtimePublisher = mock(RealtimePublisher::class.java),
                commandBlockMs = 1,
            ),
        )
    }

    private fun loader(worldId: WorldId): WorldSnapshotLoader = WorldSnapshotLoader(
        jdbc = jdbc,
        seedBootstrap = SeedBootstrap(
            scenarioCode = "scenario_0",
            seedEnabled = false,
            worldId = worldId,
        ),
        worldId = worldId,
        snapshotValidator = {},
    )
}

internal data class FullRehydrateFixture(
    val world: InMemoryTurnWorld,
    val service: TurnRunService,
)
