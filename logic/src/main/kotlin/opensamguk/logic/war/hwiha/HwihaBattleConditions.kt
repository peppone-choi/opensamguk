package opensamguk.logic.war.hwiha

import java.math.BigInteger
import java.util.Collections
import opensamguk.logic.input.HwihaCorpsEncounter
import opensamguk.logic.input.HwihaEncounterForces

/** Evaluates a completed-round snapshot. Applying actions, card reveals and settlement belong to the caller. */
class HwihaBattleConditions(
    encounter: HwihaCorpsEncounter,
    private val forces: HwihaEncounterForces,
    private val plans: HwihaBattlePlans,
) {
    data class CommandKey(val commanderGeneralId: Int, val slot: Int)
    data class Activation(val commanderGeneralId: Int, val slot: Int, val action: BattlePlanAction)
    class Evaluation(activations: List<Activation>, triggered: Set<CommandKey>) {
        val activations: List<Activation> = Collections.unmodifiableList(ArrayList(activations))
        val triggered: Set<CommandKey> = Collections.unmodifiableSet(LinkedHashSet(triggered))
    }
    private val order = listOf(encounter.attacker.commanderGeneralId) + encounter.defenders.map { it.commanderGeneralId }.sorted()
    private val original = forces.units.associateBy { it.bugokId }
    init { forces.requireBinding(encounter); plans.requireBinding(encounter) }

    fun evaluate(round: Int, units: List<HwihaGridExchange.UnitState>, alreadyTriggered: Set<CommandKey>): Evaluation {
        require(round in 1..HwihaBattlePlans.MAX_ROUNDS)
        require(units.size == original.size && units.map { it.bugokId }.toSet() == original.keys)
        require(units.all { it.troops in 0..original.getValue(it.bugokId).troops && it.morale in 0..100 })
        val known = plans.plans.flatMap { plan -> plan.commands.map { CommandKey(plan.commanderGeneralId,it.slot) } }.toSet()
        require(alreadyTriggered.all { it in known })
        val state = units.associateBy { it.bugokId }
        val activations = mutableListOf<Activation>()
        val triggered = alreadyTriggered.sortedWith(compareBy(CommandKey::commanderGeneralId,CommandKey::slot)).toMutableSet()
        for (commander in order) {
            val plan = plans.plans.single { it.commanderGeneralId == commander }
            val army = forces.units.filter { it.commanderGeneralId == commander }
            val initial = army.fold(BigInteger.ZERO) { n,u -> n+u.troops.toBigInteger() }
            val remaining = army.fold(BigInteger.ZERO) { n,u -> n+state.getValue(u.bugokId).troops.toBigInteger() }
            val morale = army.fold(BigInteger.ZERO) { n,u ->
                val current=state.getValue(u.bugokId)
                n+current.troops.toBigInteger()*current.morale.toBigInteger()
            }
            for (command in plan.commands) {
                val key=CommandKey(commander,command.slot)
                if (key in alreadyTriggered) continue
                val threshold=command.threshold.toBigInteger()
                val matches=when(command.condition) {
                    BattlePlanCondition.LOSS_AT_LEAST -> (initial-remaining)*BigInteger.valueOf(100) >= initial*threshold
                    BattlePlanCondition.MORALE_BELOW -> remaining == BigInteger.ZERO || morale < remaining*threshold
                    BattlePlanCondition.ROUND_AT_LEAST -> round >= command.threshold
                }
                if (matches) {
                    activations.add(Activation(commander,command.slot,command.action))
                    triggered.add(key)
                    break // These actions are mutually exclusive; only the earliest executed slot is consumed.
                }
            }
        }
        return Evaluation(activations,triggered)
    }
}
