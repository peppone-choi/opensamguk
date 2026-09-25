package opensamguk.gameapi.precheck

import opensamguk.logic.domestic.FieldRequest
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.FieldFailure
import opensamguk.logic.domestic.FieldAssessment
import opensamguk.logic.domestic.FieldRules
import opensamguk.logic.domestic.FieldEconomyAssessment

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
    private val catalog: InputCatalog = InputCatalog.load(),
    private val design: DomesticDesign = DomesticDesign.CANON) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(inputId: String, actorId: Int, ownerUserId: Long): HwihaFieldOptions {
        reader.requireOwner(actorId, ownerUserId)
        if (inputId !in FieldInput.INPUT_IDS) return blocked(inputId, FieldFailure.INVALID_INPUT)
        val snapshot = reader.snapshot()
        val state = snapshot.state ?: return blocked(inputId, if (snapshot.failure == "WRONG_RULE_PROFILE")
            FieldFailure.WRONG_RULE_PROFILE else FieldFailure.STATE_UNAVAILABLE)
        return when (val check = FieldRules.assess(FieldRequest(actorId, inputId), state)) {
            is FieldAssessment.Rejected -> blocked(inputId, check.reason)
            is FieldAssessment.Eligible -> {
                if (design.directActionStatus != DomesticDesign.CONFIRMED ||
                    catalog[inputId]?.deliveryState?.hasHandler != true)
                    HwihaFieldOptions(inputId, false, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
                else when (val economy = FieldRules.assessEconomy(inputId, check.person, check.county.id,
                    snapshot.countyLevels[check.county.id], snapshot.warehouseStocks[check.county.id], design)) {
                    is FieldEconomyAssessment.Rejected -> blocked(inputId, economy.reason)
                    is FieldEconomyAssessment.Eligible -> HwihaFieldOptions(inputId, true, countyId = check.county.id,
                        countyName = snapshot.countyNames[check.county.id] ?: check.county.name)
                }
            }
        }
    }

    private fun blocked(inputId: String, failure: FieldFailure) =
        HwihaFieldOptions(inputId, false, failure.name, failure.message)
}
