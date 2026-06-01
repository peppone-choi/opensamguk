package opensamguk.engine.config

import opensamguk.common.rng.RandUtil
import opensamguk.engine.run.MonthlyPostUpdateHook
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.WorldActions
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.tick.CheckStatistic
import opensamguk.logic.tick.MonthlyClock
import opensamguk.logic.tick.MonthlyPipeline
import opensamguk.logic.tick.MonthScopedRng
import opensamguk.logic.tick.PreUpdateMonthly
import opensamguk.logic.tick.ServerClock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import java.time.Instant

/**
 * P6 Task 6 — Spring configuration for the monthly-pipeline event infrastructure.
 *
 * Wires the [EventStore], [EventActionFactory], [EventDispatcher], [MonthlyPostUpdateHook],
 * and [MonthlyPipeline] beans so the consuming daemon can inject them into [TurnRunService].
 */
@Configuration
class EngineEventConfig {

    @Bean
    fun eventStore(): EventStore = EventStore.withDefaults()

    @Bean
    fun eventActionFactory(): EventActionFactory =
        WorldActions.register(EventActionFactory())

    @Bean
    fun eventDispatcher(store: EventStore, factory: EventActionFactory): EventDispatcher =
        EventDispatcher(store, factory)

    @Bean
    fun generalActionPipeline(): GeneralActionPipeline =
        GeneralActionPipeline()

    @Bean
    @Lazy
    fun monthlyPostUpdateHook(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        pipeline: GeneralActionPipeline,
    ): MonthlyPostUpdateHook = MonthlyPostUpdateHook(world, recorder, pipeline)

    @Bean
    @Lazy
    fun monthlyPipeline(
        hook: MonthlyPostUpdateHook,
        world: InMemoryTurnWorld,
    ): MonthlyPipeline<RandUtil> {
        val hiddenSeed = world.getState().meta["hiddenSeed"] as? String ?: ""
        val startYear = (world.getState().meta["startYear"] as? Number)?.toInt() ?: 0
        val startTime = Instant.parse(
            world.getState().meta["startTime"] as? String ?: Instant.now().toString()
        )
        val turnTerm = world.getState().tickSeconds / 60
        return MonthlyPipeline(
            monthlyRngFactory = { year, month ->
                MonthScopedRng.forMonth(hiddenSeed, year, month)
            },
            clock = MonthlyClock { nextTurn, st ->
                ServerClock.turnDate(nextTurn, startYear, st, turnTerm)
            },
            preUpdateMonthly = PreUpdateMonthly { true },
            checkStatistic = CheckStatistic { },
            postUpdateMonthly = hook,
        )
    }
}
