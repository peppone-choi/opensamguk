package opensamguk.gameapi.precheck

import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class HwihaTransferTargetOption(val generalId: Int, val name: String, val available: Boolean,
    val code: String? = null, val reason: String? = null)
data class HwihaTransferResourceOption(val resource: String, val available: Boolean, val maxAmount: Long,
    val code: String? = null, val reason: String? = null)
data class HwihaTransferOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val resources: List<HwihaTransferResourceOption> = emptyList(),
    val targets: List<HwihaTransferTargetOption> = emptyList())

@Service
class HwihaTransferOptionsService(private val reader: HwihaDomesticReader,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(inputId: String, actorId: Int, userId: Long): HwihaTransferOptions {
        reader.requireOwner(actorId, userId)
        if (inputId !in HwihaTransferInput.INPUT_IDS) return HwihaTransferOptions(inputId, false,
            HwihaTransferFailure.INVALID_INPUT.name, HwihaTransferFailure.INVALID_INPUT.message)
        if (catalog[inputId]?.deliveryState?.hasHandler != true) return HwihaTransferOptions(inputId, false,
            InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        val state = reader.snapshot().state ?: return HwihaTransferOptions(inputId, false,
            HwihaTransferFailure.STATE_UNAVAILABLE.name, HwihaTransferFailure.STATE_UNAVAILABLE.message)
        val actor = state.person(actorId) ?: return HwihaTransferOptions(inputId, false,
            HwihaTransferFailure.ACTOR_NOT_FOUND.name, HwihaTransferFailure.ACTOR_NOT_FOUND.message)
        val stock = try { HwihaPortableStock.read(actor.meta, actor.gold, actor.rice) }
            catch (_: IllegalArgumentException) { return HwihaTransferOptions(inputId, false,
                HwihaTransferFailure.STATE_UNAVAILABLE.name, HwihaTransferFailure.STATE_UNAVAILABLE.message) }
        val candidates = if (inputId == HwihaTransferInput.GIFT)
            state.people.filter { it.id != actorId && it.node == actor.node }.sortedBy { it.id } else emptyList()
        fun assess(resource: HwihaTransferResource, targetId: Int?): HwihaTransferAssessment =
            HwihaTransferRules.assess(HwihaTransferRequest(actorId, inputId, resource, 1, targetId), state)
        val targets = candidates.map { target ->
            val success = listOf(HwihaTransferResource.MONEY, HwihaTransferResource.GRAIN)
                .any { assess(it, target.id) is HwihaTransferAssessment.Eligible }
            val failure = if (success) null else (assess(HwihaTransferResource.MONEY, target.id)
                as? HwihaTransferAssessment.Rejected)?.reason
            HwihaTransferTargetOption(target.id, target.name, success, failure?.name, failure?.message)
        }
        val resources = listOf(HwihaTransferResource.MONEY, HwihaTransferResource.GRAIN).map { resource ->
            val max = when (resource) {
                HwihaTransferResource.MONEY -> stock.money
                HwihaTransferResource.GRAIN -> stock.grain
                HwihaTransferResource.IRON -> stock.iron
                HwihaTransferResource.TIMBER -> stock.timber
                HwihaTransferResource.HORSES -> stock.horses
            }
            val checks = if (inputId == HwihaTransferInput.GIFT)
                candidates.map { assess(resource, it.id) } else listOf(assess(resource, null))
            val success = checks.any { it is HwihaTransferAssessment.Eligible }
            val failure = if (success) null else (checks.firstOrNull() as? HwihaTransferAssessment.Rejected)?.reason
                ?: if (candidates.isEmpty() && inputId == HwihaTransferInput.GIFT)
                    HwihaTransferFailure.TARGET_UNAVAILABLE else null
            HwihaTransferResourceOption(resource.name, success, max, failure?.name, failure?.message)
        }
        val available = resources.any { it.available }
        val failure = when {
            !available -> resources.firstNotNullOfOrNull { option ->
                if (option.code != null && option.reason != null) option.code to option.reason else null
            }
            else -> null
        }
        return HwihaTransferOptions(inputId, available, failure?.first, failure?.second, resources, targets)
    }
}
