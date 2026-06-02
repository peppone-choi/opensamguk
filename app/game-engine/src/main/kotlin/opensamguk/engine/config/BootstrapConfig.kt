package opensamguk.engine.config

import opensamguk.engine.boot.WorldSnapshotLoader
import opensamguk.engine.turn.InMemoryTurnWorld
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * F1b — the missing boot wiring: the `@Bean InMemoryTurnWorld` that every consumer
 * (`EngineEventConfig.monthlyPostUpdateHook` / `monthlyPipeline`, `TurnRunService`,
 * `ReservedTurnHandler`, `AiTurnAdapter`, the auction/betting handlers, …) injects but which did NOT
 * exist in production — only tests hand-built a `WorldSnapshot`. This bean closes that gap by loading
 * the snapshot from the seeded DB at construction time.
 *
 * Kept in a SEPARATE `@Configuration` from `EngineEventConfig` (the event/pipeline wiring) so the
 * world-construction concern is isolated.
 *
 * **Seed→load ordering (guaranteed).** [WorldSnapshotLoader.buildSnapshot] calls
 * `SeedBootstrap.ensureSeeded(...)` (idempotent) BEFORE it reads any row, so the world is always
 * seeded before it is materialized — independent of bean-init order. The boot-time
 * `ScenarioSeedRunner` (an `ApplicationRunner`) additionally seeds + logs counts at startup; because
 * the seed is idempotent (gated on `world_state` emptiness), whichever runs first wins and the other
 * is a no-op. `EngineEventConfig`'s `@Lazy` world-injected beans now resolve against THIS bean.
 */
@Configuration
class BootstrapConfig {

    @Bean
    fun inMemoryTurnWorld(loader: WorldSnapshotLoader): InMemoryTurnWorld =
        InMemoryTurnWorld(loader.buildSnapshot())
}
