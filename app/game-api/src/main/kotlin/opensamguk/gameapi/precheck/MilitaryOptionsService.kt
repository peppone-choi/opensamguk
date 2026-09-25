package opensamguk.gameapi.precheck

import opensamguk.logic.domestic.FieldRequest
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.FieldAssessment
import opensamguk.logic.domestic.FieldRules

import opensamguk.gameapi.read.DomesticReader
import opensamguk.logic.input.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

data class MilitaryOptions(val inputId: String, val available: Boolean,
    val code: String? = null, val reason: String? = null,
    val countyId: Int? = null, val countyName: String? = null,
    val troops: Int? = null, val training: Int? = null, val morale: Int? = null,
    val gatheringCorps: Int? = null, val troopsAfter: Int? = null, val populationAfter: Int? = null,
    val trainingAfter: Int? = null, val moraleAfter: Int? = null,
    val grainCost: Long? = null, val moneyCost: Long? = null)

@Service
class MilitaryOptionsService(private val reader: DomesticReader,
    private val deploy: DeployPrecheckService,
    private val catalog: InputCatalog = InputCatalog.load(),
    private val design: MilitaryDesign = MilitaryDesign.CANON) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun options(inputId: String, actorId: Int, userId: Long): MilitaryOptions {
        reader.requireOwner(actorId, userId)
        if (inputId !in MilitaryInput.INPUT_IDS) return blocked(inputId, MilitaryFailure.INVALID_INPUT)
        if (inputId == MilitaryInput.MUSTER) {
            val check = deploy.assessMuster(actorId, userId)
            if (check is MusterAssessment.Rejected) return blocked(inputId, check.reason)
            val ready = check as MusterAssessment.Eligible
            return if (design.status == MilitaryDesign.CONFIRMED && catalog[inputId]?.deliveryState?.hasHandler == true)
                MilitaryOptions(inputId, true, gatheringCorps = ready.corps.size)
            else MilitaryOptions(inputId, false, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        }
        val snapshot = reader.snapshot()
        val state = snapshot.state ?: return blocked(inputId, if (snapshot.failure == "WRONG_RULE_PROFILE")
            MilitaryFailure.WRONG_RULE_PROFILE else MilitaryFailure.STATE_UNAVAILABLE)
        val geography = FieldRules.assess(FieldRequest(actorId, FieldInput.FARM), state)
        if (geography is FieldAssessment.Rejected)
            return blocked(inputId, MilitaryFailure.valueOf(geography.reason.name))
        val county = (geography as FieldAssessment.Eligible).county
        val levels = snapshot.countyLevels[county.id]
        val check = MilitaryRules.assessCity(MilitaryRequest(actorId, inputId), state,
            levels?.population, levels?.populationMax, snapshot.cityMilitaryTroops[county.id],
            snapshot.cityMilitaryStates[county.id], snapshot.warehouseStocks[county.id], design)
        if (check is CityMilitaryAssessment.Rejected) return blocked(inputId, check.reason)
        val plan = (check as CityMilitaryAssessment.Eligible).plan
        if (design.status != MilitaryDesign.CONFIRMED || catalog[inputId]?.deliveryState?.hasHandler != true)
            return MilitaryOptions(inputId, false, InputRejection.NOT_DELIVERED.name, InputRejection.NOT_DELIVERED.message)
        return MilitaryOptions(inputId, true, countyId = county.id,
            countyName = snapshot.countyNames[county.id] ?: county.name, troops = snapshot.cityMilitaryTroops[county.id],
            training = snapshot.cityMilitaryStates[county.id]?.training,
            morale = snapshot.cityMilitaryStates[county.id]?.morale,
            troopsAfter = plan.troops, populationAfter = plan.population,
            trainingAfter = plan.condition.training, moraleAfter = plan.condition.morale,
            grainCost = plan.debit.grain, moneyCost = plan.debit.money)
    }

    private fun blocked(inputId: String, reason: MilitaryFailure) =
        MilitaryOptions(inputId, false, reason.name, reason.message)
}
