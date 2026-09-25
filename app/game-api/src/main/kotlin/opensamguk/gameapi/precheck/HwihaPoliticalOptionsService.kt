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
    private val catalog: InputCatalog = InputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(actorId: Int, userId: Long): List<HwihaPoliticalOption> {
        reader.requireOwner(actorId, userId)
        val state = reader.snapshot().state
        return PoliticalRules.SUPPORTED_IDS.map { inputId ->
            if (catalog[inputId]?.deliveryState?.hasHandler != true)
                return@map HwihaPoliticalOption(inputId, false,
                    InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
            val targets = if (inputId in PoliticalInput.TARGET_IDS && state != null)
                state.people.filter { it.id != actorId && it.userOwned }.sortedBy { it.id }.map { person ->
                    val assessment = PoliticalRules.assess(PoliticalRequest(actorId, inputId, person.id), state)
                    val failure = (assessment as? PoliticalAssessment.Rejected)?.reason
                    HwihaPoliticalTargetOption(person.id, person.name, failure == null, failure?.name, failure?.message)
                } else emptyList()
            val assessment = if (inputId in PoliticalInput.TARGET_IDS) null
                else state?.let { PoliticalRules.assess(PoliticalRequest(actorId, inputId), it) }
            val failure = when {
                state == null -> PoliticalFailure.STATE_UNAVAILABLE.name to PoliticalFailure.STATE_UNAVAILABLE.message
                inputId in PoliticalInput.TARGET_IDS && targets.none { it.available } ->
                    (targets.firstOrNull()?.code ?: PoliticalFailure.CONSENT_REQUIRED.name) to
                        (targets.firstOrNull()?.reason ?: PoliticalFailure.CONSENT_REQUIRED.message)
                assessment is PoliticalAssessment.Rejected -> assessment.reason.name to assessment.reason.message
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
        val current = try { PoliticalConsent.read(target.meta) } catch (_: IllegalArgumentException) { null }
        return PoliticalInput.TARGET_IDS.flatMap { inputId ->
            state.people.filter { it.id != targetId && it.userOwned }.sortedBy { it.id }.map { issuer ->
                val failure = PoliticalRules.assessConsent(targetId,
                    PoliticalConsent(issuer.id, inputId, true), state)
                HwihaPoliticalConsentOption(inputId, issuer.id, issuer.name, failure == null,
                    current?.takeIf { it.inputId == inputId && it.issuerGeneralId == issuer.id }?.accepted,
                    failure?.name, failure?.message)
            }
        }
    }
}
