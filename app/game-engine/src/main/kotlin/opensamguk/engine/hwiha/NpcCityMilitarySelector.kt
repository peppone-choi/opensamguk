package opensamguk.engine.hwiha

import opensamguk.logic.domestic.FieldRequest
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.domestic.FieldAssessment
import opensamguk.logic.domestic.FieldRules

import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.infra.persistence.ReservedTurnRepository.ReservedTurn
import opensamguk.logic.economy.CountyWarehouse
import opensamguk.logic.input.*

/** Autonomous lords choose only from their own current county, using the human military precheck. */
internal class HwihaNpcCityMilitarySelector(
    private val context: HwihaDomesticContext,
    private val design: MilitaryDesign = MilitaryDesign.CANON,
    private val catalog: InputCatalog = InputCatalog.load(),
) {
    fun select(world: InMemoryTurnWorld, actorId: Int, reserved: ReservedTurn): ReservedTurn {
        if (world.ruleProfile != RuleProfile.HWIHA || reserved.rowExists || !HwihaPersonalTurn.hasNoInput(reserved) ||
            design.status != MilitaryDesign.CONFIRMED) return reserved
        val actor = world.getGeneralById(actorId) ?: return reserved
        if (!HwihaNpcDeploySelector.isUnowned(actor.userId) || actor.nationId <= 0 || actor.npcState < 2 ||
            world.listRetainers().any { it.generalId == actorId } || CorpsOrder.META_KEY in actor.meta) return reserved
        val deployed = try { DeploymentState.read(actor.meta)?.corps.orEmpty() }
            catch (_: IllegalArgumentException) { return reserved }
        if (deployed.isNotEmpty()) return reserved
        val projection = context.projection(world)
        val geographic = FieldRules.assess(FieldRequest(actorId, FieldInput.FARM), projection)
            as? FieldAssessment.Eligible ?: return reserved
        val city = world.getCityById(geographic.county.id) ?: return reserved
        val state = try { CityMilitaryState.read(city.meta, city.defence.coerceAtLeast(0)) }
            catch (_: IllegalArgumentException) { return reserved }
        val stock = try { CountyWarehouse.read(city.meta, city.id)?.stock }
            catch (_: IllegalArgumentException) { null }
        val preferred = when {
            state.troops < design.npcPolicy.minimumTroops -> listOf(MilitaryInput.CONSCRIPT, MilitaryInput.RAISE_VOLUNTEERS)
            state.training < design.npcPolicy.minimumTraining -> listOf(MilitaryInput.TRAIN)
            state.morale < design.npcPolicy.minimumMorale -> listOf(MilitaryInput.BOOST_MORALE)
            city.population < design.npcPolicy.demobilizeBelowPopulation &&
                state.troops > design.npcPolicy.demobilizeAboveTroops -> listOf(MilitaryInput.DEMOBILIZE)
            else -> emptyList()
        }
        val chosen = preferred.firstOrNull { inputId ->
            catalog[inputId]?.deliveryState?.hasHandler == true &&
                MilitaryRules.assessCity(MilitaryRequest(actorId, inputId), projection, city.population,
                    city.populationMax, state.troops, state, stock, design) is CityMilitaryAssessment.Eligible
        } ?: return reserved
        return ReservedTurn(chosen, "{}", brief = chosen, rowExists = false)
    }
}
