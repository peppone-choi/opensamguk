package opensamguk.logic.ai

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.LastTurn

/**
 * F-DISPATCH — `GeneralAI`, a faithful port of the two LIVE dispatcher spines of
 * `legacy/devsam-core/hwe/sammo/GeneralAI.php` (GRAND TRUTH, read in full).
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

    private fun isTruthyMsg(npcmsg: String?): Boolean = !npcmsg.isNullOrEmpty()

    companion object {
        /** The reserved 휴식 command code (PHP `Command\General\휴식`) — the not-honored baseline at :3767. */
        const val REST_COMMAND: String = "휴식"
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
