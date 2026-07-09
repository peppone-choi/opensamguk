package opensamguk.engine.turn

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDraft
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.actions.RestAction
import opensamguk.logic.actions.nation.NationActionResolveContext
import opensamguk.logic.actions.nation.NationActionResolver
import opensamguk.logic.actions.nation.NationActionResolverRegistry
import opensamguk.logic.ai.ChosenCommand
import opensamguk.logic.diplomacy.DiplomacyCascadeTerm
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.statview.WorldEnvBuilder
import opensamguk.logic.util.phpRound
import opensamguk.engine.turn.PerTurnOverlay.Companion.toLogicCity
import opensamguk.engine.turn.PerTurnOverlay.Companion.toLogicGeneral
import opensamguk.engine.turn.PerTurnOverlay.Companion.toLogicNation

/**
 * P5 Task FM2 (F-SEAM) — the NATION-command resolve path (R-SEAM §4).
 *
 * Dispatch order (live daemon):
 *  1. [NationActionResolverRegistry] when a code is registered (diplomacy accepts, strategic leaves).
 *  2. [CommandRegistry] logic definition bridge — runs the same `NationCommand.resolve(GeneralAction…)`
 *     bodies the golden tests use, then drains draft/cascade through [ChangeRecorder].
 *  3. Optional [nationCommandResolver] pass-through (tests / unknown codes).
 *
 * ## The ONE daemon-write rule (architecture-test enforced)
 * Mutations go ONLY through [ChangeRecorder] + dirty-free world apply — never JPA EntityManager.
 */
class ProcessNationCommand(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val hiddenSeed: String,
    private val registry: CommandRegistry? = null,
    private val startYear: Int = 0,
    private val turnTerm: Int = 60,
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

        // --- dispatch: registry leaf → logic CommandRegistry bridge → pass-through ---
        val registered: NationActionResolver? = NationActionResolverRegistry.resolve(nationCommand.actionCode)
        val resultTurn = when {
            registered != null ->
                dispatchRegistered(registered, rng, general, officerLevel, nationCommand, lastTurn, year, month, date)
            registry != null ->
                dispatchLogicDefinition(registry, rng, general, nationCommand, lastTurn, year, month, date)
            else ->
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
     * Bridge: run the logic-module [CommandRegistry] definition (`NationCommand.resolve` on
     * [GeneralActionResolveContext]) and drain draft/cascade identically to [ReservedTurnHandler].
     * Closes the "logic golden green, live nation pass silent no-op" gap for every ported nation cmd.
     */
    private fun dispatchLogicDefinition(
        registry: CommandRegistry,
        rng: RandUtil,
        general: TurnGeneral,
        nationCommand: ChosenCommand,
        lastTurn: LastTurn,
        year: Int,
        month: Int,
        date: String,
    ): LastTurn {
        val definition = registry.resolve(nationCommand.actionCode)
        if (definition === RestAction || definition.key == "휴식") {
            return nationCommandResolver.resolve(rng, nationCommand, lastTurn)
        }

        val nationId = general.nationId
        val preGeneral = toLogicGeneral(general)
        val preCity = world.getCityById(general.cityId)?.let { toLogicCity(it) }
            ?: error("ProcessNationCommand: city ${general.cityId} not in world")
        val preNation = world.getNationById(nationId)?.let { toLogicNation(it) }
        val draft = GeneralActionDraft(preGeneral, preCity, preNation)
        val args = LinkedHashMap(nationCommand.args)
        // parseArgs normalizes (argTest); empty map keeps raw if parse fails.
        val parsed = try {
            definition.parseArgs(args)
        } catch (_: Exception) {
            args
        }
        val resolveArgs = LinkedHashMap(if (parsed.isNotEmpty()) parsed else args)
        preloadLogicTargets(draft, resolveArgs)
        stageWorldInputs(nationCommand.actionCode, draft, resolveArgs, general)

        val destName = draft.destGeneral?.let { world.getGeneralById(it.id)?.name }
            ?: draft.destNation?.name
            ?: draft.destCity?.let { world.getCityById(it.id)?.name }
            ?: ""

        val candidateGenerals = stageCandidateGenerals(nationCommand.actionCode, general, resolveArgs)
        val candidateCityIds = stageCandidateCityIds(nationCommand.actionCode)

        val ctx = GeneralActionResolveContext(
            draft = draft,
            rng = rng,
            env = WorldEnvBuilder.worldEnv(year, startYear.ifZero { year }),
            month = month,
            date = date,
            args = resolveArgs,
            candidateGenerals = candidateGenerals,
            candidateCityIds = candidateCityIds,
            generalName = general.name,
            destGeneralName = destName,
            turnterm = turnTerm,
        )
        definition.resolve(ctx)

        // --- drain draft (mirrors ReservedTurnHandler nation-capable path) ---
        recorder.diffGeneral(preGeneral, draft.general)
        world.applyGeneralDirtyFree(applyLogicToGeneral(general, draft.general))

        if (preNation != null && draft.nation != null && draft.nation !== preNation) {
            recorder.diffNation(preNation, draft.nation!!)
            world.getNationById(nationId)?.let { world.applyNationDirtyFree(applyLogicToNation(it, draft.nation!!)) }
        }
        draft.destNation?.let { destN ->
            if (destN.id == nationId) return@let
            val pre = world.getNationById(destN.id)?.let { toLogicNation(it) } ?: return@let
            if (destN != pre) {
                recorder.diffNation(pre, destN)
                world.getNationById(destN.id)?.let { world.applyNationDirtyFree(applyLogicToNation(it, destN)) }
            }
        }
        draft.destGeneral?.let { destG ->
            if (destG.id == general.id) return@let
            val pre = world.getGeneralById(destG.id)?.let { toLogicGeneral(it) } ?: return@let
            if (destG != pre) {
                recorder.diffGeneral(pre, destG)
                world.getGeneralById(destG.id)?.let { world.applyGeneralDirtyFree(applyLogicToGeneral(it, destG)) }
            }
        }
        draft.destCity?.let { destC ->
            if (destC.id == preCity.id) {
                // actor city may also be dest for some cmds — still apply if mutated
            }
            val pre = world.getCityById(destC.id)?.let { toLogicCity(it) } ?: return@let
            if (destC != pre) {
                recorder.diffCity(pre, destC)
                world.getCityById(destC.id)?.let { world.applyCityDirtyFree(applyLogicToCity(it, destC)) }
            }
        }
        if (draft.city != preCity) {
            recorder.diffCity(preCity, draft.city)
            world.getCityById(preCity.id)?.let { world.applyCityDirtyFree(applyLogicToCity(it, draft.city)) }
        }

        for (delta in draft.cascadeDiplomacy) {
            val pre = world.getDiplomacy(delta.me, delta.you) ?: continue
            val applied = DiplomacyCascadeTerm.apply(pre.state, pre.term, delta.state, delta.term)
            world.updateDiplomacy(delta.me, delta.you, applied.state, applied.term)
            val post = world.getDiplomacy(delta.me, delta.you) ?: continue
            recorder.diffDiplomacy(pre, post)
        }
        for (moved in draft.cascadeGenerals) {
            val pre = world.getGeneralById(moved.id) ?: continue
            recorder.diffGeneral(toLogicGeneral(pre), moved)
            world.applyGeneralDirtyFree(applyLogicToGeneral(pre, moved))
        }
        for (moved in draft.cascadeCities) {
            val pre = world.getCityById(moved.id) ?: continue
            recorder.diffCity(toLogicCity(pre), moved)
            world.applyCityDirtyFree(applyLogicToCity(pre, moved))
        }

        for (line in ctx.logs()) world.pushLog(nationLog(general, "action", "general", line))
        for (line in ctx.plainLogs()) world.pushLog(nationLog(general, "action", "general", line))
        for (line in ctx.globalActionLogs()) world.pushLog(nationLog(general, "action", "global", line))
        draft.destGeneral?.id?.let { gid ->
            for (line in ctx.logsTo(gid)) {
                world.pushLog(
                    LogEntryDraft(scope = "general", category = "action", text = line, generalId = gid, nationId = world.getGeneralById(gid)?.nationId),
                )
            }
            for (line in ctx.plainLogsTo(gid)) {
                world.pushLog(
                    LogEntryDraft(scope = "general", category = "action", text = line, generalId = gid, nationId = world.getGeneralById(gid)?.nationId),
                )
            }
        }

        for (message in ctx.messages()) routeMessage(message, year, month)

        return LastTurn(command = definition.name, arg = resolveArgs)
    }

    private fun preloadLogicTargets(draft: GeneralActionDraft, args: Map<String, Any?>) {
        (args["destGeneralID"] as? Number)?.toInt()?.let { id ->
            world.getGeneralById(id)?.let { draft.destGeneral = toLogicGeneral(it) }
        }
        (args["destNationID"] as? Number)?.toInt()?.let { id ->
            world.getNationById(id)?.let { draft.destNation = toLogicNation(it) }
        }
        (args["destCityID"] as? Number)?.toInt()?.let { id ->
            world.getCityById(id)?.let { draft.destCity = toLogicCity(it) }
        }
    }

    /**
     * Stage live-world query substitutes that logic resolvers expect as fixture args / context lists
     * (PHP SELECT results the golden harness injects).
     */
    private fun stageWorldInputs(
        actionCode: String,
        draft: GeneralActionDraft,
        args: MutableMap<String, Any?>,
        general: TurnGeneral,
    ) {
        when (actionCode) {
            "che_허보" -> {
                val destCityId = (args["destCityID"] as? Number)?.toInt() ?: return
                val destCity = world.getCityById(destCityId) ?: return
                val destNationId = destCity.nationId
                if (destNationId == 0) return
                // PHP: SELECT city FROM city WHERE nation=dest AND supply=1
                val supplied = world.listCities()
                    .filter { it.nationId == destNationId && it.supplyState != 0 }
                    .map { it.id }
                    .sorted()
                args["__suppliedEnemyCities"] = supplied
            }
            "che_초토화" -> {
                draft.destCity?.let { dc ->
                    args.putIfAbsent("__destLevel", dc.level)
                }
            }
        }
    }

    private fun stageCandidateGenerals(
        actionCode: String,
        general: TurnGeneral,
        args: Map<String, Any?>,
    ): List<opensamguk.logic.domain.General> {
        val nationId = general.nationId
        return when (actionCode) {
            // same-nation peers (초토화 betray, 필사즉생, 백성동원)
            "che_초토화", "che_필사즉생", "che_백성동원" ->
                world.listGenerals()
                    .filter { it.nationId == nationId && it.id != general.id }
                    .map { toLogicGeneral(it) }
            // 허보: generals in dest city of enemy nation
            "che_허보" -> {
                val destCityId = (args["destCityID"] as? Number)?.toInt() ?: return emptyList()
                world.listGenerals()
                    .filter { it.cityId == destCityId && it.nationId != nationId && it.nationId != 0 }
                    .map { toLogicGeneral(it) }
            }
            else -> emptyList()
        }
    }

    private fun stageCandidateCityIds(actionCode: String): List<Int> =
        when (actionCode) {
            // 무작위수도이전: neutral level 5-6 cities (PHP SELECT)
            "che_무작위수도이전" ->
                world.listCities()
                    .filter { it.nationId == 0 && it.level in 5..6 }
                    .map { it.id }
                    .sorted()
            else -> emptyList()
        }

    private fun Int.ifZero(block: () -> Int): Int = if (this == 0) block() else this

    /**
     * Build the [NationActionResolveContext] from the world's draft state, run the registered resolver,
     * and route every buffered side effect through the [ChangeRecorder] single-dirty-source (T0.6).
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
        val preGeneral = toLogicGeneral(general)
        // dest nation (선전포고/수락/파기/종전 target) — derived from the parsed args' destNationID if present.
        val destNationId = (nationCommand.args["destNationID"] as? Number)?.toInt()
        val preDestNation = destNationId?.let { world.getNationById(it)?.let { n -> toLogicNation(n) } }
        // dest general (몰수 등) — destGeneralID when present.
        val destGeneralId = (nationCommand.args["destGeneralID"] as? Number)?.toInt()
        val preDestGeneral = destGeneralId?.let { world.getGeneralById(it)?.let { g -> toLogicGeneral(g) } }
        val destDisplayName = when {
            preDestNation != null -> preDestNation.name
            preDestGeneral != null -> world.getGeneralById(preDestGeneral.id)?.name.orEmpty()
            else -> ""
        }

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
            general = preGeneral,
            destGeneral = preDestGeneral,
            generalName = general.name,
            destName = destDisplayName,
            diplomacyMatrix = matrix,
            lastTurn = lastTurn,
        )

        resolver.resolve(ctx)

        // --- route the actor general draft → recorder diffs (exp/ded/meta) ---
        val postGeneral = ctx.general
        if (postGeneral != null && postGeneral != preGeneral) {
            recorder.diffGeneral(preGeneral, postGeneral)
            world.getGeneralById(general.id)?.let { world.applyGeneralDirtyFree(applyLogicToGeneral(it, postGeneral)) }
        }
        val postDestGeneral = ctx.destGeneral
        if (destGeneralId != null && preDestGeneral != null && postDestGeneral != null && postDestGeneral != preDestGeneral) {
            recorder.diffGeneral(preDestGeneral, postDestGeneral)
            world.getGeneralById(destGeneralId)?.let { world.applyGeneralDirtyFree(applyLogicToGeneral(it, postDestGeneral)) }
        }

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
     * Apply logic General exp/ded/gold/rice/meta back onto the engine row (same rounding as
     * [ReservedTurnHandler] general-pass applyGeneralPatch — phpRound half-away).
     */
    private fun applyLogicToGeneral(engine: TurnGeneral, logic: opensamguk.logic.domain.General): TurnGeneral =
        engine.copy(
            experience = phpRound(logic.experience),
            dedication = phpRound(logic.dedication),
            gold = logic.gold,
            rice = logic.rice,
            officerLevel = logic.officerLevel,
            cityId = logic.cityId,
            nationId = logic.nationId,
            troopId = logic.troop,
            injury = logic.injury,
            meta = logic.meta,
        )

    private fun applyLogicToCity(engine: City, post: opensamguk.logic.domain.City): City {
        val nextMeta = if (post.trust != (engine.meta["trust"] as? Number)?.toDouble()) {
            val m = LinkedHashMap(engine.meta); m["trust"] = post.trust; m
        } else {
            engine.meta
        }
        return engine.copy(
            level = post.level,
            state = post.state,
            commerce = post.commerce,
            commerceMax = post.commerceMax,
            agriculture = post.agriculture,
            agricultureMax = post.agricultureMax,
            population = post.population,
            populationMax = post.populationMax,
            security = post.security,
            securityMax = post.securityMax,
            defence = post.defense,
            defenceMax = post.defenseMax,
            wall = post.wall,
            wallMax = post.wallMax,
            supplyState = post.supplyState,
            frontState = post.frontState,
            nationId = post.nationId,
            trade = post.trade,
            region = post.region,
            term = post.term,
            officerSet = post.officerSet,
            conflict = post.conflict,
            meta = nextMeta,
        )
    }

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
