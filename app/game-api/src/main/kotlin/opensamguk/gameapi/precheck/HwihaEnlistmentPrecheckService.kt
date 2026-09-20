package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.*
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Read-only committed snapshot; execution repeats the identical pure assessment on current state. */
@Service
class HwihaEnlistmentPrecheckService(
    private val generals: GeneralReadRepository,
    private val nations: NationReadRepository,
    private val retainers: RetainerReadRepository,
    private val worlds: WorldStateReadRepository,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun assess(request: EnlistmentRequest): EnlistmentAssessment {
        val world = worlds.findProcessWorld()
            ?: return EnlistmentAssessment.Rejected(EnlistmentFailure.POLICY_UNAVAILABLE)
        val rawProfile = world.config["ruleProfile"]
        if (rawProfile != null && rawProfile !is String) {
            return EnlistmentAssessment.Rejected(EnlistmentFailure.POLICY_UNAVAILABLE)
        }
        val profile = try { RuleProfile.fromWorldConfig(rawProfile as? String) }
            catch (_: IllegalArgumentException) { return EnlistmentAssessment.Rejected(EnlistmentFailure.POLICY_UNAVAILABLE) }
        if (profile != RuleProfile.HWIHA) return EnlistmentAssessment.Rejected(EnlistmentFailure.WRONG_RULE_PROFILE)
        val state = HwihaEnlistmentProjection(profile,
            generals.findAll().map { general ->
                // Do not use the legacy toLogic conversion: it does not project politics/charm.
                EnlistmentPersonRow(PersonPolicyInput(general.id, general.nationId, general.leadership,
                    general.strength, general.intel, general.politics, general.charm, general.meta),
                    general.name, general.officerLevel, general.npcState, general.userId)
            },
            retainers.findAll().map { EnlistmentCardRow(it.id, it.masterGeneralId, it.generalId, it.name) },
            nations.findAll().map { it.id }.toSet(),
        )
        return HwihaEnlistmentPrecheck.assess(request, state)
    }
}
