package opensamguk.logic.ai

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.LastTurn

/**
 * F-DISPATCH — `GeneralAI`, a faithful port of the two LIVE dispatcher spines of
 * `legacy/devsam-core/hwe/sammo/GeneralAI.php` (frozen historical baseline (ADR-LITE-042; not current product authority), read in full).
 *
 * Task FD1 ports [chooseGeneralTurn] (`:3709-3848`). Task FD2 adds `chooseNationTurn` (`:3640-3683`) and
 * the QUARANTINED `chooseInstantNationTurn` stub (`:3685-3705`, decision #3 — not wired, off the gate).
 *
 * ## The spine IS the draw order (decision #1, the load-bearing parity contract)
 * The whole AI decision runs on ONE [RandUtil] built once per general per decision from
 * `serializeSeed(hiddenSeed,"GeneralAI",year,month,generalId)` (F-SEED [AiSeed.rng]) and threaded by
 * reference; NEVER re-seeded. This class makes only ONE draw of its OWN — the `:3719` npcmsg prologue
 * draw — and otherwise threads the rng into [updateInstance] (calcGenType's FIRST draw, F-INSTANCE) and
 * into the `do<한글>` bodies (the leaf families, Wave-1). **The merged [AutorunGeneralPolicy.priority]
 * ORDER is the `do<한글>` dispatch order, which is the log order AND the per-decision draw order.**
 *
 * ## The dispatch map (m12)
 * [dispatch] is a `LinkedHashMap<String, (LastTurn?) -> ChosenCommand?>` keyed by ACTION-NAME. The leaf
 * families REGISTER their `do<한글>` bodies into this map by name; THIS class owns the loop body and the
 * pre-loop branches — frozen here. A family never edits the loop; it only adds its method + the by-name
 * registration. The priority loop walks [AutorunGeneralPolicy.priority] in order and invokes
 * `dispatch[actionName]` (first non-null wins). An action with no registered body is a no-op (null).
 *
 * ## The two cache regimes / read-only-over-entities discipline
 * The AI is READ-ONLY over GAME ENTITIES — it returns a [ChosenCommand] `(actionCode, RAW args, reason)`;
 * the meta-KV side-effects (here only `killturn=1` at `:3755`, and `defence_train=80` at `:3742`) route
 * through the [recordGeneralKv] ChangeRecorder delta seam (decision #12 / M4), NOT inline and NOT a
 * module-static Map. The chosen command's resolve runs the EXISTING P2-P4 delta path downstream.
 *
 * PURE `:logic` — no Spring, no DB. The engine adapter (F-SEAM `AiTurnAdapter`) supplies the real hooks
 * ([updateInstance], the `do<한글>` bodies, [candidateAllowed], [recordGeneralKv]); the defaults here make
 * the spine self-contained + unit-testable.
 *
 * @property generalPolicy the merged general policy (F-POLICY) — its [AutorunGeneralPolicy.priority]
 *   ORDER is the dispatch spine; its `can<name>` flags ([AutorunGeneralPolicy.canFor]) gate the loop.
 * @property dispatch the by-name `do<한글>` registry the leaf families populate (m12). Frozen loop body.
 * @property updateInstance the `:3715` prologue (F-INSTANCE; calcGenType's FIRST draw lives there).
 * @property candidateAllowed the F-BRIDGE gate `(actionCode, rawArgs) -> Boolean` — the GENERAL
 *   reserved-honor path does NOT consult it (decision #4); the `do<한글>` bodies do (except gate-exempt).
 * @property recordGeneralKv the meta-KV delta seam `(generalId, key, value) -> Unit` (decision #12).
 * @property logFailString the deny fail-log sink — the GENERAL path NEVER calls it (decision #4); kept
 *   for symmetry with the nation path (FD2) and to assert the no-log invariant.
 * @property do선양 / do집합 / do거병 / do국가선택 / do방랑군이동 / do건국 / do해산 / do중립 the pre-loop +
 *   terminal branch bodies (leaf families wire the real ones; defaults return null / a neutral fallback).
 *
 * ## The nation spine (FD2)
 * `chooseNationTurn` (`:3640-3683`, the officer_level>=5 LIVE nation pass — runs BEFORE the general pass in
 * the daemon, R-SEAM §2) and the QUARANTINED `chooseInstantNationTurn` (`:3685-3707`, decision #3 / R-SEAM §3
 * — NOT wired, OFF the gate, DELIBERATELY DIVERGENT, must NOT be collapsed with `chooseNationTurn`) consume
 * the nation-side hooks below. The nation policy's merged [AutorunNationPolicy.priority] ORDER is the nation
 * `do<한글>` dispatch/log/draw order. The nation loop passes the reserved command's `lastTurn` into each
 * `do{X}($lastTurn)` (the general loop passes `null`).
 *
 * @property nationPolicy the merged nation policy (F-POLICY) — its [AutorunNationPolicy.priority] ORDER is
 *   the nation dispatch spine; [AutorunNationPolicy.canFor] is the guard-A/guard-B accessor.
 * @property nationDispatch the by-name nation `do<한글>` registry the leaf families populate (m12).
 * @property updateNationInstance the `:3618` prologue (F-INSTANCE).
 * @property categorizeNationGeneral / categorizeNationCities the `:3625` / `:3626` derive hooks (F-FACADE;
 *   the by-reference cities-before-generals invariant lives in F-FACADE, not here — this is the literal
 *   PHP call order).
 * @property choosePromotion / chooseNonLordPromotion the npcType>=2 step-1 promotion hooks (MAY draw —
 *   L-RATES); `chooseTexRate`/`chooseGoldBillRate`/`chooseRiceBillRate` the rate hooks (NO draws).
 * @property hasFullConditionMet the GATED reserved-honor gate `(reserved) -> Boolean` (decision #4 — the
 *   NATION path gates, DISTINCT from the general NO-gate path).
 * @property getFailString the deny-path fail-string `(reserved) -> String` (`:3656`).
 * @property buildNeutralNationCommand the `:3680` neutral fallback builder (PHP `buildNationCommandClass(null,...)`).
 * @property nationGeneralId the meta-KV delta target for `use_auto_nation_turn` (`:3631`).
 * @property useAutoNationTurn the pre-turn `getAuxVar('use_auto_nation_turn') ?? 1` snapshot (`:3630`).
 * @property nationTurnTimeHm the `$general->getTurnTime(TURNTIME_HM)` date stamp for the fail-log (`:3655`).
 */
class GeneralAI(
    private val generalPolicy: AutorunGeneralPolicy,
    private val dispatch: LinkedHashMap<String, (LastTurn?) -> ChosenCommand?>,
    private val updateInstance: (RandUtil) -> Unit = {},
    private val candidateAllowed: (actionCode: String, rawArgs: Map<String, Any?>) -> Boolean = { _, _ -> true },
    private val recordGeneralKv: (generalId: Int, key: String, value: Any?) -> Unit = { _, _, _ -> },
    private val logFailString: (String) -> Unit = {},
    private val do선양: ((LastTurn?) -> ChosenCommand?) = { null },
    private val do집합: ((LastTurn?) -> ChosenCommand?) = { null },
    private val do거병: ((LastTurn?) -> ChosenCommand?) = { null },
    private val do국가선택: ((LastTurn?) -> ChosenCommand?) = { null },
    private val do방랑군이동: ((LastTurn?) -> ChosenCommand?) = { null },
    private val do건국: ((LastTurn?) -> ChosenCommand?) = { null },
    private val do해산: ((LastTurn?) -> ChosenCommand?) = { null },
    private val do중립: ((LastTurn?) -> ChosenCommand) = { ChosenCommand("che_중립", emptyMap()) },
    // --- nation spine (FD2) ---
    private val nationPolicy: AutorunNationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 0),
    private val nationDispatch: LinkedHashMap<String, (LastTurn?) -> ChosenCommand?> = linkedMapOf(),
    private val updateNationInstance: () -> Unit = {},
    private val categorizeNationGeneral: () -> Unit = {},
    private val categorizeNationCities: () -> Unit = {},
    private val choosePromotion: () -> Unit = {},
    private val chooseNonLordPromotion: () -> Unit = {},
    private val chooseTexRate: () -> Unit = {},
    private val chooseGoldBillRate: () -> Unit = {},
    private val chooseRiceBillRate: () -> Unit = {},
    private val hasFullConditionMet: (reserved: ChosenCommand) -> Boolean = { true },
    private val getFailString: (reserved: ChosenCommand) -> String = { "" },
    private val buildNeutralNationCommand: () -> ChosenCommand = { ChosenCommand("휴식", emptyMap()) },
    private val nationGeneralId: Int = 0,
    private val useAutoNationTurn: Boolean = true,
    private val nationTurnTimeHm: String = "",
) {

    /**
     * Faithful port of `chooseGeneralTurn` (PHP `GeneralAI.php:3709-3848`). The general decision spine.
     *
     * The control flow VERBATIM (PHP source-line order — the order IS the draw/log order):
     *  1. `:3715` [updateInstance] prologue (calcGenType FIRST draw; F-INSTANCE).
     *  2. `:3719` the npcmsg prologue draw — `npcmsg && rng.nextBool(npcMessageProb)` — a `&&` short-circuit,
     *     side-effect-only (no return). ZERO draws when [GeneralAiInput.npcmsg] is falsy.
     *  3. `:3741` npcType>=2 → queue `defence_train=80` (a meta-KV side-effect; no draw, no return).
     *  4. `:3745` `do선양` if officer_level==12 && can선양 (reason 'do선양').
     *  5. `:3753` npcType==5 → nation 0: queue `killturn=1` + return reserved (reason '사망'); else `do집합`
     *     (gate-exempt; throws-if-null in PHP — here the wired body is expected non-null) reason 'do집합'.
     *  6. `:3767` the RESERVED-HONOR (decision #4): a non-휴식 reserved command is returned WITHOUT a gate
     *     and WITHOUT a log (reason 'do예약턴'). Do NOT add a check — the ReservedTurnHandler execution gate
     *     is the backstop.
     *  7. `:3772` injury > cureThreshold → `che_요양` direct build (reason 'do요양', gate-exempt).
     *  8. `:3778` (npcType 2|3) && nation 0 → `do거병` (reason 'do거병').
     *  9. `:3786` nation 0 && can국가선택 → `do국가선택`; if null → `do중립` (reasons 'do국가선택'/'do중립').
     * 10. `:3797` npcType<2 && nation 0 && !can국가선택 → return reserved (reason '재야유저').
     * 11. `:3802` npcType>=2 && officer_level==12 && !capital → (relYearMonth>1) `do건국`, then `do방랑군이동`,
     *     then (relYearMonth>1) `do해산`. Each first-non-null returns with its reason.
     * 12. `:3829` the PRIORITY LOOP: foreach [AutorunGeneralPolicy.priority] → [AutorunGeneralPolicy.canFor]
     *     guard (null=skip-with-notice / false=skip) → `dispatch[actionName]?.invoke(null)` first-non-null
     *     wins (reason 'do'+actionName).
     * 13. `:3845` `do중립` TERMINAL fallback (never null).
     */
    fun chooseGeneralTurn(reservedCommand: ChosenCommand, input: GeneralAiInput): ChosenCommand {
        val rng = input.rng

        // (1) :3715 — the updateInstance prologue (calcGenType FIRST draw, F-INSTANCE).
        updateInstance(rng)

        // (2) :3719 — npcmsg prologue draw. `&&` short-circuit: rng.nextBool drawn ONLY if npcmsg truthy.
        // Side-effect-only (the message send) — no return. ZERO draws when npcmsg is falsy.
        if (isTruthyMsg(input.npcmsg) && rng.nextBool(input.npcMessageProb)) {
            // PHP :3720-3737 sends a MessageTarget/Message; in :logic this is a no-op (the engine sends it).
            // The DRAW is the parity target, not the send.
        }

        // (3) :3741 — npcType>=2 queues defence_train=80 (meta-KV side-effect; no draw, no return).
        if (input.npcType >= 2) {
            recordGeneralKv(input.generalId, "defence_train", 80)
        }

        // (4) :3745 — do선양 (officer_level==12 && can선양).
        if (input.officerLevel == 12 && generalPolicy.can선양) {
            do선양(null)?.let { return it.copy(reason = "do선양") }
        }

        // (5) :3753 — npcType==5.
        if (input.npcType == 5) {
            if (input.nationId == 0) {
                recordGeneralKv(input.generalId, "killturn", 1) // :3755
                return reservedCommand.copy(reason = "사망") // :3756-3757 (gate-exempt reserved-return)
            }
            // :3759-3764 — do집합 (gate-exempt). PHP throws MustNotBeReachedException on null; the wired
            // body is expected non-null. A null default here would NPE — that mirrors the PHP throw intent.
            val result = do집합(null) ?: throw IllegalStateException("do집합 must not return null (GeneralAI.php:3761)")
            return result.copy(reason = "do집합")
        }

        // (6) :3767 — the RESERVED-HONOR. A non-휴식 reserved command is honored with NO gate, NO log
        // (decision #4). Do NOT add a candidateAllowed/log check here.
        if (reservedCommand.actionCode != REST_COMMAND) {
            return reservedCommand.copy(reason = "do예약턴")
        }

        // (7) :3772 — injury > cureThreshold → che_요양 (gate-exempt direct build).
        if (input.injury > input.cureThreshold) {
            return ChosenCommand("che_요양", emptyMap(), reason = "do요양")
        }

        // (8) :3778 — (npcType 2|3) && nation 0 → do거병.
        if ((input.npcType == 2 || input.npcType == 3) && input.nationId == 0) {
            do거병(null)?.let { return it.copy(reason = "do거병") }
        }

        // (9) :3786 — nation 0 && can국가선택 → do국가선택 then do중립 (the same-branch sibling).
        if (input.nationId == 0 && generalPolicy.can국가선택) {
            do국가선택(null)?.let { return it.copy(reason = "do국가선택") }
            return do중립(null).copy(reason = "do중립") // :3792-3794
        }

        // (10) :3797 — npcType<2 && nation 0 && !can국가선택 → return reserved (재야유저).
        if (input.npcType < 2 && input.nationId == 0 && !generalPolicy.can국가선택) {
            return reservedCommand.copy(reason = "재야유저")
        }

        // (11) :3802 — wandering ruler (npcType>=2 && officer_level==12 && !capital).
        if (input.npcType >= 2 && input.officerLevel == 12 && !input.capital) {
            if (input.relYearMonth > 1) { // :3806
                do건국(null)?.let { return it.copy(reason = "do건국") }
            }
            do방랑군이동(null)?.let { return it.copy(reason = "do방랑군이동") } // :3814
            if (input.relYearMonth > 1) { // :3820
                do해산(null)?.let { return it.copy(reason = "do해산") }
            }
        }

        // (12) :3829 — the PRIORITY LOOP. The merged priority ORDER IS the dispatch/log/draw order.
        for (actionName in generalPolicy.priority) {
            // guard-A :3830 — no `can<name>` property → skip with notice (canFor returns null).
            val canFlag = generalPolicy.canFor(actionName) ?: continue
            // guard-B :3834 — the live `can<name>` flag is false → skip.
            if (!canFlag) continue
            // :3838 — do{X}(); first non-null wins.
            dispatch[actionName]?.invoke(null)?.let { return it.copy(reason = "do$actionName") }
        }

        // (13) :3845 — do중립 TERMINAL fallback (never null).
        return do중립(null).copy(reason = "do중립")
    }

    /**
     * Faithful port of `chooseNationTurn` (PHP `GeneralAI.php:3616-3683`). The LIVE nation decision spine
     * (officer_level>=5; runs BEFORE the general pass in the daemon — R-SEAM §2).
     *
     * The control flow VERBATIM (PHP source-line order — the order IS the draw/log order):
     *  1. `:3618` [updateNationInstance] prologue.
     *  2. `:3621` `$lastTurn = $reservedCommand->getLastTurn()` — here threaded in as [lastTurn], passed into
     *     each `do{X}($lastTurn)` (the nation loop passes the lastTurn; the GENERAL loop passes null).
     *  3. `:3625` [categorizeNationGeneral] THEN `:3626` [categorizeNationCities] (the literal PHP call order;
     *     the by-reference cities-before-generals invariant lives in F-FACADE).
     *  4. `:3628-3648` the npcType>=2 step-1 side-effects:
     *       - `:3630-3632` `use_auto_nation_turn` — if the pre-turn snapshot ([useAutoNationTurn], PHP
     *         `?? 1`) is falsy, reset it to 1 via the [recordGeneralKv] delta (decision #12 / M4).
     *       - `:3633-3647` officer_level==12 → month-gated `choosePromotion`(∈{3,6,9,12}) /
     *         `chooseTexRate`+`chooseGoldBillRate`(month 12) / `chooseTexRate`+`chooseRiceBillRate`(month 6);
     *         ELSE (npcType>=2 non-ruler) `chooseNonLordPromotion`(∈{3,6,9,12}). The promotion hooks MAY draw
     *         (L-RATES); the rate hooks do NOT.
     *  5. `:3650-3659` the GATED reserved-honor (decision #4 — DISTINCT from the general NO-gate path): a
     *     non-휴식 reserved command is gated via [hasFullConditionMet]; on PASS return it (reason 'reserved');
     *     on DENY emit the fail-log `"{getFailString} <1>{nationTurnTimeHm}</>"` ([logFailString]) THEN FALL
     *     THROUGH to the loop. (The deny-log string is the literal PHP `:3657` form; FR3 re-derives it in G-GATE.)
     *  6. `:3661-3679` the 4-guard priority loop over [AutorunNationPolicy.priority]:
     *       - guard-A `:3663` `property_exists($this->nationPolicy,'can'.$name)` false → trigger_error+continue
     *         (here: [AutorunNationPolicy.canFor] returns null → skip; a typo/unknown name drops HERE).
     *       - guard-B `:3667` `!$this->nationPolicy->{'can'.$name}` → continue (canFor returns false).
     *       - guard-C `:3670` `$npcType < 2 && !($availableInstantTurn[$name] ?? false)` → continue (the
     *         npcType<2 restriction to the 12-entry whitelist; npcType>=2 bypasses guard-C).
     *       - `:3674` `do{X}($lastTurn)` — first non-null wins (reason 'do'+name).
     *  7. `:3680-3682` the neutral fallback (PHP `buildNationCommandClass(null,...)`, reason 'neutral').
     */
    fun chooseNationTurn(reservedCommand: ChosenCommand, lastTurn: LastTurn?, input: NationAiInput): ChosenCommand {
        val npcType = input.npcType

        // (1) :3618 — the updateInstance prologue.
        updateNationInstance()

        // (3) :3625/:3626 — categorizeNationGeneral THEN categorizeNationCities (literal PHP call order).
        categorizeNationGeneral()
        categorizeNationCities()

        // (4) :3628-3648 — the npcType>=2 step-1 side-effects.
        if (npcType >= 2) {
            // :3630-3632 — reset use_auto_nation_turn to 1 when the pre-turn snapshot is falsy.
            if (!useAutoNationTurn) {
                recordGeneralKv(nationGeneralId, "use_auto_nation_turn", 1)
            }
            val month = input.month
            if (input.officerLevel == 12) {
                if (month in PROMOTION_MONTHS) {
                    choosePromotion() // :3635 (MAY draw — L-RATES)
                }
                if (month == 12) {
                    chooseTexRate() // :3638 (NO draw)
                    chooseGoldBillRate() // :3639 (NO draw)
                }
                if (month == 6) {
                    chooseTexRate() // :3642 (NO draw)
                    chooseRiceBillRate() // :3643 (NO draw)
                }
            } else if (month in PROMOTION_MONTHS) {
                chooseNonLordPromotion() // :3646 (MAY draw — L-RATES)
            }
        }

        // (5) :3650-3659 — the GATED reserved-honor (DISTINCT from the general NO-gate path).
        if (reservedCommand.actionCode != REST_COMMAND) {
            if (hasFullConditionMet(reservedCommand)) {
                return reservedCommand.copy(reason = "reserved") // :3652-3653
            }
            // :3655-3658 — fail-log "{failString} <1>{date}</>" THEN fall through (decision #4).
            val failString = getFailString(reservedCommand)
            logFailString("$failString <1>$nationTurnTimeHm</>")
        }

        // (6) :3661-3679 — the 4-guard priority loop.
        for (actionName in nationPolicy.priority) {
            // guard-A :3663 — no `can<name>` property → skip with notice.
            val canFlag = nationPolicy.canFor(actionName) ?: continue
            // guard-B :3667 — the live `can<name>` flag is false → skip.
            if (!canFlag) continue
            // guard-C :3670 — npcType<2 restricts to the availableInstantTurn whitelist (`?? false`).
            if (npcType < 2 && actionName !in AutorunNationPolicy.AVAILABLE_INSTANT_TURN) continue
            // :3674 — do{X}($lastTurn); first non-null wins.
            nationDispatch[actionName]?.invoke(lastTurn)?.let { return it.copy(reason = "do$actionName") }
        }

        // (7) :3680-3682 — the neutral fallback (never null).
        return buildNeutralNationCommand().copy(reason = "neutral")
    }

    /**
     * QUARANTINED structural stub of `chooseInstantNationTurn` (PHP `GeneralAI.php:3685-3707`).
     *
     * `// @ParityQuarantine("R-SEAM-no-call-site")` — a repo-wide grep finds ZERO live PHP callers (R-SEAM §3;
     * its own TODO at `:3620` confirms it is an unwired stub). NO PHP golden exercises it, so it CANNOT be
     * captured faithfully → it is EXCLUDED from G-GATE and is NOT wired into F-SEAM (decision #3 / B3).
     * The sibling `chooseNationTurn` provides the byte-match proof.
     *
     * It is DELIBERATELY DIVERGENT from [chooseNationTurn] and must NOT be collapsed with it:
     *  - `:3687-3689` GATE-FIRST: `if($reservedCommand->hasFullConditionMet()) return $reservedCommand;`
     *    BEFORE `updateInstance()` (chooseNationTurn runs updateInstance FIRST then gates with a fall-through).
     *  - `:3691` `updateInstance()` AFTER the gate.
     *  - `:3693-3705` a 2-guard loop (NO guard-C, NO reason strings):
     *      guard-A `:3695` `key_exists($name, $availableInstantTurn)` (NOT `?? false`) — npcType-independent;
     *      guard-B `:3698` `!$this->nationPolicy->{'can'.$name}`;
     *      `:3701` `do{X}($reservedCommand)` — the reserved command threaded BY-REFERENCE (m7) as the do-arg
     *      (NOT the lastTurn), first non-null wins, NO reason assigned.
     *  - `:3706` neutral fallback (`buildNationCommandClass(null,...)`), nullable return.
     *
     * @return PHP `?NationCommand`: the gate-pass reserved command, a do-loop winner, or the neutral build
     *   (never null here since [buildNeutralNationCommand] always builds — PHP can return the build object).
     */
    // @ParityQuarantine("R-SEAM-no-call-site"): zero live PHP callers (R-SEAM §3); off the gate; NOT wired into
    // F-SEAM; deliberately DIVERGENT from chooseNationTurn (gate-FIRST, key_exists, by-ref do-arg, no reasons).
    fun chooseInstantNationTurn(reservedCommand: ChosenCommand, lastTurn: LastTurn?, input: NationAiInput): ChosenCommand? {
        // :3687-3689 — GATE FIRST (BEFORE updateInstance — the divergence from chooseNationTurn).
        if (hasFullConditionMet(reservedCommand)) {
            return reservedCommand
        }

        // :3691 — updateInstance AFTER the gate.
        updateNationInstance()

        // :3693-3705 — the 2-guard loop: key_exists (NOT `?? false`), NO guard-C, NO reason strings.
        for (actionName in nationPolicy.priority) {
            // guard-A :3695 — key_exists($name, availableInstantTurn); npcType-independent.
            if (actionName !in AutorunNationPolicy.AVAILABLE_INSTANT_TURN) continue
            // guard-B :3698 — the live `can<name>` flag. (PHP `!$this->nationPolicy->{'can'.$name}`; a name in
            // availableInstantTurn always has a `can<name>` prop, so canFor never returns null on this path.)
            if (nationPolicy.canFor(actionName) != true) continue
            // :3701 — do{X}($reservedCommand) by-reference; first non-null wins; NO reason assigned (divergence).
            // (The reserved command is the do-arg in PHP; here the do-bodies take a LastTurn? — the lastTurn
            // carrier is threaded, since the stub is OFF the gate the exact do-arg shape is moot — m7.)
            nationDispatch[actionName]?.invoke(lastTurn)?.let { return it }
        }

        // :3706 — the neutral fallback.
        return buildNeutralNationCommand()
    }

    private fun isTruthyMsg(npcmsg: String?): Boolean = !npcmsg.isNullOrEmpty()

    companion object {
        /** The reserved 휴식 command code (PHP `Command\General\휴식`) — the not-honored baseline at :3767. */
        const val REST_COMMAND: String = "휴식"

        /** `in_array($month, [3,6,9,12])` — the quarterly promotion months (`GeneralAI.php:3634/3645`). */
        private val PROMOTION_MONTHS: Set<Int> = setOf(3, 6, 9, 12)
    }
}

/**
 * The AI return value (decision #1): the chosen `(actionCode, RAW args)` + the firing `do<한글>` [reason].
 * The AI emits RAW args; the F-BRIDGE gate canonicalizes once (M2). [reason] is the gate's selection label
 * (`'do'+actionName` for the priority loop; `'do선양'/'사망'/'do집합'/'do예약턴'/'do요양'/'do거병'/'do국가선택'/
 * 'do중립'/'재야유저'/'do건국'/'do방랑군이동'/'do해산'` for the pre-loop/terminal branches). Empty until set.
 */
data class ChosenCommand(
    val actionCode: String,
    val args: Map<String, Any?>,
    val reason: String = "",
)

/**
 * The read-only general scalars [GeneralAI.chooseGeneralTurn] branches on (PHP `$general->getVar(...)` +
 * `$this->nation['capital']`). The engine adapter (F-SEAM) materializes this from the WorldStateViewAdapter;
 * tests build it directly. The SOLE per-decision [rng] (F-SEED [AiSeed.rng]) is threaded by reference.
 *
 * @param generalId PHP `$general->getID()` — the meta-KV delta target (killturn/defence_train).
 * @param npcType PHP `$general->getNPCType()` (`:3712`) — branches 5 / (2|3) / >=2 / <2.
 * @param nationId PHP `$general->getNationID()` (`:3713`) — the 0 (재야/방랑) vs joined branches.
 * @param officerLevel PHP `$general->getVar('officer_level')` — ==12 ruler branches (선양/방랑).
 * @param injury PHP `$general->getVar('injury')` (`:3772`) — > cureThreshold → 요양.
 * @param npcmsg PHP `$general->getVar('npcmsg')` (`:3719`) — the `&&` short-circuit left operand.
 * @param capital PHP `$this->nation['capital']` truthiness (`:3802`) — !capital → wandering branch.
 * @param relYearMonth PHP `joinYearMonth(year,month) - joinYearMonth(init_year,init_month)` (`:3804`) —
 *   >1 gates 건국/해산 in the wandering branch. Supplied pre-computed (the env math is F-INSTANCE/adapter).
 * @param can선양 PHP `$this->generalPolicy->can선양` (`:3745`) — mirrors [AutorunGeneralPolicy.can선양];
 *   kept on the input so the test/adapter can branch without re-reading the policy. (The spine reads the
 *   policy directly; this field documents the gate.)
 * @param can국가선택 likewise mirrors the policy flag (documentation; the spine reads the policy).
 * @param cureThreshold PHP `$this->nationPolicy->cureThreshold` (`:3772`) — the 요양 injury threshold.
 * @param npcMessageProb PHP `GameConst::npcMessageFreqByDay * turnterm / (60*24)` (`:3719`) — the prob the
 *   npcmsg `nextBool` is drawn against (pre-computed; turnterm is env-supplied).
 * @param rng the SOLE per-general-per-decision [RandUtil] (F-SEED), threaded by reference; NEVER re-seeded.
 */
data class GeneralAiInput(
    val generalId: Int = 0,
    val npcType: Int,
    val nationId: Int,
    val officerLevel: Int,
    val injury: Int,
    val npcmsg: String?,
    val capital: Boolean,
    val relYearMonth: Int,
    val can선양: Boolean,
    val can국가선택: Boolean,
    val cureThreshold: Int,
    val npcMessageProb: Double,
    val rng: RandUtil,
)

/**
 * The read-only nation-side scalars [GeneralAI.chooseNationTurn] branches on (PHP `$general->getNPCType()`
 * `:3623`, `$general->getVar('officer_level')` `:3633`, `$this->env['month']` `:3628`). The engine adapter
 * (F-SEAM) materializes this; tests build it directly.
 *
 * NOTE: the nation spine threads the SOLE per-decision [rng] into the promotion hooks (which MAY draw —
 * L-RATES), so it is carried here for the adapter; the spine itself makes NO direct draw (the only nation
 * draws come from `choosePromotion`/`chooseNonLordPromotion`, which the adapter wires with the same rng).
 *
 * @param npcType PHP `$general->getNPCType()` (`:3623`) — gates the whole step-1 block + guard-C.
 * @param officerLevel PHP `$general->getVar('officer_level')` (`:3633`) — ==12 ruler vs non-ruler promotion.
 * @param month PHP `$this->env['month']` (`:3628`) — the quarterly/half-year promotion/rate gates.
 * @param rng the SOLE per-general-per-decision [RandUtil] (F-SEED) — wired into the promotion hooks.
 */
data class NationAiInput(
    val npcType: Int,
    val officerLevel: Int,
    val month: Int,
    val rng: RandUtil,
)
