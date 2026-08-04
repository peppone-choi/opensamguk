package opensamguk.engine.config

import opensamguk.engine.boot.SeedBootstrap
import opensamguk.engine.boot.WorldSnapshotLoader
import opensamguk.engine.turn.InMemoryTurnWorld
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.jdbc.core.JdbcTemplate

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
    fun seedBootstrap(
        @Value("\${SCENARIO_CODE:scenario_1010}") scenarioCode: String,
        @Value("\${SCENARIO_SEED_ENABLED:true}") seedEnabled: Boolean,
        @Value("\${SCENARIO_DIR:}") scenarioDir: String,
        @Value("\${SCENARIO_QA_TURNTERM:}") qaTurnTerm: String,
        // 어드민/워크플로 리셋이 고른 턴 주기. deployer가 servers/*.env에 RESET_TURNTERM으로
        // 쓰는 이름을 그대로 읽어 중간 번역 계층을 두지 않는다. 이 값이 게임 엔진까지 오려면
        // docker-compose.server.yml의 game-engine environment에도 전달돼야 한다(별도 저장소).
        @Value("\${RESET_TURNTERM:}") resetTurnTerm: String,
        processWorld: EngineProcessWorld,
    ): SeedBootstrap = SeedBootstrap(
        scenarioCode = scenarioCode,
        seedEnabled = seedEnabled,
        scenarioDir = scenarioDir,
        qaTurnTerm = qaTurnTerm,
        resetTurnTerm = resetTurnTerm,
        worldId = processWorld.worldId,
    )

    @Bean
    fun worldSnapshotLoader(
        jdbc: JdbcTemplate,
        seedBootstrap: SeedBootstrap,
        processWorld: EngineProcessWorld,
    ): WorldSnapshotLoader = WorldSnapshotLoader(jdbc, seedBootstrap, processWorld.worldId)

    @Bean
    @Lazy
    fun inMemoryTurnWorld(loader: WorldSnapshotLoader): InMemoryTurnWorld =
        InMemoryTurnWorld(loader.buildSnapshot())
}
