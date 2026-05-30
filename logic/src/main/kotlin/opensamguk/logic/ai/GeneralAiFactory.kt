package opensamguk.logic.ai

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.LastTurn

/**
 * P5 Wire step — the SINGLE assembly seam that lifts the 7 PURE leaf families (the `ai/families` package) into a
 * live [GeneralAI]. The factory is the ONLY place that touches the [GeneralAI] ctor; a family file stays
 * PURE `:logic` and NEVER edits [GeneralAI], the policies, [candidateAllowed], the registry, or the engine
 * adapter (the shared-extension protocol — m12). A family supplies its `do<한글>` bodies through a
 * [GeneralAiDoBodies] bundle, and THIS factory registers them:
 *  - the **general-side** do-bodies into the priority-loop dispatch `LinkedHashMap`, keyed by ACTION-NAME
 *    in the policy/catalog order (the loop walks [AutorunGeneralPolicy.priority]; an unregistered name is a
 *    null no-op → the loop skips it — exactly the PHP `do{X}()` first-non-null behavior);
 *  - the **8 pre-loop ctor lambdas** (do선양 / do집합 / do거병 / do국가선택 / do방랑군이동 / do건국 / do해산 /
 *    do중립) from the same bundle (the GenFoundFamily / GenWarMoveFamily branch bodies);
 *  - the **nation-side** do-bodies into the nation dispatch `LinkedHashMap` (keyed by action-name in
 *    [AutorunNationPolicy.priority] order) + the nation hooks (the gated reserved-honor / fail-log /
 *    promotion+rate side-effects / neutral fallback) used by [GeneralAI.chooseNationTurn].
 *
 * **READ-ONLY over GAME ENTITIES (decision #12 / M4).** The factory wires the meta-KV side-effects
 * (killturn / defence_train / use_auto_nation_turn / …) through the supplied [GeneralAiDoBodies.recordGeneralKv]
 * delta sink — NOT an inline entity write, NOT a module-static Map. It NEVER mutates a general/city/nation
 * row; the chosen command's resolve runs the EXISTING P2-P4 [opensamguk.logic.actions] delta path downstream.
 * The factory itself draws NO randomness and invokes NO do-body at construction (it only stores the lambdas).
 *
 * PURE `:logic` — no Spring, no DB. The engine adapter (`AiTurnAdapter`, F-SEAM) builds the per-general
 * `"GeneralAI"` rng + [AiInstanceState] + [AiWorldView] + [candidateAllowed] gate and hands them to [build].
 */
object GeneralAiFactory {

    /**
     * Construct a fully-wired [GeneralAI] for ONE general's decision from the per-general AI context.
     *
     * @param generalPolicy the merged general policy (F-POLICY) — its [AutorunGeneralPolicy.priority] ORDER
     *   is the general dispatch/log/draw spine; its `can<name>` flags gate the loop.
     * @param bodies the leaf families' `do<한글>` bodies + the spine hooks (the assembly seam). An absent
     *   body is a null no-op (the catalog allows it — "a no-registered action = null no-op is fine").
     * @param nationPolicy the merged nation policy (F-POLICY) — its [AutorunNationPolicy.priority] ORDER is
     *   the nation dispatch spine. Defaults to a benign npcType=2 policy for the general-only call sites.
     * @param updateInstance the `:3715` prologue hook (calcGenType's FIRST draw lives there, F-INSTANCE).
     * @param nationHooks the nation-pass prologue/derive/promotion hooks ([NationPassHooks]); defaults are
     *   inert (no draw, no side-effect) so a general-only construction stays self-contained + unit-testable.
     */
    fun build(
        generalPolicy: AutorunGeneralPolicy,
        bodies: GeneralAiDoBodies,
        nationPolicy: AutorunNationPolicy = AutorunNationPolicy(npcType = 2, tech = 0, develcost = 0),
        updateInstance: (RandUtil) -> Unit = {},
        nationHooks: NationPassHooks = NationPassHooks(),
    ): GeneralAI {
        // The general-side priority-loop dispatch (keyed by ACTION-NAME; the loop walks the policy order).
        val dispatch = LinkedHashMap<String, (LastTurn?) -> ChosenCommand?>()
        for ((actionName, body) in bodies.generalDispatch) {
            dispatch[actionName] = body
        }
        // The nation-side priority-loop dispatch (keyed by ACTION-NAME; the loop walks the nation order).
        val nationDispatch = LinkedHashMap<String, (LastTurn?) -> ChosenCommand?>()
        for ((actionName, body) in bodies.nationDispatch) {
            nationDispatch[actionName] = body
        }

        return GeneralAI(
            generalPolicy = generalPolicy,
            dispatch = dispatch,
            updateInstance = updateInstance,
            candidateAllowed = bodies.candidateAllowed,
            recordGeneralKv = bodies.recordGeneralKv,
            logFailString = nationHooks.logFailString,
            // the 8 pre-loop / terminal general branches (GenFoundFamily + GenWarMoveFamily bodies).
            do선양 = bodies.do선양,
            do집합 = bodies.do집합,
            do거병 = bodies.do거병,
            do국가선택 = bodies.do국가선택,
            do방랑군이동 = bodies.do방랑군이동,
            do건국 = bodies.do건국,
            do해산 = bodies.do해산,
            do중립 = bodies.do중립,
            // the nation spine (FD2).
            nationPolicy = nationPolicy,
            nationDispatch = nationDispatch,
            updateNationInstance = nationHooks.updateNationInstance,
            categorizeNationGeneral = nationHooks.categorizeNationGeneral,
            categorizeNationCities = nationHooks.categorizeNationCities,
            choosePromotion = nationHooks.choosePromotion,
            chooseNonLordPromotion = nationHooks.chooseNonLordPromotion,
            chooseTexRate = nationHooks.chooseTexRate,
            chooseGoldBillRate = nationHooks.chooseGoldBillRate,
            chooseRiceBillRate = nationHooks.chooseRiceBillRate,
            hasFullConditionMet = bodies.hasFullConditionMet,
            getFailString = bodies.getFailString,
            buildNeutralNationCommand = bodies.buildNeutralNationCommand,
            nationGeneralId = nationHooks.nationGeneralId,
            useAutoNationTurn = nationHooks.useAutoNationTurn,
            nationTurnTimeHm = nationHooks.nationTurnTimeHm,
        )
    }
}

/**
 * The leaf families' `do<한글>` bodies + the cross-cutting spine hooks the factory wires into [GeneralAI]
 * (the assembly seam). Every field defaults to an inert no-op (a null-returning body / a pass-through gate)
 * so a partial wiring (a single representative family in a smoke test, or the production set as families
 * land) constructs without ceremony. An absent dispatch entry is a null no-op — the priority loop simply
 * skips that action name (the catalog-sanctioned behavior).
 *
 * The bodies are PURE `(LastTurn?) -> ChosenCommand?` closures the families build over their primitives +
 * the per-general AI context (rng / instance / worldView / bridge). They are READ-ONLY over GAME ENTITIES;
 * any meta-KV side-effect routes through [recordGeneralKv] (decision #12 / M4).
 *
 * @param generalDispatch the general priority-loop bodies, keyed by ACTION-NAME (the bare `do<한글>` name,
 *   e.g. "일반내정" / "출병" / "징병" — NOT the `che_*` code). The factory registers them in this map; the
 *   loop walks [AutorunGeneralPolicy.priority] and invokes `dispatch[name]` first-non-null.
 * @param nationDispatch the nation priority-loop bodies, keyed by ACTION-NAME (e.g. "선전포고" / "포상" via
 *   its 발령 family name). The loop walks [AutorunNationPolicy.priority].
 * @param do선양 / do집합 / do거병 / do국가선택 / do방랑군이동 / do건국 / do해산 the pre-loop branch bodies
 *   ([GeneralAI.chooseGeneralTurn] steps 4/5/8/9/11). [do중립] the TERMINAL fallback (never null — its
 *   default builds `che_중립`).
 * @param candidateAllowed the F-BRIDGE gate `(actionCode, rawArgs) -> Boolean` the do-bodies consult
 *   (except the gate-exempt do집합/do요양/reserved-returns — [isGateExempt]). The general reserved-honor
 *   path does NOT consult it (decision #4).
 * @param recordGeneralKv the meta-KV delta sink `(generalId, key, value) -> Unit` (decision #12).
 * @param hasFullConditionMet the nation reserved-honor gate `(reserved) -> Boolean` (decision #4).
 * @param getFailString the nation deny fail-string `(reserved) -> String` (`GeneralAI.php:3656`).
 * @param buildNeutralNationCommand the nation neutral fallback builder (`:3680`).
 */
data class GeneralAiDoBodies(
    val generalDispatch: Map<String, (LastTurn?) -> ChosenCommand?> = emptyMap(),
    val nationDispatch: Map<String, (LastTurn?) -> ChosenCommand?> = emptyMap(),
    val do선양: (LastTurn?) -> ChosenCommand? = { null },
    val do집합: (LastTurn?) -> ChosenCommand? = { null },
    val do거병: (LastTurn?) -> ChosenCommand? = { null },
    val do국가선택: (LastTurn?) -> ChosenCommand? = { null },
    val do방랑군이동: (LastTurn?) -> ChosenCommand? = { null },
    val do건국: (LastTurn?) -> ChosenCommand? = { null },
    val do해산: (LastTurn?) -> ChosenCommand? = { null },
    val do중립: (LastTurn?) -> ChosenCommand = { ChosenCommand("che_중립", emptyMap()) },
    val candidateAllowed: (actionCode: String, rawArgs: Map<String, Any?>) -> Boolean = { _, _ -> true },
    val recordGeneralKv: (generalId: Int, key: String, value: Any?) -> Unit = { _, _, _ -> },
    val hasFullConditionMet: (reserved: ChosenCommand) -> Boolean = { true },
    val getFailString: (reserved: ChosenCommand) -> String = { "" },
    val buildNeutralNationCommand: () -> ChosenCommand = { ChosenCommand("che_휴식", emptyMap()) },
)

/**
 * The nation-pass prologue/derive/promotion hooks the factory wires into [GeneralAI.chooseNationTurn]
 * (PHP `GeneralAI.php:3616-3683`). Defaults are inert so a general-only construction (the common case)
 * needs no nation context. The promotion hooks MAY draw (L-RATES); the rate hooks do NOT; the derive
 * hooks are the F-FACADE categorize calls (cities-before-generals invariant lives in F-FACADE).
 *
 * @param logFailString the deny fail-log sink (`:3657`).
 * @param updateNationInstance the `:3618` prologue.
 * @param categorizeNationGeneral / categorizeNationCities the `:3625` / `:3626` derive hooks.
 * @param choosePromotion / chooseNonLordPromotion the npcType>=2 step-1 promotion hooks (MAY draw).
 * @param chooseTexRate / chooseGoldBillRate / chooseRiceBillRate the rate hooks (NO draw).
 * @param nationGeneralId the meta-KV delta target for `use_auto_nation_turn` (`:3631`).
 * @param useAutoNationTurn the pre-turn `getAuxVar('use_auto_nation_turn') ?? 1` snapshot (`:3630`).
 * @param nationTurnTimeHm the `getTurnTime(TURNTIME_HM)` stamp for the fail-log (`:3655`).
 */
data class NationPassHooks(
    val logFailString: (String) -> Unit = {},
    val updateNationInstance: () -> Unit = {},
    val categorizeNationGeneral: () -> Unit = {},
    val categorizeNationCities: () -> Unit = {},
    val choosePromotion: () -> Unit = {},
    val chooseNonLordPromotion: () -> Unit = {},
    val chooseTexRate: () -> Unit = {},
    val chooseGoldBillRate: () -> Unit = {},
    val chooseRiceBillRate: () -> Unit = {},
    val nationGeneralId: Int = 0,
    val useAutoNationTurn: Boolean = true,
    val nationTurnTimeHm: String = "",
)
