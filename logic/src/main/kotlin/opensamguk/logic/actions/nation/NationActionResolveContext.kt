package opensamguk.logic.actions.nation

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import opensamguk.logic.message.Message

/**
 * A diplomacy `(from, to)` state transition the nation-command resolver buffers (T0.6). The engine
 * routes each to `ChangeRecorder.diffDiplomacy` (the T0.4 delta channel). Bidirectional transitions
 * (선전포고/수락/파기/종전) buffer TWO (both directions).
 */
data class DiplomacyDelta(
    val fromNationId: Int,
    val toNationId: Int,
    val state: Int,
    val term: Int,
    val dead: Int? = null,
)

/**
 * The mutable per-command DRAFT + sinks for a NATION command resolve (T0.6 — the foundation the
 * diplomacy / nation-internal / event_*연구 leaf families consume). Faithful to the PHP NationCommand
 * `run()` surface: the actor [nation] (draft), the actor [general] (exp/ded), the [destNation]
 * (선전포고/수락 target), the diplomacy matrix read ([diplomacyOf]), and the side-effect sinks
 * (diplomacy deltas, the four ActionLogger scopes — action / general-history / national-history /
 * global-history + global-action — and the Message sink).
 *
 * The leaf `resolve()` MUTATES `nation`/`destNation`/`general` in place and BUFFERS its side effects;
 * the engine [opensamguk.engine.turn.ProcessNationCommand] diffs the drafts → recorder deltas, routes
 * the buffered logs to the ActionLogger scopes, the diplomacy deltas to `diffDiplomacy`, and the
 * messages to the mailbox channel — NEVER an inline write (the ONE daemon-write rule). The leaf
 * returns its result [LastTurn] via [setResultTurn] (`$commandObj->getResultTurn()`).
 *
 * Accept-flow leaves (불가침수락/등용수락 …) run on a [NoRng][opensamguk.common.rng.NoRng]-backed [rng]
 * (zero-draw invariant) — a stray draw throws.
 */
class NationActionResolveContext(
    /** The actor nation draft (mutate in place; the engine diffs it). */
    var nation: Nation,
    /** The SOLE per-command `'nationCommand'` RNG, threaded by reference (un-drawn by diplomacy leaves). */
    val rng: RandUtil,
    /** The acting general id (the action/general-history log scope owner). */
    val generalId: Int,
    /** The acting general's officer level — keys the `turn_last_{officer_level}` KV. */
    val officerLevel: Int,
    val year: Int,
    val month: Int,
    /** Turn-time `HH:MM` for the `<1>date</>` log suffix. */
    val date: String,
    /** The parsed reserved arg map (PHP `$this->arg`). */
    val args: Map<String, Any?> = emptyMap(),
    /** The dest nation draft (선전포고/수락/파기/종전 target); mutate in place. Null for nation-internal. */
    var destNation: Nation? = null,
    /**
     * Actor general draft (exp/ded/meta). Engine seeds from the live world; null only in pure
     * foundation tests that never touch general state.
     */
    var general: General? = null,
    /**
     * Dest general draft (몰수 등). Engine seeds when args carry destGeneralID; null otherwise.
     */
    var destGeneral: General? = null,
    /** Actor general display name for log tokens (engine supplies from TurnGeneral.name). */
    val generalName: String = "",
    /** Dest nation/general display name for log tokens. */
    val destName: String = "",
    /** The diplomacy matrix snapshot keyed `(from, to)` (the resolver reads current state/term). */
    private val diplomacyMatrix: Map<Pair<Int, Int>, Diplomacy> = emptyMap(),
    private val lastTurn: LastTurn,
) {
    /** The result LastTurn (`$commandObj->getResultTurn()`); starts as a copy of the pre-turn lastTurn. */
    var resultTurn: LastTurn = lastTurn
        private set

    fun setResultTurn(next: LastTurn) { resultTurn = next }

    /** Read the current diplomacy `(from, to)` row (null if absent). */
    fun diplomacyOf(from: Int, to: Int): Diplomacy? = diplomacyMatrix[from to to]

    // --- side-effect sinks (buffered; the engine drains + routes them) ---------------------------

    private val diplomacyDeltas = mutableListOf<DiplomacyDelta>()
    private val actionLogs = mutableListOf<String>()
    private val generalHistoryLogs = mutableListOf<String>()
    private val nationalHistoryLogs = mutableListOf<String>()
    private val globalActionLogs = mutableListOf<String>()
    private val globalHistoryLogs = mutableListOf<String>()
    private val destNationalHistoryLogs = mutableListOf<String>()
    private val messages = mutableListOf<Message>()
    private val kvWrites = mutableListOf<KvDelta>()

    /** Buffer a bidirectional pair in one call (선전포고/수락/파기/종전 update both directions). */
    fun setDiplomacyBidirectional(a: Int, b: Int, state: Int, term: Int, dead: Int? = null) {
        diplomacyDeltas.add(DiplomacyDelta(a, b, state, term, dead))
        diplomacyDeltas.add(DiplomacyDelta(b, a, state, term, dead))
    }

    /** Buffer a single directional diplomacy transition. */
    fun setDiplomacy(from: Int, to: Int, state: Int, term: Int, dead: Int? = null) {
        diplomacyDeltas.add(DiplomacyDelta(from, to, state, term, dead))
    }

    fun addActionLog(text: String) { if (text.isNotEmpty()) actionLogs.add(text) }
    fun addGeneralHistoryLog(text: String) { if (text.isNotEmpty()) generalHistoryLogs.add(text) }
    fun addNationalHistoryLog(text: String) { if (text.isNotEmpty()) nationalHistoryLogs.add(text) }
    fun addDestNationalHistoryLog(text: String) { if (text.isNotEmpty()) destNationalHistoryLogs.add(text) }
    fun addGlobalActionLog(text: String) { if (text.isNotEmpty()) globalActionLogs.add(text) }
    fun addGlobalHistoryLog(text: String) { if (text.isNotEmpty()) globalHistoryLogs.add(text) }

    /** Buffer a Message to send (the engine routes it to the mailbox channel, receiver-before-sender). */
    fun sendMessage(message: Message) { messages.add(message) }

    /** Buffer a KV write (delete-on-null). `table=="nation_env"` → int-ns; else string-ns. */
    fun recordKv(table: String, namespace: String, key: String, value: Any?) {
        kvWrites.add(KvDelta(table, namespace, key, value))
    }

    fun diplomacyDeltas(): List<DiplomacyDelta> = diplomacyDeltas.toList()
    fun actionLogs(): List<String> = actionLogs.toList()
    fun generalHistoryLogs(): List<String> = generalHistoryLogs.toList()
    fun nationalHistoryLogs(): List<String> = nationalHistoryLogs.toList()
    fun destNationalHistoryLogs(): List<String> = destNationalHistoryLogs.toList()
    fun globalActionLogs(): List<String> = globalActionLogs.toList()
    fun globalHistoryLogs(): List<String> = globalHistoryLogs.toList()
    fun messages(): List<Message> = messages.toList()
    fun kvWrites(): List<KvDelta> = kvWrites.toList()

    data class KvDelta(val table: String, val namespace: String, val key: String, val value: Any?)
}

/**
 * The per-command nation-action resolve hook (T0.6) — the `che_*` nation-command `run()` for the
 * diplomacy / nation-internal / event_*연구 leaves. Wave A1/B/D leaves register one per action code;
 * the engine looks it up by action code and invokes it with a built [NationActionResolveContext].
 *
 * `resolve()` mutates the draft + buffers side effects; the engine routes them through the recorder.
 */
fun interface NationActionResolver {
    fun resolve(ctx: NationActionResolveContext)
}

/**
 * action-code → [NationActionResolver] registry (T0.6 — single owner). Leaf families register their
 * resolver here; the engine consults it in the nation pass. An unregistered code falls through (the
 * engine's no-op pass-through default), so the daemon never crashes on a code whose family has not
 * landed.
 */
object NationActionResolverRegistry {
    private val resolvers = LinkedHashMap<String, NationActionResolver>()

    fun register(actionCode: String, resolver: NationActionResolver) {
        resolvers[actionCode] = resolver
    }

    fun resolve(actionCode: String): NationActionResolver? = resolvers[actionCode]

    /** Test/registry reset (leaf families register at wiring; tests may clear). */
    fun clear() = resolvers.clear()
}
