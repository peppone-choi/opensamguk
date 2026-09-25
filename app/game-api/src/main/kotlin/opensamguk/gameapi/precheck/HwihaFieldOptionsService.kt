package opensamguk.gameapi.precheck

import opensamguk.logic.domestic.DomesticDesign
import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class HwihaFieldOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val countyId: Int? = null, val countyName: String? = null)

@Service
class HwihaFieldOptionsService(private val reader: HwihaDomesticReader,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load(),
    private val design: DomesticDesign = DomesticDesign.CANON) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(inputId: String, actorId: Int, ownerUserId: Long): HwihaFieldOptions {
        reader.requireOwner(actorId, ownerUserId)
        if (inputId !in HwihaFieldInput.INPUT_IDS) return blocked(inputId, HwihaFieldFailure.INVALID_INPUT)
        val snapshot = reader.snapshot()
        val state = snapshot.state ?: return blocked(inputId, if (snapshot.failure == "WRONG_RULE_PROFILE")
            HwihaFieldFailure.WRONG_RULE_PROFILE else HwihaFieldFailure.STATE_UNAVAILABLE)
        return when (val check = HwihaFieldRules.assess(HwihaFieldRequest(actorId, inputId), state)) {
            is HwihaFieldAssessment.Rejected -> blocked(inputId, check.reason)
            is HwihaFieldAssessment.Eligible -> {
                if (design.directActionStatus != DomesticDesign.CONFIRMED ||
                    catalog[inputId]?.deliveryState?.hasHandler != true)
                    HwihaFieldOptions(inputId, false, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
                else when (val economy = HwihaFieldRules.assessEconomy(inputId, check.person, check.county.id,
                    snapshot.countyLevels[check.county.id], snapshot.warehouseStocks[check.county.id], design)) {
                    is HwihaFieldEconomyAssessment.Rejected -> blocked(inputId, economy.reason)
                    is HwihaFieldEconomyAssessment.Eligible -> HwihaFieldOptions(inputId, true, countyId = check.county.id,
                        countyName = snapshot.countyNames[check.county.id] ?: check.county.name)
                }
            }
        }
    }

    private fun blocked(inputId: String, failure: HwihaFieldFailure) =
        HwihaFieldOptions(inputId, false, failure.name, failure.message)
}
