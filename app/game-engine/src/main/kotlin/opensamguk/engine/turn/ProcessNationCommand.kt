package opensamguk.engine.turn

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.nation.NationActionResolveContext
import opensamguk.logic.actions.nation.NationActionResolver
import opensamguk.logic.actions.nation.NationActionResolverRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.domain.LastTurn
import opensamguk.engine.turn.PerTurnOverlay.Companion.toLogicNation

/**
 * P5 Task FM2 (F-SEAM) — the NATION-command resolve path (R-SEAM §4).
 *
 * **No nation-command resolve path existed in the Kotlin daemon before P5** — `ReservedTurnHandler`
 * ported ONLY `processCommand` (the GENERAL path). This is the faithful port of
 * `processNationCommand` (`legacy/devsam-core/hwe/sammo/TurnExecutionHelper.php:72-109`), reusing the
 * GREEN general `processCommand` while-loop (`:111-167`) as the structural template — the SAME
 * full-condition → addTermStack → run → getAlternativeCommand spine, sharing the same
 * `getFailString`/`getTermString` log strings.
 *
 * It runs in the daemon's nation pass, which executes BEFORE the general pass within one general's
 * turn (R-SEAM §2 `:299-348`), seeded with the `'nationCommand'` 6-component RNG
 * (`serializeSeed(hiddenSeed,'nationCommand',year,month,generalId,cmd.getRawClassName())`, `:310-317`),
 * and writes the `turn_last_{officer_level}` KV (`:322`) + clears the general's cached city handle
 * (`setRawCity(null)`, `:323`).
 *
 * ## The ONE daemon-write rule (architecture-test enforced)
 * The nation resolve writes ONLY through the [ChangeRecorder] single-dirty-source (the
 * `turn_last_{officer_level}` nation-meta delta) — NEVER a JPA `EntityManager`, NEVER an inline
 * `world.updateNation`/`updateGeneral`. The `nation_turn` ring rotation + the actual `che_*`
 * nation-command state-mutation/logs (불가침제의/선전포고/천도 …) are P6; here the seam ports the
 * seed + the while-loop structure + the KV delta + the `setRawCity` invalidation. The per-command
 * mutation is delegated to a pluggable [nationCommandResolver] hook (default = a structural no-op
 * producing the command's `getResultTurn` — mirroring the [ReservedTurnHandler.nextRuler]/
 * [ReservedTurnHandler.dyingMessage] hook pattern), so no P6 internals are fabricated.
 *
 * @param world the live in-memory turn world (read-only here; the resolver hook may queue recorder deltas).
 * @param recorder the SINGLE dirty source — the `turn_last_{officer_level}` nation-meta delta lands here.
 * @param hiddenSeed the per-game `UniqueConst::$hiddenSeed` (the captured golden FIXTURE INPUT) — seed
 *   component 1 of the `'nationCommand'` lineage.
 * @param nationCommandResolver the per-command resolve hook (the `che_*` nation-command `run`). It is
 *   handed the SOLE per-command `'nationCommand'` [RandUtil] (threaded by reference) + the reserved/
 *   AI-chosen [ChosenCommand] + the pre-turn [LastTurn], and returns the result [LastTurn]
 *   (`$commandObj->getResultTurn()`, `:108`). Default = pass-through of the pre-turn lastTurn (the P6
 *   nation-command internals are NOT ported here — the seam + seed + KV delta are).
 */
class ProcessNationCommand(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val hiddenSeed: String,
    private val nationCommandResolver: NationCommandResolver = NationCommandResolver { _, _, lastTurn -> lastTurn },
) {

    /** The per-nation-command resolve hook (the `che_*` `run`); P6 wires the real packs. */
    fun interface NationCommandResolver {
        /**
         * Resolve [command] on the SOLE per-command [rng] (threaded by reference), returning the result
         * [LastTurn] (`$commandObj->getResultTurn()`). Any state mutation goes through the [ChangeRecorder]
         * single-dirty-source (NEVER inline / NEVER an EntityManager).
         */
        fun resolve(rng: RandUtil, command: ChosenCommand, lastTurn: LastTurn): LastTurn
    }

    /**
     * Resolve ONE nation command for [generalId] (R-SEAM §4 — the [processNationCommand][ProcessNationCommand]
     * port). Mirrors `TurnExecutionHelper.php:72-109` + the surrounding `:310-323` seed/KV/setRawCity seam.
     *
     * @param officerLevel `$general->getVar('officer_level')` — keys the `turn_last_{officer_level}` KV (`:261/322`).
     * @param nationCommand the reserved/AI-chosen `(actionCode, RAW args)` (the `getRawClassName` keys the seed).
     * @param lastTurn the pre-turn `LastTurn` for this `(nation, officer_level)` ring slot (`:271`).
     * @param year/month the game year/month — RNG seed components 3/4.
     * @param date the turn-time `HH:MM` for the fail/term log `<1>date</>` suffix (parity-faithful; the P6
     *   nation-command resolve emits the actual logs — the default no-op resolver pushes none).
     * @return the result `LastTurn` (`$commandObj->getResultTurn()`), also queued as the KV delta.
     */
    fun process(
        generalId: Int,
        officerLevel: Int,
        nationCommand: ChosenCommand,
        lastTurn: LastTurn,
        year: Int,
        month: Int,
        @Suppress("UNUSED_PARAMETER") date: String,
    ): LastTurn {
        val general = world.getGeneralById(generalId)
            ?: error("ProcessNationCommand: general $generalId not in world")
        val nationId = general.nationId

        // --- the 'nationCommand' 6-component RNG (PHP grand truth :310-317) ---
        // Component 2 is the LITERAL string "nationCommand" (DISTINCT from "generalCommand"); component 6
        // is the nation command's short class name (= getRawClassName(true) = the action code). RE-SEEDED
        // here — NOT one shared stream with the general pass, NOT the 'GeneralAI' decision stream.
        val rng = RandUtil(
            LiteHashDrbg(
                serializeSeed(hiddenSeed, NATION_COMMAND_TOKEN, year, month, generalId, nationCommand.actionCode),
            ),
        )

        // --- registry-backed dispatch (T0.6) ---
        // The diplomacy / nation-internal / event_*연구 leaf families register a NationActionResolver
        // by action code (Wave A1/B/D). When one exists, build the NationActionResolveContext from the
        // world's draft state, run resolve(), and route the buffered side effects through the
        // ChangeRecorder single-dirty-source (diplomacy deltas → diffDiplomacy, logs → ActionLogger
        // scopes, messages → the mailbox channel, KV → recordKv, draft nation/dest → diffNation) — NEVER
        // an inline write / EntityManager. When no resolver is registered (the family has not landed),
        // fall back to the legacy pass-through hook (default no-op getResultTurn).
        val registered: NationActionResolver? = NationActionResolverRegistry.resolve(nationCommand.actionCode)
        val resultTurn = if (registered != null) {
            dispatchRegistered(registered, rng, general, officerLevel, nationCommand, lastTurn, year, month, date)
        } else {
            nationCommandResolver.resolve(rng, nationCommand, lastTurn)
        }

        // --- $nationStor->setValue("turn_last_{officer_level}", $resultNationTurn->toRaw()) (:322) ---
        // Recorded as the nation-meta KV delta through the ChangeRecorder single-dirty-source (NOT inline,
        // NOT an EntityManager). The nation KV rides the `nation` row meta jsonb in the engine slice.
        recordTurnLastKv(nationId, officerLevel, resultTurn)

        // --- $general->setRawCity(null) (:323) ---
        // PHP clears the general's lazily-cached City object handle so the next read re-fetches it. The
        // in-memory world has NO such cached handle (the city is always read fresh via world.getCityById),
        // so this is a faithful no-op marker — there is nothing to invalidate.
        // (intentionally empty)

        return resultTurn
    }

    /**
     * Build the [NationActionResolveContext] from the world's draft state, run the registered resolver,
     * and route every buffered side effect through the [ChangeRecorder] single-dirty-source (T0.6):
     *  - diplomacy deltas → [InMemoryTurnWorld.updateDiplomacy] (dirty-free apply) + `diffDiplomacy`,
     *  - the actor draft nation + dest nation → `diffNation`,
     *  - the four ActionLogger scopes (action/general-history/national-history/global-history +
     *    global-action + dest-national-history) → `world.pushLog` with the matching scope/category,
     *  - the buffered Messages → the mailbox channel (receiver row BEFORE sender row),
     *  - the buffered KV writes → `recorder.recordKv`.
     * Returns the resolver's result [LastTurn].
     */
    private fun dispatchRegistered(
        resolver: NationActionResolver,
        rng: RandUtil,
        general: TurnGeneral,
        officerLevel: Int,
        nationCommand: ChosenCommand,
        lastTurn: LastTurn,
        year: Int,
        month: Int,
        date: String,
    ): LastTurn {
        val nationId = general.nationId
        val preNation = world.getNationById(nationId)?.let { toLogicNation(it) }
            ?: error("ProcessNationCommand: nation $nationId not in world")
        // dest nation (선전포고/수락/파기/종전 target) — derived from the parsed args' destNationID if present.
        val destNationId = (nationCommand.args["destNationID"] as? Number)?.toInt()
        val preDestNation = destNationId?.let { world.getNationById(it)?.let { n -> toLogicNation(n) } }

        // snapshot the diplomacy matrix the resolver reads.
        val matrix = LinkedHashMap<Pair<Int, Int>, opensamguk.logic.domain.Diplomacy>()
        for (d in world.listDiplomacy()) {
            matrix[d.fromNationId to d.toNationId] = PerTurnOverlay.toLogicDiplomacy(d)
        }

        val ctx = NationActionResolveContext(
            nation = preNation,
            rng = rng,
            generalId = general.id,
            officerLevel = officerLevel,
            year = year,
            month = month,
            date = date,
            args = nationCommand.args,
            destNation = preDestNation,
            diplomacyMatrix = matrix,
            lastTurn = lastTurn,
        )

        resolver.resolve(ctx)

        // --- route the actor + dest nation drafts → recorder diffs (single dirty source) ---
        if (ctx.nation != preNation) {
            recorder.diffNation(preNation, ctx.nation)
            world.getNationById(nationId)?.let { world.applyNationDirtyFree(applyLogicToNation(it, ctx.nation)) }
        }
        val postDest = ctx.destNation
        if (destNationId != null && preDestNation != null && postDest != null && postDest != preDestNation) {
            recorder.diffNation(preDestNation, postDest)
            world.getNationById(destNationId)?.let { world.applyNationDirtyFree(applyLogicToNation(it, postDest)) }
        }

        // --- diplomacy deltas → world.updateDiplomacy (dirty-free) + diffDiplomacy (T0.4) ---
        for (delta in ctx.diplomacyDeltas()) {
            val pre = world.getDiplomacy(delta.fromNationId, delta.toNationId) ?: continue
            world.updateDiplomacy(delta.fromNationId, delta.toNationId, delta.state, delta.term, delta.dead)
            val post = world.getDiplomacy(delta.fromNationId, delta.toNationId) ?: continue
            recorder.diffDiplomacy(pre, post)
        }

        // --- the ActionLogger scopes → world.pushLog ---
        for (line in ctx.actionLogs()) world.pushLog(nationLog(general, "action", "general", line))
        for (line in ctx.generalHistoryLogs()) world.pushLog(nationLog(general, "history", "general", line))
        for (line in ctx.nationalHistoryLogs()) world.pushLog(nationLog(general, "history", "nation", line))
        for (line in ctx.destNationalHistoryLogs()) {
            world.pushLog(LogEntryDraft(scope = "nation", category = "history", text = line, nationId = destNationId))
        }
        for (line in ctx.globalActionLogs()) world.pushLog(nationLog(general, "action", "global", line))
        for (line in ctx.globalHistoryLogs()) world.pushLog(nationLog(general, "history", "global", line))

        // --- buffered KV writes → recorder.recordKv ---
        for (kv in ctx.kvWrites()) recorder.recordKv(kv.table, kv.namespace, kv.key, kv.value)

        // --- buffered Messages → the mailbox channel (receiver row BEFORE sender row) ---
        for (message in ctx.messages()) routeMessage(message, year, month)

        return ctx.resultTurn
    }

    /** Map a LogEntryDraft for a nation-command log scope. */
    private fun nationLog(general: TurnGeneral, category: String, scope: String, text: String): LogEntryDraft =
        when (scope) {
            "general" -> LogEntryDraft(scope = "general", category = category, text = text, generalId = general.id, nationId = general.nationId)
            "nation" -> LogEntryDraft(scope = "nation", category = category, text = text, nationId = general.nationId)
            else -> LogEntryDraft(scope = "global", category = category, text = text, generalId = general.id, nationId = general.nationId)
        }

    /**
     * Route a logic [opensamguk.logic.message.Message] through the mailbox channel: produce its send
     * rows (receiver BEFORE sender) and record each INSERT with the pre-assigned in-memory id folded
     * into the body's receiver/sender back-references (research §2).
     */
    private fun routeMessage(message: opensamguk.logic.message.Message, year: Int, month: Int) {
        val drafts = message.send()
        // PHP folds the receiver id into the receiver body's receiverMessageID then the sender body's
        // senderMessageID after the AUTO_INCREMENT returns; the in-memory channel assigns ids in emit
        // order (receiver first) so the monotonic id matches the flushed SERIAL.
        var receiverId: Int? = null
        for (draft in drafts) {
            val option = LinkedHashMap(draft.option ?: emptyMap())
            // fold the back-references the way PHP `send` does (receiverMessageID on both rows; senderMessageID on the sender row).
            if (draft.whichRow == opensamguk.logic.message.MessageRowDraft.Row.RECEIVER) {
                val id = recorder.recordMessageInsert(
                    mailbox = draft.mailbox, type = draft.type.value, srcId = draft.srcId, destId = draft.destId,
                    time = message.date, validUntil = message.validUntil,
                    bodyJson = encodeMessageBody(draft, option, receiverIdToFold = null),
                )
                receiverId = id
            } else {
                recorder.recordMessageInsert(
                    mailbox = draft.mailbox, type = draft.type.value, srcId = draft.srcId, destId = draft.destId,
                    time = message.date, validUntil = message.validUntil,
                    bodyJson = encodeMessageBody(draft, option, receiverIdToFold = receiverId),
                )
            }
        }
    }

    /** Byte-faithful `{src,dest,text,option}` jsonb for a message row. */
    private fun encodeMessageBody(
        draft: opensamguk.logic.message.MessageRowDraft,
        option: Map<String, Any?>,
        receiverIdToFold: Int?,
    ): String {
        val opt = LinkedHashMap<String, Any?>(option)
        if (receiverIdToFold != null) opt["receiverMessageID"] = receiverIdToFold
        val body = linkedMapOf<String, Any?>(
            "src" to draft.src.toArray(),
            "dest" to draft.dest.toArray(),
            "text" to draft.text,
            "option" to (if (draft.option == null) null else opt),
        )
        return opensamguk.infra.persistence.MetaJson.encode(body)
    }

    /**
     * Apply a logic Nation's mutated scalar/meta fields back onto the engine Nation row. (The engine
     * Nation row carries name/color/gold/rice/level/typeCode/meta; `tech` rides meta in the engine
     * slice, so it is not a separate engine column here.)
     */
    private fun applyLogicToNation(engine: Nation, logic: opensamguk.logic.domain.Nation): Nation =
        engine.copy(
            name = logic.name,
            color = logic.color,
            gold = logic.gold,
            rice = logic.rice,
            level = logic.level,
            typeCode = logic.typeCode,
            meta = logic.meta,
        )

    /**
     * Queue the `turn_last_{officer_level}` nation-meta KV delta via the [ChangeRecorder] single-dirty-source.
     * Diffs the nation's pre-state against a post-state carrying the new meta key (insertion-order-preserving),
     * so the recorder owns dirtiness — the world's own `updateNation` (the JPA-competing dirty path) is never
     * touched.
     */
    private fun recordTurnLastKv(nationId: Int, officerLevel: Int, resultTurn: LastTurn) {
        val nation = world.getNationById(nationId) ?: return
        val pre = toLogicNation(nation)
        val nextMeta = LinkedHashMap(pre.meta)
        nextMeta["turn_last_$officerLevel"] = resultTurn.toRaw()
        recorder.diffNation(pre, pre.copy(meta = nextMeta))
    }

    companion object {
        /** PHP seed component 2 for the nation pass (`TurnExecutionHelper.php:312`). */
        const val NATION_COMMAND_TOKEN: String = "nationCommand"
    }
}
