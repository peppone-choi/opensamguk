package opensamguk.logic.war

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections
import opensamguk.logic.input.CorpsEncounter
import opensamguk.logic.input.EncounterDeployment
import opensamguk.logic.input.EncounterForces
import opensamguk.logic.input.EncounterRelations

/**
 * Runs a sealed encounter to its first playback barrier and classifies the result (§5.1.1 phase 5).
 *
 * Pure: the same sealed inputs always yield the same journal, final units and verdict. The caller
 * applies losses, retreat and deployment endings through the recorder. Rules for the verdict:
 *
 * - A commander is DESTROYED when every one of its units has zero troops, RETREATED when its last
 *   activated command is a retreat, otherwise HOLDING.
 * - The attacker wins only when it is HOLDING and every defender is DESTROYED or RETREATED. Any other
 *   barrier — including a simultaneous round-limit retreat — is a defender victory: the attacker withdraws.
 * - Losers are the attacker when it did not win, and each defender that is DESTROYED or RETREATED.
 *   Winners are the HOLDING commanders of the victorious side.
 * - A DESTROYED loser of the opposing side becomes a captive: destroyed defenders of the attacker when it
 *   won, or a destroyed attacker of the lowest HOLDING defender. With no winner there is no captor. This
 *   is the provisional minimal captive rule
 *   (`data/curated/han/hwiha-s3-provisional-v1.json` `encounter.captives`).
 */
object EncounterResolution {
    const val RULE_VERSION = 1

    enum class CommanderStatus { HOLDING, RETREATED, DESTROYED }
    enum class Outcome { ATTACKER_VICTORY, DEFENDER_VICTORY }

    class Result internal constructor(
        val journal: BattleJournal,
        val barrier: BattlePlayback.Barrier,
        units: List<GridExchange.UnitState>,
        statuses: Map<Int, CommanderStatus>,
        val outcome: Outcome,
        winners: List<Int>,
        losers: List<Int>,
        captives: Map<Int, Int>,
        val replayHash: String,
    ) {
        val units: List<GridExchange.UnitState> = Collections.unmodifiableList(units.sortedBy { it.bugokId })
        val statuses: Map<Int, CommanderStatus> = Collections.unmodifiableMap(java.util.TreeMap(statuses))
        val winners: List<Int> = Collections.unmodifiableList(winners.sorted())
        val losers: List<Int> = Collections.unmodifiableList(losers.sorted())
        /** captive commander id → captor commander id. */
        val captives: Map<Int, Int> = Collections.unmodifiableMap(java.util.TreeMap(captives))
        val rounds: Int get() = journal.rounds.size
    }

    fun resolve(
        encounter: CorpsEncounter,
        forces: EncounterForces,
        relations: EncounterRelations,
        combat: EncounterCombatProfiles,
        plans: BattlePlans,
        deployment: EncounterDeployment,
        journal: BattleJournal,
    ): Result {
        val playback = BattlePlayback(encounter, forces, relations, combat, plans, deployment)
        val autopilot = BattleAutopilot(encounter, forces, relations, combat, plans, deployment)
        var current = journal
        var replay = playback.replay(current)
        while (replay.barrier == BattlePlayback.Barrier.NONE) {
            // Playback always raises ROUND_LIMIT at the last round, so this loop is bounded by MAX_ROUNDS.
            check(replay.lastResolvedRound < BattlePlans.MAX_ROUNDS) { "Battle exceeded the round limit" }
            current = current.append(autopilot.next(current))
            replay = playback.replay(current)
        }
        val state = replay.units.associateBy { it.bugokId }
        val attackerId = encounter.attacker.commanderGeneralId
        val commanders = (listOf(encounter.attacker) + encounter.defenders).map { it.commanderGeneralId }.sorted()
        val statuses = commanders.associateWith { commander ->
            val army = forces.units.filter { it.commanderGeneralId == commander }
            when {
                army.all { state.getValue(it.bugokId).troops == 0 } -> CommanderStatus.DESTROYED
                replay.actions[commander] == BattlePlanAction.RETREAT -> CommanderStatus.RETREATED
                else -> CommanderStatus.HOLDING
            }
        }
        val defenders = commanders - attackerId
        val attackerWon = statuses.getValue(attackerId) == CommanderStatus.HOLDING &&
            defenders.all { statuses.getValue(it) != CommanderStatus.HOLDING }
        val outcome = if (attackerWon) Outcome.ATTACKER_VICTORY else Outcome.DEFENDER_VICTORY
        val losers = if (attackerWon) defenders else
            listOf(attackerId) + defenders.filter { statuses.getValue(it) != CommanderStatus.HOLDING }
        val winners = if (attackerWon) listOf(attackerId) else defenders.filter { statuses.getValue(it) == CommanderStatus.HOLDING }
        // Captives come only from the opposing side: a destroyed defender is never "captured" by its co-defender.
        val captives = when {
            attackerWon -> defenders.filter { statuses.getValue(it) == CommanderStatus.DESTROYED }.associateWith { attackerId }
            winners.isNotEmpty() && statuses.getValue(attackerId) == CommanderStatus.DESTROYED -> mapOf(attackerId to winners.min())
            else -> emptyMap()
        }
        return Result(current, replay.barrier, replay.units, statuses, outcome, winners, losers, captives,
            hash(current, replay.barrier, replay.units, statuses, outcome))
    }

    private fun hash(journal: BattleJournal, barrier: BattlePlayback.Barrier,
        units: List<GridExchange.UnitState>, statuses: Map<Int, CommanderStatus>, outcome: Outcome): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String) { val b = value.toByteArray(Charsets.UTF_8); out.writeInt(b.size); out.write(b) }
            text("encounterResolution:v$RULE_VERSION"); text(journal.snapshotId); text(barrier.name); text(outcome.name)
            out.writeInt(units.size)
            units.sortedBy { it.bugokId }.forEach { unit ->
                listOf(unit.bugokId, unit.troops, unit.morale, unit.fatigue).forEach(out::writeInt)
                out.writeBoolean(unit.position != null)
                unit.position?.let { out.writeInt(it.col); out.writeInt(it.row) }
            }
            out.writeInt(statuses.size)
            statuses.toSortedMap().forEach { (id, status) -> out.writeInt(id); text(status.name) }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
