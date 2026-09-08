package opensamguk.logic.war

import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.war.trigger.WarUnitTriggerCaller

/** Immutable inputs; encounter selection and hostility validation belong to the caller. */
data class FieldCombatantInput(
    val general: General,
    val crewType: GameUnitDetail,
    val tech: Int,
    val pipeline: GeneralActionPipeline,
)

/** Working units expose state.snapshot() and battle counters for the caller's delta recorder. */
data class ProcessFieldWarResult(
    val attacker: WarUnitGeneral,
    val defenders: List<WarUnitGeneral>,
    val outcome: FieldWarOutcome,
    /** Exact original rows for uncontacted units; snapshot() normalizes absent dex metadata. */
    val attackerAfter: General,
    val defendersAfter: List<General>,
)

/**
 * General-versus-general encounter. Defender order is explicitly supplied by the caller.
 * No city is constructed, no city bonuses are applied, and no city/nation settlement occurs.
 * Empty encounters return unchanged combatants without running finishBattle or drawing RNG.
 */
fun processFieldWar(
    warSeed: String,
    attacker: FieldCombatantInput,
    defenders: List<FieldCombatantInput>,
    hooks: WarBattleHooks = FieldWarBattleHooks,
): ProcessFieldWarResult {
    val ids = listOf(attacker.general.id) + defenders.map { it.general.id }
    require(ids.all { it > 0 } && ids.distinct().size == ids.size) { "Field combatants must have unique positive IDs" }
    require((listOf(attacker) + defenders).all { it.general.crew > 0 }) { "Field combatants require living troops" }
    val rng = RandUtil(LiteHashDrbg(warSeed))
    fun build(input: FieldCombatantInput, isAttacker: Boolean) = WarUnitGeneral(
        rng, WarUnitGeneralState(input.general), input.pipeline, input.crewType, input.tech,
        isAttacker, cityLevel = 0, isCapital = false,
    )
    val attackerUnit = build(attacker, true)
    val defenderUnits = defenders.map { build(it, false) }
    val iterator = defenderUnits.iterator()
    val outcome = processFieldWarNG(rng, attackerUnit, { _, next ->
        if (next && iterator.hasNext()) iterator.next() else null
    }, hooks)
    return ProcessFieldWarResult(
        attackerUnit, defenderUnits, outcome,
        if (outcome.contact) attackerUnit.state.snapshot() else attacker.general,
        defenderUnits.zip(defenders).map { (unit, input) ->
            if (unit.getPhase() > 0) unit.state.snapshot() else input.general
        },
    )
}

/** The existing general trigger pipeline and training effects, without city-only hooks. */
object FieldWarBattleHooks : WarBattleHooks {
    override fun battleInitCaller(unit: WarUnit): WarUnitTriggerCaller? =
        (unit as? WarUnitGeneral)?.battleInitCaller()

    override fun battlePhaseCaller(unit: WarUnit): WarUnitTriggerCaller? =
        (unit as? WarUnitGeneral)?.battlePhaseCaller()

    override fun addTrain(unit: WarUnit, amount: Int) {
        (unit as? WarUnitGeneral)?.addTrain(amount)
    }

    override fun addLevelExp(unit: WarUnitGeneral, value: Double) {
        unit.addLevelExpBonus(value)
    }
}
