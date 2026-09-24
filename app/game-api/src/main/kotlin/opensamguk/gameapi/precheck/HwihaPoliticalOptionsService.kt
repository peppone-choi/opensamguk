package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class HwihaPoliticalTargetOption(val generalId: Int, val name: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class HwihaPoliticalOption(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val targets: List<HwihaPoliticalTargetOption> = emptyList())
data class HwihaPoliticalConsentOption(val inputId: String, val issuerGeneralId: Int, val issuerName: String,
    val available: Boolean, val accepted: Boolean?, val code: String? = null, val reason: String? = null)

@Service
class HwihaPoliticalOptionsService(private val reader: HwihaDomesticReader,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(actorId: Int, userId: Long): List<HwihaPoliticalOption> {
        reader.requireOwner(actorId, userId)
        val state = reader.snapshot().state
        return HwihaPoliticalRules.SUPPORTED_IDS.map { inputId ->
            if (catalog[inputId]?.deliveryState?.hasHandler != true)
                return@map HwihaPoliticalOption(inputId, false,
                    InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
            val targets = if (inputId in HwihaPoliticalInput.TARGET_IDS && state != null)
                state.people.filter { it.id != actorId && it.userOwned }.sortedBy { it.id }.map { person ->
                    val assessment = HwihaPoliticalRules.assess(HwihaPoliticalRequest(actorId, inputId, person.id), state)
                    val failure = (assessment as? HwihaPoliticalAssessment.Rejected)?.reason
                    HwihaPoliticalTargetOption(person.id, person.name, failure == null, failure?.name, failure?.message)
                } else emptyList()
            val assessment = if (inputId in HwihaPoliticalInput.TARGET_IDS) null
                else state?.let { HwihaPoliticalRules.assess(HwihaPoliticalRequest(actorId, inputId), it) }
            val failure = when {
                state == null -> HwihaPoliticalFailure.STATE_UNAVAILABLE.name to HwihaPoliticalFailure.STATE_UNAVAILABLE.message
                inputId in HwihaPoliticalInput.TARGET_IDS && targets.none { it.available } ->
                    (targets.firstOrNull()?.code ?: HwihaPoliticalFailure.CONSENT_REQUIRED.name) to
                        (targets.firstOrNull()?.reason ?: HwihaPoliticalFailure.CONSENT_REQUIRED.message)
                assessment is HwihaPoliticalAssessment.Rejected -> assessment.reason.name to assessment.reason.message
                else -> null
            }
            HwihaPoliticalOption(inputId, failure == null, failure?.first, failure?.second, targets)
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun consentOptions(targetId: Int, userId: Long): List<HwihaPoliticalConsentOption> {
        reader.requireOwner(targetId, userId)
        val state = reader.snapshot().state ?: return emptyList()
        val target = state.person(targetId) ?: return emptyList()
        val current = try { HwihaPoliticalConsent.read(target.meta) } catch (_: IllegalArgumentException) { null }
        return HwihaPoliticalInput.TARGET_IDS.flatMap { inputId ->
            state.people.filter { it.id != targetId && it.userOwned }.sortedBy { it.id }.map { issuer ->
                val failure = HwihaPoliticalRules.assessConsent(targetId,
                    HwihaPoliticalConsent(issuer.id, inputId, true), state)
                HwihaPoliticalConsentOption(inputId, issuer.id, issuer.name, failure == null,
                    current?.takeIf { it.inputId == inputId && it.issuerGeneralId == issuer.id }?.accepted,
                    failure?.name, failure?.message)
            }
        }
    }
}
