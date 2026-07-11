package opensamguk.engine.turn

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.domain.General
import opensamguk.logic.items.ItemRegistry
import opensamguk.logic.scenario.ScenarioEffectRegistry
import opensamguk.logic.stats.GeneralActionModuleFactory
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.traits.NationTypeRegistry
import opensamguk.logic.traits.PersonalityRegistry
import opensamguk.logic.traits.SpecialDomesticRegistry
import opensamguk.logic.war.specialty.SpecialWarRegistry

class EngineGeneralActionPipelineBuilder(
    private val world: InMemoryTurnWorld,
    private val startYear: Int,
) {
    private val moduleFactory by lazy {
        GeneralActionModuleFactory(
            nationTypeRegistry = NationTypeRegistry,
            specialDomesticRegistry = SpecialDomesticRegistry,
            personalityRegistry = PersonalityRegistry(),
            itemRegistry = ItemRegistry(relYearProvider = {
                maxOf(0, world.getState().currentYear - startYear)
            }),
            specialWarRegistry = SpecialWarRegistry,
            scenarioEffectRegistry = ScenarioEffectRegistry,
        )
    }

    fun pipelineFor(general: TurnGeneral): GeneralActionPipeline =
        GeneralActionPipeline(modulesFor(general))

    fun pipelineFor(general: General): GeneralActionPipeline =
        GeneralActionPipeline(modulesFor(general))

    fun registryFor(general: TurnGeneral): CommandRegistry =
        CommandRegistry(pipelineFor(general))

    fun registryFor(general: General): CommandRegistry =
        CommandRegistry(pipelineFor(general))

    private fun modulesFor(general: TurnGeneral) = moduleFactory.build(
        general = PerTurnOverlay.toLogicGeneral(general),
        nationTypeCode = world.getNationById(general.nationId)?.typeCode,
        specialDomesticCode = general.role.specialDomestic ?: general.meta["special"] as? String,
        personalityCode = general.role.personality ?: general.meta["personal"] as? String,
        nationLevel = world.getNationById(general.nationId)?.level ?: 0,
        officerCity = (general.meta["officer_city"] as? Number)?.toInt() ?: 0,
        specialWarCode = general.role.specialWar ?: general.meta["special2"] as? String,
        scenarioEffectCode = scenarioEffectCode(),
    )

    private fun modulesFor(general: General) = moduleFactory.build(
        general = general,
        nationTypeCode = world.getNationById(general.nationId)?.typeCode,
        specialDomesticCode = general.meta["special"] as? String,
        personalityCode = general.meta["personal"] as? String,
        nationLevel = world.getNationById(general.nationId)?.level ?: 0,
        officerCity = (general.meta["officer_city"] as? Number)?.toInt() ?: 0,
        specialWarCode = general.meta["special2"] as? String,
        scenarioEffectCode = scenarioEffectCode(),
    )

    private fun scenarioEffectCode(): String? {
        val meta = world.getState().meta
        (meta["scenarioEffect"] as? String)?.let { return it }
        @Suppress("UNCHECKED_CAST")
        val map = meta["map"] as? Map<String, Any?>
        return map?.get("scenarioEffect") as? String
    }
}
