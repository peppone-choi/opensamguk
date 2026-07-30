package opensamguk.logic.actions.military

import opensamguk.common.constants.CityConst
import opensamguk.common.josa.JosaUtil
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_NPC능동 — faithful port of `legacy/devsam-core/hwe/sammo/Command/General/che_NPC능동.php`.
 *
 * The NPC-only 순간이동 (warp) command, AI-emitted by do후방워프/do전방워프/do내정워프 (GeneralAI.php:
 * 2958/3009/3083). All three emit `che_NPC능동` with `{optionText:'순간이동', destCityID}`.
 *
 * argTest (che_NPC능동.php:20-43): require `optionText`; ONLY `'순간이동'` is supported → also require
 * `destCityID` resolving to a real `CityConst::byID(...)` → canonical `{optionText, destCityID}`; any
 * other optionText (or a missing/unknown city) → false.
 *
 * Constraints (che_NPC능동.php:45-58): `permissionConstraints = [MustBeNPC()]` (the SEPARATE reserve-
 * time gate, BaseCommand.php:450-451 — NOT part of hasFullConditionMet) and **EMPTY**
 * `fullConditionConstraints`. So [buildConstraints] (the full-condition gate the AI bridge evaluates)
 * is EMPTY → the AI gate passes on argValid alone (R-BRIDGE: gate ≈ argValid). The MustBeNPC
 * permission gate is enforced upstream by the dispatcher (the warp methods only fire for NPC actors).
 *
 * run() (che_NPC능동.php:90-99): move general.city → destCityID + the action log; no exp/ded/lottery.
 */
class CheNpcNeungdong(@Suppress("UNUSED_PARAMETER") private val pipeline: GeneralActionPipeline) : GeneralActionDefinition {
    override val key: String get() = "che_NPC능동"
    override val name: String get() = "NPC능동"
    override val category: String get() = "군사"
    override val argsSchema: Map<String, Any?> get() = linkedMapOf("optionText" to "string", "destCityID" to "int")

    /** che_NPC능동.php:20-43 argTest. Returns `{optionText, destCityID}` for a valid 순간이동, else null. */
    fun argTest(raw: Map<String, Any?>): Map<String, Any?>? {
        if (!raw.containsKey("optionText")) return null
        // PHP: only optionText=='순간이동' is supported; any other value falls through to `return false`.
        if (raw["optionText"] != "순간이동") return null
        if (!raw.containsKey("destCityID")) return null
        val destCityID = (raw["destCityID"] as? Number)?.toInt() ?: return null
        if (CityConst.byId(destCityID) == null) return null
        return linkedMapOf("optionText" to "순간이동", "destCityID" to destCityID)
    }

    /** EMPTY fullConditionConstraints (che_NPC능동.php:55-57) — MustBeNPC is permission-only. */
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = emptyList()

    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = argTest(raw) ?: emptyMap()

    override fun resolve(context: GeneralActionResolveContext) {
        val destCityId = (context.args["destCityID"] as? Number)?.toInt() ?: return
        val cityName = CityConst.byId(destCityId)?.name ?: return
        val josaRo = JosaUtil.pick(cityName, "로")
        context.addLog("NPC 전용 명령을 이용해 $cityName$josaRo 이동했습니다.")
        context.draft.general = context.draft.general.copy(
            cityId = destCityId,
            lastTurn = LastTurn(command = name, arg = context.args),
        )
    }
}
