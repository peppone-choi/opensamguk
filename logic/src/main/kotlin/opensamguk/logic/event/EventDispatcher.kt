package opensamguk.logic.event

/**
 * FE3 — the dynamic-event dispatcher (PHP `runEventHandler`, TurnExecutionHelper.php:370-391).
 *
 * For a given [EventTarget], runs every event row in the EXACT and ONLY ordering `priority DESC,
 * id ASC` ([EventStore.rowsFor]). The materialized row set is FROZEN at the start of [run]: a
 * `DeleteEvent` tombstone or a self-insert mutates the [EventStore] but NOT the in-flight set (the
 * snapshot list is taken before the loop), so a deleted row that already fired is not re-run and a
 * spawned row does not fire in the same dispatch.
 *
 * The env is lazy-loaded on the FIRST row (PHP `$e_env === null` → `gameStor->getAll(false)`); if
 * there are no rows, the env is never built. Per row the dispatcher injects
 * `env['currentEventID'] = id`, evaluates the [EventCondition] — a falsy verdict SKIPS all of that
 * row's actions (PHP `EventHandler::tryRunEvent` early-returns) — else instantiates each action via
 * the [EventActionFactory] and runs it in array order. The PHP return is DISCARDED (observable
 * side-effects come ONLY from the Action bodies; G1 does not byte-match the chain, plan FE3).
 */
class EventDispatcher(
    private val store: EventStore,
    private val factory: EventActionFactory,
) {

    /**
     * Run all [target] events. [envSupplier] is the lazy `gameStor->getAll(false)` loader — called at
     * most ONCE per dispatch (only if there is at least one row). It must return a fresh MUTABLE map
     * (the dispatcher overwrites `currentEventID` in place per row, exactly as PHP mutates `$e_env`).
     */
    /**
     * [contextFactory] wraps the live mutable [env] into the [EventActionContext] each action receives.
     * The DEFAULT ([Context]) is a bare env-only context — correct for the light/logic-test path. The
     * engine MUST pass a richer factory (`WorldActionContext`) for the world-event leaves
     * (UpdateCitySupply/ProcessIncome/… `ctx as? XContext`, plus the env-read leaves that need
     * `env[specialityWorld]`/`env[disasterWorld]`/… threaded) — otherwise the monthly settlement
     * crashes (cast leaves) or silently no-ops (env-read leaves). The ctx is built ONCE per dispatch and
     * wraps the same mutable env the loop mutates (`currentEventID` in place), so per-row rebuild is moot.
     */
    fun run(
        target: EventTarget,
        // envSupplier보다 앞 — envSupplier가 마지막이라야 기존 `run(target) { env }` trailing-lambda 호출이
        // (contextFactory가 아니라) envSupplier에 바인딩된다. 새 엔진 경로는 named arg로 둘 다 넘긴다.
        contextFactory: (MutableMap<String, Any?>) -> EventActionContext = { Context(it) },
        envSupplier: () -> MutableMap<String, Any?>,
    ) {
        // FROZEN row set — materialized BEFORE any action can mutate the store.
        val frozen = store.rowsFor(target)
        if (frozen.isEmpty()) return

        val env = envSupplier()
        val ctx = contextFactory(env)

        for (row in frozen) {
            env["currentEventID"] = row.id
            if (!row.condition.eval(env)) continue // falsy → skip ALL actions of this row
            for (rawAction in row.actions) {
                factory.create(rawAction).run(ctx) // result discarded
            }
        }
    }

    /** The per-dispatch [EventActionContext] backing the env the dispatcher mutates in place. */
    private class Context(override val env: Map<String, Any?>) : EventActionContext
}
