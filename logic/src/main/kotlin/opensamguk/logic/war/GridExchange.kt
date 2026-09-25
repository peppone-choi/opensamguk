package opensamguk.logic.war

import java.math.BigInteger
import java.util.Collections
import opensamguk.logic.input.*
import opensamguk.logic.world.BattlefieldGeometry.Position

/** One simultaneous exchange of explicit attacks against an immutable encounter context. No live writes. */
class GridExchange(
    encounter: CorpsEncounter,
    private val forces: EncounterForces,
    private val relations: EncounterRelations,
    combat: EncounterCombatProfiles,
    private val deployment: EncounterDeployment,
) {
    data class UnitState(val bugokId: Int, val troops: Int, val morale: Int, val fatigue: Int, val position: Position?)
    data class AttackIntent(val attackerId: Int, val targetId: Int)
    enum class Outcome { STRUCK, INACTIVE, RESERVE, NOT_HOSTILE, OUT_OF_REACH }
    /** Potential casualties are independent attacks, not attribution of overkill losses. */
    data class AttackResult(val attackerId: Int, val targetId: Int, val outcome: Outcome, val potentialCasualties: Int)
    class Result(units: List<UnitState>, attacks: List<AttackResult>) {
        val units: List<UnitState> = Collections.unmodifiableList(ArrayList(units))
        val attacks: List<AttackResult> = Collections.unmodifiableList(ArrayList(attacks))
    }
    private val original = forces.units.associateBy { it.bugokId }
    private val commanders = forces.commanders.associateBy { it.generalId }
    private val profiles = combat.profiles.associateBy { it.crewTypeId }
    val initialUnits: List<UnitState>
    init {
        forces.requireBinding(encounter)
        relations.requireBinding(encounter)
        require(combat.ready && combat.encounterId == encounter.encounterId &&
            combat.forcesSnapshotId == forces.snapshotId && deployment.encounterId == encounter.encounterId)
        require(profiles.keys == forces.units.map { it.crewTypeId }.toSet())
        require(deployment.tokens.map { it.bugokId }.toSet() == original.keys)
        require(deployment.tokens.all { original.getValue(it.bugokId).commanderGeneralId == it.commanderGeneralId })
        val positions = deployment.tokens.associate { it.bugokId to it.position }
        initialUnits = Collections.unmodifiableList(forces.units.map {
            UnitState(it.bugokId, it.troops, it.morale, it.fatigue, positions.getValue(it.bugokId))
        })
    }

    fun resolve(units: List<UnitState>, intents: List<AttackIntent>): Result {
        validate(units, intents)
        val state = units.associateBy { it.bugokId }
        val attacks = intents.sortedBy { it.attackerId }.map { intent ->
            val attacker = state.getValue(intent.attackerId)
            val target = state.getValue(intent.targetId)
            val attackerForce = original.getValue(attacker.bugokId)
            val targetForce = original.getValue(target.bugokId)
            val outcome = when {
                attacker.troops == 0 || attacker.morale == 0 || target.troops == 0 -> Outcome.INACTIVE
                attacker.position == null || target.position == null -> Outcome.RESERVE
                attackerForce.commanderGeneralId == targetForce.commanderGeneralId ||
                    !relations.isHostile(attackerForce.commanderGeneralId, targetForce.commanderGeneralId) -> Outcome.NOT_HOSTILE
                !GridReach.canStrike(deployment.layout, attacker.position, target.position,
                    profiles.getValue(attackerForce.crewTypeId).attackRange) -> Outcome.OUT_OF_REACH
                else -> Outcome.STRUCK
            }
            AttackResult(attacker.bugokId, target.bugokId, outcome,
                if (outcome == Outcome.STRUCK) damage(attacker, target) else 0)
        }
        val losses = attacks.groupBy { it.targetId }.mapValues { (_, values) ->
            values.fold(BigInteger.ZERO) { sum, attack -> sum + attack.potentialCasualties.toBigInteger() }
        }
        val active = attacks.filter { it.outcome == Outcome.STRUCK }.mapTo(hashSetOf()) { it.attackerId }
        val after = units.sortedBy { it.bugokId }.map { unit ->
            val lost = (losses[unit.bugokId] ?: BigInteger.ZERO).min(unit.troops.toBigInteger()).toInt()
            val moraleLoss = if (lost == 0) 0 else ((lost.toLong() * 100 + unit.troops - 1) / unit.troops).toInt()
            unit.copy(troops=unit.troops-lost, morale=(unit.morale-moraleLoss).coerceAtLeast(0),
                fatigue=(unit.fatigue + if (unit.bugokId in active) FATIGUE_PER_ATTACK else 0).coerceAtMost(100),
                position=if (lost == unit.troops) null else unit.position)
        }
        return Result(after, attacks)
    }

    class MovementPlan(val bugokId: Int, path: List<Position>) {
        val path: List<Position> = Collections.unmodifiableList(ArrayList(path))
    }
    data class RoundMove(val step: Int, val move: GridMovement.Step)
    class RoundResult(movements: List<RoundMove>, val exchange: Result) {
        val movements: List<RoundMove> = Collections.unmodifiableList(ArrayList(movements))
    }

    /** Explicit paths are bounded by sealed profiles; a blocked path stops for the rest of this round. */
    fun resolveRound(units: List<UnitState>, movements: List<MovementPlan>, attacks: List<AttackIntent>): RoundResult {
        // Validate the entire input before any calculation, including malformed later attack identities.
        validate(units, attacks)
        require(movements.map { it.bugokId }.distinct().size == movements.size)
        require(movements.all { plan -> plan.bugokId in original && plan.path.size <=
            profiles.getValue(original.getValue(plan.bugokId).crewTypeId).movementSteps })
        var current = units.sortedBy { it.bugokId }
        val stopped = hashSetOf<Int>()
        val history = mutableListOf<RoundMove>()
        for (step in 0 until (movements.maxOfOrNull { it.path.size } ?: 0)) {
            val state = current.associateBy { it.bugokId }
            val requests = movements.filter { it.path.size > step && it.bugokId !in stopped }.sortedBy { it.bugokId }
            val eligible = requests.filter { plan ->
                val unit = state.getValue(plan.bugokId)
                val outcome = when {
                    unit.troops == 0 || unit.morale == 0 -> GridMovement.Outcome.INACTIVE
                    unit.position == null -> GridMovement.Outcome.RESERVE
                    else -> null
                }
                if (outcome != null) {
                    stopped.add(plan.bugokId)
                    history.add(RoundMove(step + 1,GridMovement.Step(plan.bugokId,unit.position,unit.position,outcome)))
                }
                outcome == null
            }
            val result = GridMovement.resolve(deployment.layout, current.map { unit ->
                GridMovement.UnitPosition(unit.bugokId,unit.position,
                    profiles.getValue(original.getValue(unit.bugokId).crewTypeId).initiative)
            },eligible.map { GridMovement.Intent(it.bugokId,it.path[step]) })
            val requested = eligible.mapTo(hashSetOf()) { it.bugokId }
            for (move in result.filter { it.bugokId in requested }) {
                history.add(RoundMove(step + 1,move))
                if (move.outcome != GridMovement.Outcome.MOVED) stopped.add(move.bugokId)
            }
            val positions = result.associate { it.bugokId to it.to }
            current = current.map { it.copy(position=positions.getValue(it.bugokId)) }
        }
        return RoundResult(history.sortedWith(compareBy(RoundMove::step).thenBy { it.move.bugokId }), resolve(current,attacks))
    }

    private fun validate(units: List<UnitState>, intents: List<AttackIntent>) {
        require(units.map { it.bugokId }.toSet() == original.keys && units.size == original.size)
        require(units.all { it.troops in 0..original.getValue(it.bugokId).troops && it.morale in 0..100 &&
            it.fatigue in 0..100 && (it.troops > 0 || it.position == null) &&
            (it.position == null || it.position in deployment.layout.distancesFromEntry) })
        val occupied = units.mapNotNull { it.position }
        require(occupied.distinct().size == occupied.size)
        require(intents.map { it.attackerId }.distinct().size == intents.size &&
            intents.all { it.attackerId in original && it.targetId in original && it.attackerId != it.targetId })
    }

    private fun damage(attacker: UnitState, target: UnitState): Int {
        val a = original.getValue(attacker.bugokId)
        val d = original.getValue(target.bugokId)
        val ap = profiles.getValue(a.crewTypeId)
        val dp = profiles.getValue(d.crewTypeId)
        fun product(vararg factors: Long) = factors.fold(BigInteger.ONE) { value, factor -> value * factor.toBigInteger() }
        val numerator = product(attacker.troops.toLong(), ap.attackPower.toLong(), 100L+a.training,
            50L+attacker.morale, 200L-attacker.fatigue, 100L+commanders.getValue(a.commanderGeneralId).leadership)
        val denominator = product(dp.defencePower.toLong(), 100L+d.training,
            100L+commanders.getValue(d.commanderGeneralId).leadership, DAMAGE_DIVISOR.toLong(), 100, 200)
        return numerator.divide(denominator).max(BigInteger.ONE).min(target.troops.toBigInteger()).toInt()
    }

    companion object {
        const val RULE_VERSION = 1
        const val DAMAGE_DIVISOR = 20
        const val FATIGUE_PER_ATTACK = 2
    }
}
