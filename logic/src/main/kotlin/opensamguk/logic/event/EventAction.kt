package opensamguk.logic.event

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

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

// ──────────────────────────────────────────────────────────────────────────────
// B5 / Task LT1 — the genuinely-light Month/PreMonth Action leaves.
//
// Port targets (PHP frozen historical baseline (ADR-LITE-042; not current product authority), sammo/Event/Action/*.php): NewYear / ResetOfficerLock /
// NoticeToHistoryLog / AddGlobalBetray. Each is a bulk world mutation + (some) history-log push with
// NO RNG and NO order-sensitive per-row iteration, so they bind to a small [LightActionWorld] view the
// tick supplies under `env[LightActionWorld.ENV_KEY]` (the richer-context seam the A-family established
// for RaiseDisaster/AssignGeneralSpeciality). Absent view → no-op (NoticeToHistoryLog still validates
// the year/month presence first, matching the PHP throw which runs before any DB touch).
//
// These are B5-owned leaves; F2 owns the sealed base + the EventStore row seed that NAMES them at their
// slots (NewYear month==1 after RandomizeCityTradeRate; ResetOfficerLock month 1/4/7/10; the two
// AddGlobalBetray in the DateRelative 4/1 row). The lone per-family touch on F2-owned code is the
// by-name [LightActions.register] into the [EventActionFactory] (plan §append protocol).
//
// MergeInheritPointRank is NOT a light Action — it is Task LT3 (P6-inheritance bound).
// ──────────────────────────────────────────────────────────────────────────────

/**
 * The world seam the B5 light Actions mutate (supplied by the tick's richer context). Kept minimal +
 * B5-owned so the leaves stay inside their file boundary while the daemon-side world adapter (F1/G1
 * wiring) implements it. Each method is a direct port of a PHP `$db->update(...)` / `logger->push*`.
 */
interface LightActionWorld {
    /** PHP `NewYear.php:23-25` `UPDATE general SET age = age+1` (ALL generals). */
    fun incrementAllGeneralAge()

    /** PHP `NewYear.php:27-29` `UPDATE general SET belong = belong+1 WHERE nation != 0`. */
    fun incrementBelongWhereNationNonZero()

    /** PHP `ResetOfficerLock.php:14-16` `UPDATE nation SET chief_set = 0` (ALL nations). */
    fun clearAllNationChiefSet()

    /** PHP `ResetOfficerLock.php:18-20` `UPDATE city SET officer_set = 0` (ALL cities). */
    fun clearAllCityOfficerSet()

    /** PHP `AddGlobalBetray.php:16-19` `UPDATE general SET betray = betray + cnt WHERE betray <= ifMax`. */
    fun addGlobalBetray(cnt: Int, ifMax: Int)

    /** PHP `ActionLogger::pushGlobalActionLog` (NewYear's `<C>{year}</>년이 되었습니다.`). */
    fun pushGlobalActionLog(msg: String)

    /** PHP `ActionLogger::pushGlobalHistoryLog` (NoticeToHistoryLog's notice). */
    fun pushGlobalHistoryLog(msg: String, type: Int)

    /** PHP `ActionLogger::pushGeneralHistoryLog` (NewYear's 매너 notice line). */
    fun pushGeneralHistoryLog(msg: String, type: Int)

    companion object {
        /** The env key the tick threads the world view under (mirrors the A-family ENV_WORLD convention). */
        const val ENV_KEY = "lightActionWorld"

        /** PHP `ActionLogger` log-type constants the B5 leaves use (ActionLogger.php:27/35/39). */
        const val YEAR_MONTH = 2
        const val EVENT_YEAR_MONTH = 6
        const val NOTICE_YEAR_MONTH = 8

        /** Resolve a NoticeToHistoryLog `type` arg, which the F2 seed carries as a self-describing token. */
        fun resolveLogType(token: String): Int = when (token) {
            "YEAR_MONTH" -> YEAR_MONTH
            "EVENT_YEAR_MONTH" -> EVENT_YEAR_MONTH
            "NOTICE_YEAR_MONTH" -> NOTICE_YEAR_MONTH
            else -> token.toIntOrNull() ?: YEAR_MONTH
        }
    }
}

private fun EventActionContext.lightWorld(): LightActionWorld? =
    env[LightActionWorld.ENV_KEY] as? LightActionWorld

/**
 * `NewYear` (PHP `NewYear.php:10-30`): +1 age (ALL generals), +1 belong (호봉, WHERE nation != 0), and two
 * logs — the `<C>{year}</>년이 되었습니다.` global ACTION log + the 매너 notice general-history line
 * (`NOTICE_YEAR_MONTH`). The PHP order is: build logger → push logs → flush → DB updates; we apply the
 * same observable side-effects (logs then field bumps). Fires at month==1 (after RandomizeCityTradeRate).
 */
class NewYearAction : EventAction {
    override fun run(ctx: EventActionContext) {
        val world = ctx.lightWorld() ?: return
        val year = (ctx.env["year"] as? Number)?.toInt() ?: return
        // NewYear.php:16-17 — logs first (PHP builds an ActionLogger(0,0,year,month) and flushes).
        world.pushGlobalActionLog("<C>$year</>년이 되었습니다.")
        world.pushGeneralHistoryLog(NEW_YEAR_NOTICE, LightActionWorld.NOTICE_YEAR_MONTH)
        // NewYear.php:23-29 — age+1 (all), belong+1 (nation != 0).
        world.incrementAllGeneralAge()
        world.incrementBelongWhereNationNonZero()
    }

    companion object {
        const val NAME = "NewYear"

        /** The byte-exact 매너 notice line (NewYear.php:17). */
        const val NEW_YEAR_NOTICE =
            "<S>모두들 즐거운 게임 하고 계신가요? ^^ <Y>매너 있는 플레이</> 부탁드리고, " +
                "게임보단 <L>건강이 먼저</>란점, 잊지 마세요!</>"
    }
}

/**
 * `ResetOfficerLock` (PHP `ResetOfficerLock.php:9-22`): clears the 천도(chief_set) + 관직변경(officer_set)
 * locks — `UPDATE nation SET chief_set = 0` (all) + `UPDATE city SET officer_set = 0` (all). Fires at
 * month 1/4/7/10.
 */
class ResetOfficerLockAction : EventAction {
    override fun run(ctx: EventActionContext) {
        val world = ctx.lightWorld() ?: return
        world.clearAllNationChiefSet()
        world.clearAllCityOfficerSet()
    }

    companion object { const val NAME = "ResetOfficerLock" }
}

/**
 * `NoticeToHistoryLog(msg, type)` (PHP `NoticeToHistoryLog.php:8-23`): push [msg] to the GLOBAL history
 * log with [type] (default `YEAR_MONTH`). PHP throws when NEITHER year NOR month is in the env (the guard
 * is `!key_exists('year') && !key_exists('month')`, an AND — one present is enough); the throw runs
 * BEFORE the log push, so it is validated before the world view is required.
 */
class NoticeToHistoryLogAction(val msg: String, val type: Int = LightActionWorld.YEAR_MONTH) : EventAction {
    override fun run(ctx: EventActionContext) {
        check(ctx.env.containsKey("year") || ctx.env.containsKey("month")) { "year, month가 없음" }
        val world = ctx.lightWorld() ?: return
        world.pushGlobalHistoryLog(msg, type)
    }

    companion object { const val NAME = "NoticeToHistoryLog" }
}

/**
 * `AddGlobalBetray(cnt, ifMax)` (PHP `AddGlobalBetray.php:7-20`): `UPDATE general SET betray = betray +
 * cnt WHERE betray <= ifMax`. PHP constructor defaults `cnt=1, ifMax=0`.
 */
class AddGlobalBetrayAction(val cnt: Int = 1, val ifMax: Int = 0) : EventAction {
    override fun run(ctx: EventActionContext) {
        val world = ctx.lightWorld() ?: return
        world.addGlobalBetray(cnt, ifMax)
    }

    companion object { const val NAME = "AddGlobalBetray" }
}

// ──────────────────────────────────────────────────────────────────────────────
// B5 / Task LT2 — DeleteEvent (tombstone-once, the F3 seam).
//
// Port target = PHP `Event/Action/DeleteEvent.php:10-20` — `DELETE FROM event WHERE id = currentEventID`,
// the "1회용 event" self-delete. PHP throws RuntimeException when currentEventID is falsy. The F2
// dispatcher FREEZES the row set before running ([EventDispatcher]), so the deleted row still ran this
// dispatch but is gone next — there is no double-apply within a dispatch. DeleteEvent binds to the
// in-memory [EventStore] via the [DeleteEventContext] seam (the tick/dispatcher threads it under
// `env[DeleteEventContext.ENV_KEY]`); B5-owned — the F2 dispatcher is NOT widened.
// ──────────────────────────────────────────────────────────────────────────────

/** The seam DeleteEvent uses to tombstone its own row in the [EventStore]. */
object DeleteEventContext {
    /** The env key the tick/dispatcher threads the live [EventStore] under (for the self-delete). */
    const val ENV_KEY = "eventStore"
}

/**
 * `DeleteEvent` (PHP `DeleteEvent.php:10-20`): tombstone the row whose id == `currentEventID` on the
 * [EventStore] reached via `env[DeleteEventContext.ENV_KEY]`. PHP throws when currentEventID is unset
 * (0/null falsy); we mirror that with `check(currentEventID != 0)`. [EventStore.delete] is idempotent
 * (map remove), so a stray re-run does not corrupt the store. The founding-limit notices pair a
 * NoticeToHistoryLog with this leaf so they self-delete on schedule (Scenario.php:560-597).
 */
class DeleteEventAction : EventAction {
    override fun run(ctx: EventActionContext) {
        val eventID = ctx.currentEventID
        check(eventID != 0) { "currentEventID가 지정되지 않았습니다." }
        val store = ctx.env[DeleteEventContext.ENV_KEY] as? EventStore ?: return
        store.delete(eventID)
    }

    companion object {
        const val NAME = "DeleteEvent"

        /** Register the `DeleteEvent` leaf into the F2-owned factory by name (plan §append protocol). */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { DeleteEventAction() }
    }
}

/**
 * B5 / LT1 — by-name registration of the light Action leaves into the F2-owned [EventActionFactory]
 * (the lone per-family touch, plan §append protocol). F2 already NAMES them in [EventStore.withDefaults].
 */
object LightActions {
    fun register(factory: EventActionFactory): EventActionFactory = factory
        .register(NewYearAction.NAME) { NewYearAction() }
        .register(ResetOfficerLockAction.NAME) { ResetOfficerLockAction() }
        .register(NoticeToHistoryLogAction.NAME) { args ->
            // args[0] = msg (string); args[1] = type (an ActionLogger token string or int), default YEAR_MONTH.
            val msg = (args[0] as JsonPrimitive).content
            val type = if (args.size > 1) LightActionWorld.resolveLogType((args[1] as JsonPrimitive).content)
                else LightActionWorld.YEAR_MONTH
            NoticeToHistoryLogAction(msg, type)
        }
        .register(AddGlobalBetrayAction.NAME) { args ->
            val cnt = if (args.isNotEmpty()) (args[0] as JsonPrimitive).content.toInt() else 1
            val ifMax = if (args.size > 1) (args[1] as JsonPrimitive).content.toInt() else 0
            AddGlobalBetrayAction(cnt, ifMax)
        }
}
