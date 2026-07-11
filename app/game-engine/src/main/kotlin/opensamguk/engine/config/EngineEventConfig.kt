package opensamguk.engine.config

import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventDispatcher
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.WorldActions
import opensamguk.logic.stats.GeneralActionPipeline
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
    fun eventStore(jdbc: JdbcTemplate, bootstrap: opensamguk.engine.boot.SeedBootstrap): EventStore {
        bootstrap.ensureSeeded(jdbc)
        val ignoreDefaults = jdbc.query(
            "SELECT COALESCE((config->>'ignoreDefaultEvents')::boolean, false) FROM world_state ORDER BY id ASC LIMIT 1",
        ) { rs, _ -> rs.getBoolean(1) }.firstOrNull() ?: false
        val rows = jdbc.query(
            "SELECT id, target_code, priority, condition::text AS condition_json, action::text AS action_json FROM event ORDER BY id ASC",
        ) { rs, _ ->
            PersistedEvent(
                id = rs.getInt("id"),
                target = rs.getString("target_code"),
                priority = rs.getInt("priority"),
                condition = rs.getString("condition_json"),
                action = rs.getString("action_json"),
            )
        }
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
