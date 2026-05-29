package opensamguk.logic.event

import kotlinx.serialization.json.JsonElement

/**
 * FE2 — the sealed dynamic-event Action base + the F2-owned [EventActionFactory].
 *
 * Faithful port of PHP `sammo/Event/Action.php`. An Action carries side effects only; PHP's `run`
 * returns a value that the dispatcher DISCARDS (`runEventHandler` ignores the result, plan FE3), so
 * the Kotlin `run` is `Unit`.
 *
 * F2 OWNS the sealed base, the [EventActionContext] contract, and the [EventActionFactory] registry.
 * Each Tier-1 family (A1..A6) defines its OWN concrete leaf in `logic/world/<Action>.kt` and the B5
 * light Actions live alongside in this package; every leaf REGISTERS itself into the factory by name
 * (the lone per-family touch — NO family edits the base or the EventStore row seed, plan §append
 * protocol). The base is an `interface` rather than a `sealed interface` precisely so the family leaf
 * files (separate modules of work) can implement it without co-editing this file.
 */
interface EventAction {
    /** Run the Action's side effects against the dispatch context. Result is discarded (PHP parity). */
    fun run(ctx: EventActionContext)
}

/**
 * The context an [EventAction] receives at dispatch (PHP `Action::run(array $env)` widened).
 *
 * F2 owns the BASE contract: the lazily-built [env] map (the same `currentEventID`-injected env the
 * dispatcher hands each row) + [currentEventID] convenience. Families that need the world snapshot,
 * the active CityConst, the self-seeded RNG path, or the `ActionLogger(0,0,year,month)` globalLogger
 * supply a RICHER context that implements this interface — keeping F2 free of not-yet-built
 * (A1..A6 / F6 / F7) types while still owning the dispatch seam.
 */
interface EventActionContext {
    /** The dispatch env (PHP `$env`), with `currentEventID` already injected by the dispatcher. */
    val env: Map<String, Any?>

    /** Convenience accessor for `$env['currentEventID']` (DeleteEvent reads it; FE3). */
    val currentEventID: Int
        get() = (env["currentEventID"] as? Number)?.toInt() ?: 0
}

/**
 * A decoded-but-not-yet-instantiated action (PHP `Action::build`'s `[$className, ...$args]` split):
 * the class [name] = `args[0]` and [args] = `array_slice(args, 1)` passed VERBATIM (non-recursive).
 * The [EventActionFactory] turns it into a concrete [EventAction].
 */
data class RawAction(val name: String, val args: List<JsonElement>)

/**
 * The F2-owned `name → leaf` factory (PHP `Action::build`'s `class_exists` + `newInstanceArgs`).
 *
 * Families register their concrete leaf builder by name; [create] resolves a [RawAction]. Unknown
 * names throw (PHP `Action.php:18-20` throws "존재하지 않는 Action입니다"). Registration is the ONLY
 * per-family touch-point on F2-owned code (plan §append protocol).
 */
class EventActionFactory {
    private val builders = LinkedHashMap<String, (List<JsonElement>) -> EventAction>()

    /** Register a leaf builder by its PHP class name (e.g. "ProcessIncome"). */
    fun register(name: String, builder: (List<JsonElement>) -> EventAction): EventActionFactory {
        builders[name] = builder
        return this
    }

    /** True if [name] has a registered builder. */
    fun has(name: String): Boolean = name in builders

    /** Instantiate the [RawAction] via its registered builder; throws if unregistered (PHP parity). */
    fun create(raw: RawAction): EventAction {
        val builder = builders[raw.name]
            ?: throw IllegalArgumentException("존재하지 않는 Action입니다 :${raw.name}")
        return builder(raw.args)
    }
}
