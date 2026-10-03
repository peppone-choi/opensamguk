package opensamguk.engine.battle

import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.logic.battle.realtime.BattleDeployment
import opensamguk.logic.battle.realtime.BattleKind
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.Retinue
import opensamguk.logic.battle.realtime.TacticalBoardCatalog
import opensamguk.logic.battle.realtime.TacticalRules
import opensamguk.logic.battle.realtime.TerrainProfile

data class CampaignBattleSide(val commanderGeneralId: Int, val retinues: List<Retinue>) {
    init { require(commanderGeneralId > 0) }
}

data class CampaignBattleGate(val row: Int, val col: Int, val hp: Int)

/** Values must come from the committed campaign encounter, not live reads made by the battle actor. */
data class CampaignBattleFreezeRequest(
    val worldId: WorldId,
    val battleId: String,
    val causeEventId: String,
    val kind: BattleKind,
    val provinceTerrain: TerrainProfile,
    val worldTerrainSha256: String,
    val seed: Long,
    val lockGeneration: Long,
    val lockSetRevision: Long,
    val joinDeadlineAt: Instant,
    val deadlineAt: Instant,
    val entityRevisions: Map<String, Long>,
    val participants: List<FrozenBattleParticipant>,
    val attacker: CampaignBattleSide,
    val defender: CampaignBattleSide,
    val gate: CampaignBattleGate? = null,
)

/** Produces the byte-stable v1 handoff. Persistence and commit ordering belong to the campaign flush. */
class CampaignBattleTicketFactory(private val catalog: TacticalBoardCatalog) {
    private val pinnedRules = requireNotNull(TacticalRules::class.java.classLoader
        .getResourceAsStream("battle/waryong-tactical-rules-v1.json")) { "battle rules missing" }
        .use { it.readBytes() }
        .also { TacticalRules.parse(String(it, Charsets.UTF_8)) }
    private val ruleSha256 = sha(pinnedRules)

    fun freeze(request: CampaignBattleFreezeRequest): FrozenBattleTicket {
        require(request.battleId.isNotBlank() && request.battleId.length <= 128)
        require(request.causeEventId.isNotBlank() && request.causeEventId == request.causeEventId.trim() &&
            request.causeEventId.length <= 128)
        require(request.worldTerrainSha256.matches(HASH)) { "world terrain pin missing" }
        require(request.lockGeneration > 0 && request.lockSetRevision > 0)
        require(request.joinDeadlineAt.isBefore(request.deadlineAt))
        require(request.entityRevisions.isNotEmpty() && request.entityRevisions.all { (key, revision) ->
            key.isNotBlank() && key == key.trim() && revision >= 0
        }) { "campaign entity revisions missing" }

        val participants = request.participants.sortedBy { it.participantId }
        require(participants.map { it.participantId }.distinct().size == participants.size &&
            participants.map { it.accountId }.distinct().size == participants.size &&
            participants.map { it.generalId }.distinct().size == participants.size) {
            "battle participant authority conflict"
        }
        val allRetinues = request.attacker.retinues + request.defender.retinues
        require(allRetinues.map { it.id }.distinct().size == allRetinues.size &&
            allRetinues.map { it.general.id }.distinct().size == allRetinues.size) {
            "battle force identity conflict"
        }
        validateSide(request.attacker, BattleSide.ATTACKER)
        validateSide(request.defender, BattleSide.DEFENDER)
        for (participant in participants) {
            val side = when (participant.side) {
                BattleSide.ATTACKER.name -> request.attacker
                BattleSide.DEFENDER.name -> request.defender
                else -> error("unknown battle side")
            }
            require(side.retinues.any { it.general.id == participant.generalId }) {
                "battle participant not in frozen side"
            }
        }

        val board = catalog.select(request.battleId, request.kind, request.provinceTerrain).board
        require(board.landEligible)
        val gate = request.gate
        if (request.kind == BattleKind.ENCOUNTER) require(gate == null) { "field battle has gate" }
        else require(gate != null && gate.hp > 0 && board.battlefield.at(gate.row, gate.col) == 'W') {
            "siege gate is not a pinned wall cell"
        }
        val terrainBytes = board.battlefield.rows.joinToString("").toByteArray(Charsets.US_ASCII)
        require(terrainBytes.size == 4096)
        val terrainRowsSha256 = sha(terrainBytes)
        val payload = buildJsonObject {
            put("schemaVersion", 1)
            put("worldId", request.worldId.value)
            put("battleId", request.battleId)
            put("causeEventId", request.causeEventId)
            put("kind", request.kind.name)
            put("battlefieldId", board.battlefield.id)
            put("ruleSha256", ruleSha256)
            put("catalogSha256", catalog.catalogSha256)
            put("terrainSha256", request.worldTerrainSha256)
            put("seed", request.seed)
            put("lockGeneration", request.lockGeneration)
            put("lockSetRevision", request.lockSetRevision)
            put("pacingMode", if (participants.isEmpty()) "ACCELERATED_NPC" else "REALTIME")
            put("joinDeadlineAt", request.joinDeadlineAt.toString())
            put("deadlineAt", request.deadlineAt.toString())
            put("entityRevisions", buildJsonObject {
                request.entityRevisions.toSortedMap().forEach { (key, revision) -> put(key, revision) }
            })
            put("participants", buildJsonArray {
                participants.forEach { participant ->
                    add(buildJsonObject {
                        put("participantId", participant.participantId)
                        put("accountId", participant.accountId)
                        put("generalId", participant.generalId)
                        put("side", participant.side)
                        put("authorityRevision", participant.authorityRevision)
                    })
                }
            })
            put("tacticalInput", buildJsonObject {
                put("schemaVersion", 1)
                put("board", buildJsonObject {
                    put("id", board.battlefield.id)
                    put("tileset", board.tileset)
                    put("terrainRowsSha256", terrainRowsSha256)
                })
                put("attacker", sideJson(request.attacker))
                put("defender", sideJson(request.defender))
                put("gate", gate?.let { value -> buildJsonObject {
                    put("row", value.row)
                    put("col", value.col)
                    put("hp", value.hp)
                } } ?: JsonNull)
            })
        }.toString()
        return FrozenBattleTicket(request.worldId, request.battleId, payload, sha(payload.toByteArray(Charsets.UTF_8)),
            ruleSha256, catalog.catalogSha256, request.worldTerrainSha256, request.seed,
            request.lockGeneration, request.lockSetRevision, request.joinDeadlineAt, request.deadlineAt, participants)
    }

    private fun validateSide(side: CampaignBattleSide, role: BattleSide) {
        require(side.retinues.isNotEmpty() && side.retinues.size <= 6 &&
            side.retinues.map { it.id }.distinct().size == side.retinues.size) {
            "battle side requires one to six unique retinues"
        }
        require(side.retinues.all { it.accompaniesCorps && it.troops > 0 })
        require(BattleDeployment.default(role, side.commanderGeneralId, side.retinues).slots.size ==
            side.retinues.size) { "frozen retinue not deployable" }
    }

    private fun sideJson(side: CampaignBattleSide) = buildJsonObject {
        put("commanderGeneralId", side.commanderGeneralId)
        put("retinues", buildJsonArray {
            side.retinues.sortedBy { it.id }.forEach { retinue ->
                add(buildJsonObject {
                    put("id", retinue.id)
                    put("generalId", retinue.general.id)
                    put("leadership", retinue.general.leadership)
                    put("strength", retinue.general.strength)
                    put("intelligence", retinue.general.intelligence)
                    put("politics", retinue.general.politics)
                    put("charisma", retinue.general.charisma)
                    put("troops", retinue.troops)
                    put("kind", retinue.kind.name)
                    put("training", retinue.training)
                    put("morale", retinue.morale)
                    put("fatigue", retinue.fatigue)
                    put("supply", retinue.supply)
                    put("accompaniesCorps", retinue.accompaniesCorps)
                })
            }
        })
    }

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")
        private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
