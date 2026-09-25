package opensamguk.engine.hwiha

import opensamguk.logic.domestic.FieldRequest
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.FieldAssessment
import opensamguk.logic.domestic.FieldRules
import opensamguk.logic.domestic.FieldEconomyAssessment

import opensamguk.logic.domestic.DomesticDesign
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.input.*

/** Autonomous county work uses only the NPC's own current county and the human handler. */
internal class HwihaNpcFieldSelector(private val context: HwihaDomesticContext,
    private val catalog: HwihaInputCatalog = HwihaInputCatalog.load()) {
    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists || !HwihaPersonalTurn.hasNoInput(reserved) ||
            context.design.directActionStatus != DomesticDesign.CONFIRMED) return reserved
        val actor = world.getGeneralById(actorId) ?: return reserved
        if (!HwihaNpcDeploySelector.isUnowned(actor.userId) || actor.nationId <= 0 || actor.npcState < 2 ||
            world.listRetainers().any { it.generalId == actorId }) return reserved
        // An ongoing corps march owns this turn's movement. A new county action would stop it before encounter.
        val deployed = try { HwihaDeploymentState.read(actor.meta)?.corps.orEmpty() }
            catch (_: IllegalArgumentException) { return reserved }
        if (deployed.isNotEmpty() || HwihaCorpsOrder.META_KEY in actor.meta) return reserved
        val state = context.projection(world)
        val available = FieldRules.assess(FieldRequest(actorId, FieldInput.FARM), state)
            as? FieldAssessment.Eligible ?: return reserved
        val city = world.getCityById(available.county.id) ?: return reserved
        val levels = HwihaDomesticCountyEffects.levelsOf(city)
        val stock = try { CountyWarehouse.read(city.meta, city.id)?.stock }
            catch (_: IllegalArgumentException) { null }
        val candidates = FieldInput.INPUT_IDS.toList().mapIndexedNotNull { index, inputId ->
            if (catalog[inputId]?.deliveryState?.hasHandler != true) return@mapIndexedNotNull null
            val economy = FieldRules.assessEconomy(inputId, available.person, city.id, levels, stock, context.design)
                as? FieldEconomyAssessment.Eligible ?: return@mapIndexedNotNull null
            val after = economy.outcome.levels
            fun gap(before: Int, next: Int, max: Int) = if (max <= 0) 0L else (next - before).coerceAtLeast(0).toLong() * 1000 / max
            val score = gap(levels.population, after.population, levels.populationMax) +
                gap(levels.agriculture, after.agriculture, levels.agricultureMax) +
                gap(levels.commerce, after.commerce, levels.commerceMax) +
                gap(levels.security, after.security, levels.securityMax) +
                gap(levels.defence, after.defence, levels.defenceMax) +
                gap(levels.wall, after.wall, levels.wallMax) +
                ((after.trust - levels.trust).coerceAtLeast(0.0) * 10).toLong()
            if (score <= 0) null else Triple(score, index, inputId)
        }
        val selected = candidates.sortedWith(compareByDescending<Triple<Long, Int, String>> { it.first }.thenBy { it.second })
            .firstOrNull()?.third ?: return reserved
        return ReservedTurn(selected, "{}", brief = selected, rowExists = false)
    }
}
