package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.DomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class TransferTargetOption(val generalId: Int, val name: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class TransferResourceOption(val resource: String, val available: Boolean, val maxAmount: Long,
    val code: String? = null, val reason: String? = null)
data class DonationRecipient(val countyId: Int, val countyName: String, val nationId: Int, val nationName: String)
data class TransferOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val resources: List<TransferResourceOption> = emptyList(),
    val targets: List<TransferTargetOption> = emptyList(),
    val donationRecipient: DonationRecipient? = null)

@Service
class TransferOptionsService(private val reader: DomesticReader,
    private val catalog: InputCatalog = InputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(inputId: String, actorId: Int, userId: Long): TransferOptions {
        reader.requireOwner(actorId, userId)
        if (inputId !in TransferInput.INPUT_IDS) return TransferOptions(inputId, false,
            TransferFailure.INVALID_INPUT.name, TransferFailure.INVALID_INPUT.message)
        if (catalog[inputId]?.deliveryState?.hasHandler != true) return TransferOptions(inputId, false,
            InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val snapshot = reader.snapshot()
        val state = snapshot.state ?: return TransferOptions(inputId, false,
            TransferFailure.STATE_UNAVAILABLE.name, TransferFailure.STATE_UNAVAILABLE.message)
        val actor = state.person(actorId) ?: return TransferOptions(inputId, false,
            TransferFailure.ACTOR_NOT_FOUND.name, TransferFailure.ACTOR_NOT_FOUND.message)
        val stock = try { PortableStock.read(actor.meta, actor.gold, actor.rice) }
            catch (_: IllegalArgumentException) { return TransferOptions(inputId, false,
                TransferFailure.STATE_UNAVAILABLE.name, TransferFailure.STATE_UNAVAILABLE.message) }
        val candidates = if (inputId == TransferInput.GIFT)
            state.people.filter { it.id != actorId && it.node == actor.node }.sortedBy { it.id } else emptyList()
        fun assess(resource: TransferResource, targetId: Int?): TransferAssessment =
            TransferRules.assess(TransferRequest(actorId, inputId, resource, 1, targetId), state)
        val targets = candidates.map { target ->
            val success = listOf(TransferResource.MONEY, TransferResource.GRAIN)
                .any { assess(it, target.id) is TransferAssessment.Eligible }
            val failure = if (success) null else (assess(TransferResource.MONEY, target.id)
                as? TransferAssessment.Rejected)?.reason
            TransferTargetOption(target.id, target.name, success, failure?.name, failure?.message)
        }
        var donationRecipient: DonationRecipient? = null
        val resources = listOf(TransferResource.MONEY, TransferResource.GRAIN).map { resource ->
            val max = when (resource) {
                TransferResource.MONEY -> stock.money
                TransferResource.GRAIN -> stock.grain
                TransferResource.IRON -> stock.iron
                TransferResource.TIMBER -> stock.timber
                TransferResource.HORSES -> stock.horses
            }
            val checks = if (inputId == TransferInput.GIFT)
                candidates.map { assess(resource, it.id) } else listOf(assess(resource, null))
            val success = checks.any { it is TransferAssessment.Eligible }
            val eligible = checks.filterIsInstance<TransferAssessment.Eligible>().firstOrNull()
            val limit = if (inputId != TransferInput.DONATE) max else if (eligible == null) 0L else {
                val county = checkNotNull(eligible.county)
                val nation = checkNotNull(eligible.nation)
                donationRecipient = DonationRecipient(county.id, snapshot.countyNames[county.id] ?: county.name,
                    nation.id, nation.name)
                val current = if (resource == TransferResource.MONEY) eligible.receivedStock.money
                    else eligible.receivedStock.grain
                minOf(max, Int.MAX_VALUE.toLong(), Long.MAX_VALUE - current)
            }
            val failure = if (success) null else (checks.firstOrNull() as? TransferAssessment.Rejected)?.reason
                ?: if (candidates.isEmpty() && inputId == TransferInput.GIFT)
                    TransferFailure.TARGET_UNAVAILABLE else null
            TransferResourceOption(resource.name, success, limit, failure?.name, failure?.message)
        }
        val available = resources.any { it.available }
        val failure = when {
            !available -> resources.firstNotNullOfOrNull { option ->
                if (option.code != null && option.reason != null) option.code to option.reason else null
            }
            else -> null
        }
        return TransferOptions(inputId, available, failure?.first, failure?.second, resources, targets, donationRecipient)
    }
}
