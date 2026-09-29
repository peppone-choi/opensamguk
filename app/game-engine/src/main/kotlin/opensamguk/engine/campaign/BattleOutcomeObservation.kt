package opensamguk.engine.campaign

import java.util.Collections
import opensamguk.logic.input.CorpsEncounter
import opensamguk.logic.input.EncounterDeployment
import opensamguk.logic.war.EncounterResolution
import opensamguk.logic.world.BattlefieldGeometry.Position
import opensamguk.logic.world.BattlefieldGeometry
import opensamguk.logic.world.BattlefieldLayout

/** QA-only evidence from a resolved, sealed battle. It is never a game event or a replay input. */
class BattleOutcomeObservation private constructor(
    val worldId: Int,
    val encounterId: String,
    val resolvedYear: Int,
    val resolvedMonth: Int,
    val resolvedPhase: Int,
    val provinceId: String,
    val approachProvinceId: String,
    val worldMapVariant: String?,
    val topologyRevision: String,
    val topologyHash: String,
    val tilesContentHash: String,
    val deploymentRuleVersion: Int,
    val layoutRuleVersion: Int,
    val geometryRuleVersion: Int,
    val resolutionRuleVersion: Int,
    val initialSeparationSteps: Int?,
    val outcome: String,
    val winners: List<Int>,
    val statuses: List<CommanderStatusObservation>,
    val barrier: String,
    val rounds: Int,
    val replayHash: String,
    val callbackInvoked: Boolean,
) {
    data class CommanderStatusObservation(val generalId: Int, val status: String)

    companion object {
        internal fun from(
            worldId: Int,
            year: Int,
            month: Int,
            phase: Int,
            worldMapVariant: String?,
            encounter: CorpsEncounter,
            deployment: EncounterDeployment,
            result: EncounterResolution.Result,
            callbackInvoked: Boolean,
        ): BattleOutcomeObservation {
            val geometry = deployment.layout.geometry
            return BattleOutcomeObservation(
                worldId = worldId,
                encounterId = encounter.encounterId,
                resolvedYear = year,
                resolvedMonth = month,
                resolvedPhase = phase,
                provinceId = encounter.province.id,
                approachProvinceId = encounter.approachFrom.id,
                worldMapVariant = worldMapVariant,
                topologyRevision = geometry.topologyRevision,
                topologyHash = geometry.topologyHash,
                tilesContentHash = geometry.tilesContentHash,
                deploymentRuleVersion = EncounterDeployment.RULE_VERSION,
                layoutRuleVersion = BattlefieldLayout.RULE_VERSION,
                geometryRuleVersion = BattlefieldGeometry.RULE_VERSION,
                resolutionRuleVersion = EncounterResolution.RULE_VERSION,
                initialSeparationSteps = initialSeparationSteps(encounter, deployment),
                outcome = result.outcome.name,
                winners = Collections.unmodifiableList(ArrayList(result.winners)),
                statuses = Collections.unmodifiableList(result.statuses.entries.map { (id, status) ->
                    CommanderStatusObservation(id, status.name)
                }),
                barrier = result.barrier.name,
                rounds = result.rounds,
                replayHash = result.replayHash,
                callbackInvoked = callbackInvoked,
            )
        }

        /** Minimum four-neighbor passable steps between placed opposing tokens; ignores occupancy and range. */
        private fun initialSeparationSteps(encounter: CorpsEncounter, deployment: EncounterDeployment): Int? {
            val attackerId = encounter.attacker.commanderGeneralId
            val attackers = deployment.tokens.filter { it.commanderGeneralId == attackerId }
                .mapNotNull { it.position }.toSet()
            val defenders = deployment.tokens.filter { it.commanderGeneralId != attackerId }
                .mapNotNull { it.position }.toSet()
            if (attackers.isEmpty() || defenders.isEmpty()) return null
            val layout = deployment.layout
            val passable = layout.distancesFromEntry.keys
            val distance = mutableMapOf<Position, Int>()
            val queue = ArrayDeque<Position>()
            attackers.sortedWith(compareBy(Position::row, Position::col)).forEach {
                distance[it] = 0
                queue.add(it)
            }
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                val steps = distance.getValue(current)
                if (current in defenders) return steps
                for (neighbor in layout.geometry.neighbors(current)) {
                    val next = neighbor.position
                    if (next in passable && next !in distance) {
                        distance[next] = steps + 1
                        queue.add(next)
                    }
                }
            }
            return null
        }
    }
}

/** Receives pre-commit evidence; a consumer must publish only after the matching flush succeeds. */
fun interface BattleOutcomeObserver {
    fun onResolved(observation: BattleOutcomeObservation)

    companion object {
        val NONE = BattleOutcomeObserver { }
    }
}
