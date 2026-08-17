package opensamguk.engine.config

import opensamguk.common.world.WorldId
import opensamguk.engine.v2.V2WorldActions
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.WorldActions
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.world.RaiseInvaderAction
import kotlinx.serialization.json.Json
import org.springframework.jdbc.core.JdbcTemplate
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
    fun eventStore(
        jdbc: JdbcTemplate,
        bootstrap: opensamguk.engine.boot.SeedBootstrap,
        processWorld: EngineProcessWorld,
    ): EventStore = createEventStore(jdbc, bootstrap, processWorld.worldId)

    internal fun createEventStore(
        jdbc: JdbcTemplate,
        bootstrap: opensamguk.engine.boot.SeedBootstrap,
        worldId: WorldId,
    ): EventStore {
        bootstrap.ensureSeeded(jdbc)
        val ignoreDefaults = jdbc.query(
            "SELECT COALESCE((config->>'ignoreDefaultEvents')::boolean, false) FROM world_state WHERE id = ?",
            { rs, _ -> rs.getBoolean(1) },
            worldId.value,
        ).firstOrNull() ?: false
        val rows = jdbc.query(
            "SELECT id, target_code, priority, condition::text AS condition_json, action::text AS action_json FROM event WHERE world_id = ? ORDER BY id ASC",
            { rs, _ ->
                PersistedEvent(
                    id = rs.getInt("id"),
                    target = rs.getString("target_code"),
                    priority = rs.getInt("priority"),
                    condition = rs.getString("condition_json"),
                    action = rs.getString("action_json"),
                )
            },
            worldId.value,
        )
        if (rows.isEmpty()) return EventStore.withDefaults(ignoreDefaults)
        return EventStore().also { store ->
            rows.forEach { row ->
                store.loadRaw(
                    row.id,
                    row.target,
                    row.priority,
                    Json.parseToJsonElement(row.condition),
                    Json.parseToJsonElement(row.action),
                )
            }
        }
    }

    private data class PersistedEvent(
        val id: Int,
        val target: String,
        val priority: Int,
        val condition: String,
        val action: String,
    )

    /**
     * OPENSAM-151 — v2 leaf도 같은 팩토리에 이름으로 등록한다([V2WorldActions]). 등록은 이름 추가일 뿐,
     * v2 leaf는 시나리오 `event` 행이 그 이름을 부르지 않는 한 절대 돌지 않는다. v1 시나리오에는 그 이름을
     * 쓰는 행이 없으므로 v1 월드 동작은 불변이다.
     */
    @Bean
    fun eventActionFactory(): EventActionFactory =
        RaiseInvaderAction.register(V2WorldActions.register(WorldActions.register(EventActionFactory())))

    @Bean
    fun eventDispatcher(store: EventStore, factory: EventActionFactory): EventDispatcher =
        EventDispatcher(store, factory)

    @Bean
    fun generalActionPipeline(): GeneralActionPipeline =
        GeneralActionPipeline()
}
