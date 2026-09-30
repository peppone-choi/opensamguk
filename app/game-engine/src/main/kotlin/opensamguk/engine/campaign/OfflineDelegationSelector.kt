package opensamguk.engine.campaign

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.domestic.CountyPolicy
import opensamguk.logic.domestic.DomesticDesign
import opensamguk.logic.domestic.DomesticRules
import opensamguk.logic.domestic.FieldAssessment
import opensamguk.logic.domestic.FieldEconomyAssessment
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.FieldRequest
import opensamguk.logic.domestic.FieldRules
import opensamguk.logic.domestic.PolicySource
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.input.InputCatalog
import opensamguk.logic.input.RuleProfile

/** A human's standing county policy may supply only its matching delivered field input. */
internal class OfflineDelegationSelector(
    private val context: DomesticContext,
    private val catalog: InputCatalog = InputCatalog.load(),
) {
    fun select(world: InMemoryTurnWorld, generalId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists || !PersonalTurn.hasNoInput(reserved) ||
            context.design.directActionStatus != DomesticDesign.CONFIRMED) return reserved
        val ownerId = world.getGeneralById(generalId)?.userId?.toIntOrNull()?.takeIf { it > 0 }
            ?: return reserved
        val state = runCatching { context.projection(world) }.getOrNull() ?: return reserved
        val county = (FieldRules.assess(FieldRequest(generalId, FieldInput.FARM), state)
            as? FieldAssessment.Eligible)?.county ?: return reserved
        val effective = runCatching { DomesticRules.effectivePolicy(county, state, context.design) }
            .getOrNull() ?: return reserved
        if (effective.source == PolicySource.DEFAULT) return reserved
        val inputId = when (effective.policy) {
            CountyPolicy.AGRICULTURE -> FieldInput.FARM
            CountyPolicy.COMMERCE -> FieldInput.COMMERCE
            else -> return reserved
        }
        if (catalog[inputId]?.deliveryState?.hasHandler != true) return reserved
        val city = world.getCityById(county.id) ?: return reserved
        val levels = DomesticCountyEffects.levelsOf(city)
        val stock = runCatching { CountyWarehouse.read(city.meta, city.id)?.stock }.getOrNull()
        val person = state.person(generalId) ?: return reserved
        if (FieldRules.assessEconomy(inputId, person, city.id, levels, stock, context.design)
            !is FieldEconomyAssessment.Eligible) return reserved
        return ReservedTurn(inputId, "{}", brief = inputId, rowExists = false,
            reservationOwnerUserId = ownerId)
    }
}
