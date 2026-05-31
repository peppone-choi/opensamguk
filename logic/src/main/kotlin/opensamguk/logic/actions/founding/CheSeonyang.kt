package opensamguk.logic.actions.founding

import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.beLord
import opensamguk.logic.constraints.existsDestGeneral
import opensamguk.logic.constraints.friendlyDestGeneral
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.withMeta
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_선양 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_선양.php`.
 *
 * Abdicate the throne to a friendly general (the lord's command), AI-emitted by do선양
 * (GeneralAI.php:3323).
 *
 * **ORDER BY RAND quarantine (G4, decision #6):** `do선양` picks the heir with a MySQL-side
 * `ORDER BY RAND()` (`GeneralAI.php:3324`) — NOT on the DRBG stream (0 draws consumed → cursor
 * unaffected). It is QUARANTINED with a deterministic `min(no)` substitute over the same WHERE-filtered
 * set, and is unreachable in scenario_1010 (R-GATE §1: 0 npc==5; can선양 requires npc==5). The [orderByRandQuarantine]
 * flag marks this for the F-QUAR backlog entry; the SELECTION substitute lives in the do선양 family (L-GENFOUND).
 *
 * argTest (che_선양.php:33-56): require `destGeneralID` (int, > 0, != selfId) → canonical
 * `{destGeneralID}` (the dest general need NOT exist — `사망 직전에 '선양' 턴을 넣을 수 있으므로`).
 *
 * fullConditionConstraints (che_선양.php:75-79, computed in initWithArg), in PHP ORDER:
 *   [BeLord, ExistsDestGeneral, FriendlyDestGeneral]
 * (minConditionConstraints is [BeLord]).
 *
 * run() (che_선양.php:104-156): a penalty-key guard (NoChief/NoFoundNation/NoAmbassador on the heir →
 * abort with a log), then the officer_level swap (heir → 12, actor → 1), `experience *= 0.7`, and the
 * 선양 logs.
 */
class CheSeonyang(@Suppress("UNUSED_PARAMETER") private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
    override val key: String get() = "che_선양"
    override val name: String get() = "선양"
    override val category: String get() = "국가"

    /** G4 — do선양's heir pick uses MySQL ORDER BY RAND(); F-QUAR backlog marker (decision #6). */
    val orderByRandQuarantine: Boolean get() = true

    /**
     * che_선양.php:33-56 argTest. Returns `{destGeneralID}` or null on invalid. `selfId` is the actor
     * general id (PHP `$this->generalObj->getID()`) — the self-target rejection.
     */
    fun argTest(raw: Map<String, Any?>, selfId: Int): Map<String, Any?>? {
        if (!raw.containsKey("destGeneralID")) return null
        val destGeneralID = (raw["destGeneralID"] as? Int) ?: return null  // PHP is_int
        if (destGeneralID <= 0) return null
        if (destGeneralID == selfId) return null
        return linkedMapOf("destGeneralID" to destGeneralID)
    }

    override fun buildMinConstraints(ctx: ConstraintContext): List<Constraint> = listOf(beLord())

    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(
        beLord(),
        existsDestGeneral(),
        friendlyDestGeneral(),
    )

    /** Reservation-time normalize: the self-reject needs the running actor id (selfId=-1 inert). */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw, selfId = -1) ?: emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val dest = d.destGeneral ?: return
        val destName = (dest.meta["name"] as? String) ?: context.destGeneralName
        // heir → lord (officer_level 12, officer_city 0); actor → 1급 (officer_level 1, officer_city 0).
        d.destGeneral = dest.copy(officerLevel = 12, meta = withMeta(dest.meta, "officer_city" to 0))
        context.addLog("<Y>$destName</>에게 군주의 자리를 물려줍니다. <1>${context.date}</>")
        val g0 = d.general
        // experience *= 0.7 (che_선양.php:137). The global/national HISTORY-log strings are a downstream
        // (history-bucket) seam; 선양 is gate-quarantined (G4, unreachable in 1010).
        d.general = g0.copy(
            officerLevel = 1,
            experience = g0.experience * 0.7,
            lastTurn = LastTurn(name),
            meta = withMeta(g0.meta, "officer_city" to 0),
        )
    }
}
