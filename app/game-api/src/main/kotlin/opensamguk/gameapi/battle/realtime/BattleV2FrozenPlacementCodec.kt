package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.Battlefield
import opensamguk.logic.battle.realtime.TacticalBoardCatalog
import opensamguk.logic.battle.realtime.TacticalV2Cell
import opensamguk.logic.battle.realtime.TacticalV2Deployment
import opensamguk.logic.battle.realtime.TacticalV2SourceKind

/** Reads only the v2 placement pin; full campaign source binding remains the producer's gate. */
class BattleV2FrozenPlacementCodec(
    private val catalog: TacticalBoardCatalog,
    private val expectedRuleSha256: String,
    private val spawnCellsForBoard: (Battlefield) -> Map<BattleSide, Set<TacticalV2Cell>>?,
) {
    init { require(expectedRuleSha256.matches(Regex("[0-9a-f]{64}"))) }

    fun initialDeployment(ticket: FrozenBattleTicket): TacticalV2Deployment {
        require(sha(ticket.payloadJson.toByteArray(Charsets.UTF_8)) == ticket.payloadSha256)
        require(ticket.catalogSha256 == catalog.catalogSha256 && ticket.ruleSha256 == expectedRuleSha256)
        val root = Json.parseToJsonElement(ticket.payloadJson).jsonObject
        require(root.int("schemaVersion") == 2)
        require(root.int("worldId") == ticket.worldId.value && root.string("battleId") == ticket.battleId)
        require(root.string("ruleSha256") == ticket.ruleSha256 &&
            root.string("catalogSha256") == ticket.catalogSha256 &&
            root.string("terrainSha256") == ticket.terrainSha256)
        require(root.long("seed") == ticket.seed &&
            root.long("lockGeneration") == ticket.lockGeneration &&
            root.long("lockSetRevision") == ticket.lockSetRevision)
        require(root.string("joinDeadlineAt") == ticket.joinDeadlineAt.toString() &&
            root.string("deadlineAt") == ticket.deadlineAt.toString())
        val kind = root.string("kind")
        require(kind in setOf("ENCOUNTER", "SIEGE"))
        require(kind == "ENCOUNTER") { "v2 siege garrison source and door pins are pending" }
        val boardId = root.int("battlefieldId")
        val board = catalog.boards.singleOrNull { it.battlefield.id == boardId }
            ?: throw IllegalArgumentException("battlefield not in pinned catalog")
        require(board.landEligible && board.battlefield.kind ==
            (if (kind == "SIEGE") "FORTRESS" else "FIELD"))
        val boardRowsSha = sha(board.battlefield.rows.joinToString("").toByteArray(Charsets.US_ASCII))

        val tactical = root.getValue("tacticalInput").jsonObject
        require(tactical.keys == setOf("schemaVersion", "placement") && tactical.int("schemaVersion") == 2)
        val raw = tactical.getValue("placement").jsonObject
        require(raw.keys == setOf("schemaVersion", "boardId", "terrainSha256", "sourceCount",
            "deploymentBase64", "deploymentSha256"))
        val pin = BattleV2PlacementPin(raw.int("schemaVersion"), raw.int("boardId"),
            raw.string("terrainSha256"), raw.int("sourceCount"),
            raw.string("deploymentBase64"), raw.string("deploymentSha256"))
        require(pin.boardId == boardId && pin.terrainSha256 == boardRowsSha) {
            "board row pin mismatch"
        }
        val allowed = spawnCellsForBoard(board.battlefield)
            ?: throw IllegalArgumentException("spawn cells not pinned")
        require(allowed.values.flatten().all { cell -> board.battlefield.at(cell.row, cell.col) in "PFMR" }) {
            "spawn cell is not passable in pinned board"
        }
        return pin.decode(allowed).also { deployment ->
            require(deployment.units.all { it.key.kind == TacticalV2SourceKind.RETINUE }) {
                "encounter cannot include unbound city garrison sources"
            }
        }
    }

    private fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.also {
        require(!it.isString) { "$key must be a JSON number" }
    }.int
    private fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.also {
        require(!it.isString) { "$key must be a JSON number" }
    }.content.toLong()
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.also {
        require(it.isString) { "$key must be a JSON string" }
    }.content

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
