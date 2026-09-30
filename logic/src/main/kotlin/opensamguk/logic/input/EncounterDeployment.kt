package opensamguk.logic.input

import java.util.Collections
import opensamguk.logic.world.ProvinceCellIndex
import opensamguk.logic.world.BattlefieldGeometry.Position
import opensamguk.logic.world.BattlefieldLayout

/** Encounter-bound default formation. A reserve has no occupied cell; it is not a lost unit. */
class EncounterDeployment private constructor(
    val encounterId: String,
    val layout: BattlefieldLayout,
    val ruleVersion: Int,
    tokens: List<Token>,
) {
    data class Token(val commanderGeneralId: Int, val orderId: String, val bugokId: Int, val position: Position?)
    val tokens: List<Token> = Collections.unmodifiableList(ArrayList(tokens))

    sealed interface Result {
        data class Ready(val deployment: EncounterDeployment) : Result
        data class TerrainUnavailable(val reason: BattlefieldLayout.Reason) : Result
        data object InsufficientDefenderCapacity : Result
    }

    companion object {
        const val LEGACY_RULE_VERSION = 1
        const val RULE_VERSION = 2
        const val META_KEY = "encounterDeployment"

        fun defaultMetaValue(encounter: CorpsEncounter, index: ProvinceCellIndex,
            ruleVersion: Int = LEGACY_RULE_VERSION): Map<String, Any?> =
            serialize(prepareDefault(encounter, index, ruleVersion), encounter, index, ruleVersion)

        /** Exact reconstruction also rejects changed pins, extra fields, and tampered occupied cells. */
        fun read(meta: Map<String, Any?>, encounter: CorpsEncounter, index: ProvinceCellIndex): Result? {
            if (META_KEY !in meta) return null
            val raw = meta[META_KEY] as? Map<*, *> ?: throw IllegalArgumentException("Invalid sealed encounter deployment")
            val ruleVersion = raw["version"] as? Int ?: throw IllegalArgumentException("Invalid sealed encounter deployment")
            require(ruleVersion == LEGACY_RULE_VERSION || ruleVersion == RULE_VERSION) { "Invalid sealed encounter deployment" }
            val result = prepareDefault(encounter, index, ruleVersion)
            require(raw == serialize(result, encounter, index, ruleVersion)) { "Invalid sealed encounter deployment" }
            return result
        }

        private fun serialize(result: Result, encounter: CorpsEncounter, index: ProvinceCellIndex,
            ruleVersion: Int): Map<String, Any?> {
            val value = linkedMapOf<String, Any?>("version" to ruleVersion,
                "geometryVersion" to opensamguk.logic.world.BattlefieldGeometry.RULE_VERSION,
                "layoutVersion" to if (ruleVersion == RULE_VERSION) BattlefieldLayout.FRONTLINE_RULE_VERSION
                    else BattlefieldLayout.RULE_VERSION, "encounterId" to encounter.encounterId,
                "topologyRevision" to index.topologyRevision, "topologyHash" to index.topologyHash,
                "tilesContentHash" to index.tilesContentHash)
            when (result) {
                is Result.Ready -> {
                    value["status"] = "READY"
                    value["tokens"] = result.deployment.tokens.map { token -> linkedMapOf<String, Any?>(
                        "commanderGeneralId" to token.commanderGeneralId, "orderId" to token.orderId,
                        "bugokId" to token.bugokId, "position" to token.position?.let { mapOf("col" to it.col, "row" to it.row) }) }
                }
                is Result.TerrainUnavailable -> { value["status"] = "TERRAIN_UNAVAILABLE"; value["reason"] = result.reason.name }
                Result.InsufficientDefenderCapacity -> value["status"] = "INSUFFICIENT_DEFENDER_CAPACITY"
            }
            return value
        }

        /** Called at approach, never to accept a new plan after an encounter has been sealed. */
        fun prepareDefault(encounter: CorpsEncounter, index: ProvinceCellIndex,
            ruleVersion: Int = LEGACY_RULE_VERSION): Result {
            require(ruleVersion == LEGACY_RULE_VERSION || ruleVersion == RULE_VERSION)
            require(encounter.topologyRevision == index.topologyRevision && encounter.topologyHash == index.topologyHash) {
                "Encounter and battlefield pins differ"
            }
            val result = BattlefieldLayout.prepare(index, encounter.province.id, encounter.approachFrom.id,
                if (ruleVersion == RULE_VERSION) BattlefieldLayout.FRONTLINE_RULE_VERSION else BattlefieldLayout.RULE_VERSION)
            if (result is BattlefieldLayout.Result.Unavailable) return Result.TerrainUnavailable(result.reason)
            val layout = (result as BattlefieldLayout.Result.Ready).layout
            val defenders = encounter.defenders.sortedWith(compareBy(EncounterParticipant::commanderGeneralId,
                EncounterParticipant::ownerGeneralId, EncounterParticipant::orderId))
            // Each participant needs a real presence. Never silently discard a third-party defender.
            if (layout.defenderZone.size < defenders.size) return Result.InsufficientDefenderCapacity
            val positions = compareBy<Position> { it.row }.thenBy { it.col }
            val attackerCells = if (ruleVersion == RULE_VERSION)
                staggeredAttackerCells(layout, encounter.attacker.bugokIds.size, positions)
            else layout.attackerZone.sortedWith(compareBy<Position> { layout.distancesFromEntry.getValue(it) }
                .then(positions))
            val units = defenders.map { it.bugokIds.sorted() }
            val defenderCells = if (ruleVersion == RULE_VERSION)
                staggeredDefenderCells(layout, units, positions)
            else layout.defenderZone.sortedWith(compareByDescending<Position> { layout.distancesFromEntry.getValue(it) }
                .then(positions))
            val tokens = mutableListOf<Token>()
            encounter.attacker.bugokIds.sorted().forEachIndexed { indexInArmy, id ->
                tokens.add(Token(encounter.attacker.commanderGeneralId, encounter.attacker.orderId, id,
                    attackerCells.getOrNull(indexInArmy)))
            }
            // Round-robin corps allocation gives each defender a cell before allocating its second.
            // Spatial grouping is not an alliance or target-selection rule.
            var nextCell = 0
            for (unitIndex in 0 until units.maxOf { it.size }) {
                for ((participantIndex, participant) in defenders.withIndex()) {
                    val id = units[participantIndex].getOrNull(unitIndex) ?: continue
                    tokens.add(Token(participant.commanderGeneralId, participant.orderId, id, defenderCells.getOrNull(nextCell)))
                    nextCell++
                }
            }
            val canonicalTokens = tokens.sortedWith(compareBy(Token::commanderGeneralId, Token::bugokId))
            return Result.Ready(EncounterDeployment(encounter.encounterId, layout, ruleVersion, canonicalTokens))
        }

        /** Front rank first for every defender, then deeper ranks; ties never depend on input order. */
        private fun staggeredDefenderCells(layout: BattlefieldLayout, units: List<List<Int>>,
            positions: Comparator<Position>): List<Position> {
            val available = layout.defenderZone.toMutableSet()
            val chosen = mutableListOf<Position>()
            val frontDepth = available.minOf { layout.distancesFromEntry.getValue(it) }
            for (rank in 0 until units.maxOf { it.size }) {
                for (participant in units.indices) {
                    if (units[participant].getOrNull(rank) == null || available.isEmpty()) continue
                    val desiredDepth = frontDepth + rank * 4
                    val cell = available.minWith(compareBy<Position> {
                        kotlin.math.abs(layout.distancesFromEntry.getValue(it) - desiredDepth)
                    }.thenBy { layout.distancesFromEntry.getValue(it) }.then(positions))
                    chosen.add(cell)
                    available.remove(cell)
                }
            }
            return chosen
        }

        private fun staggeredAttackerCells(layout: BattlefieldLayout, count: Int,
            positions: Comparator<Position>): List<Position> {
            val available = layout.attackerZone.toMutableSet()
            val chosen = mutableListOf<Position>()
            val frontDepth = available.maxOf { layout.distancesFromEntry.getValue(it) }
            repeat(minOf(count, available.size)) { rank ->
                val desiredDepth = frontDepth - rank * 4
                val cell = available.minWith(compareBy<Position> {
                    kotlin.math.abs(layout.distancesFromEntry.getValue(it) - desiredDepth)
                }.thenByDescending { layout.distancesFromEntry.getValue(it) }.then(positions))
                chosen.add(cell)
                available.remove(cell)
            }
            return chosen
        }

    }
}
