package opensamguk.engine.config

import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.WorldActions
import opensamguk.logic.stats.GeneralActionPipeline
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * P6 Task 6 — Spring configuration for the monthly-pipeline EVENT infrastructure.
 *
 * Wires the [EventStore], [EventActionFactory], [EventDispatcher], and [GeneralActionPipeline] singletons.
 *
 * The [opensamguk.logic.tick.MonthlyPipeline] itself is **NOT** a bean here: it is per-run state whose
 * PostUpdate hook writes through the handler's per-run [opensamguk.engine.turn.ChangeRecorder] (the lone
 * dirty source), so it is built inline in [DaemonLoopConfig.turnRunService] (which owns that recorder).
 * Exposing it as a `@Lazy @Bean` previously forced a Spring CGLIB class proxy with a null-field shell +
 * an unsatisfiable `ChangeRecorder` dependency, freezing the turn-daemon clock on the first month boundary.
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
}
