package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.logic.battle.realtime.BattleDeployment
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.Deployment
import opensamguk.logic.battle.realtime.GeneralStats
import opensamguk.logic.battle.realtime.Retinue
import opensamguk.logic.battle.realtime.TacticalBattle
import opensamguk.logic.battle.realtime.TacticalBoardCatalog
import opensamguk.logic.battle.realtime.TacticalRules
import opensamguk.logic.battle.realtime.TacticalState
import opensamguk.logic.battle.realtime.UnitKind

/** Refuses to construct an authority state unless the immutable handoff matches pinned game inputs. */
class BattleFrozenInputCodec(private val catalog: TacticalBoardCatalog) {
    /** A different installed revision may be valid; do not permanently reject its handoff. */
    fun hasInstalledPins(ticket: FrozenBattleTicket): Boolean {
        if (catalog.catalogSha256 != ticket.catalogSha256) return false
        val rules = TacticalRules::class.java.classLoader
            .getResourceAsStream("battle/waryong-tactical-rules-v1.json")?.use { it.readBytes() }
            ?: return false
        return sha(rules) == ticket.ruleSha256
    }

    fun initialState(ticket: FrozenBattleTicket): TacticalState {
        require(sha(ticket.payloadJson.toByteArray(Charsets.UTF_8)) == ticket.payloadSha256)
        require(catalog.catalogSha256 == ticket.catalogSha256) { "battle catalog pin mismatch" }
        val rules = TacticalRules::class.java.classLoader
            .getResourceAsStream("battle/waryong-tactical-rules-v1.json")?.use { it.readBytes() }
            ?: error("battle rule resource missing")
        require(sha(rules) == ticket.ruleSha256) { "battle rule pin mismatch" }
        TacticalRules.parse(String(rules, Charsets.UTF_8))

        val root = Json.parseToJsonElement(ticket.payloadJson).jsonObject
        require(root.int("schemaVersion") == 1 && root.int("worldId") == ticket.worldId.value)
        require(root.string("battleId") == ticket.battleId)
        require(root.string("ruleSha256") == ticket.ruleSha256 &&
            root.string("catalogSha256") == ticket.catalogSha256 &&
            root.string("terrainSha256") == ticket.terrainSha256)
        require(root.long("seed") == ticket.seed && root.long("lockGeneration") == ticket.lockGeneration &&
            root.long("lockSetRevision") == ticket.lockSetRevision)
        val battleKind = root.string("kind")
        require(battleKind in setOf("ENCOUNTER", "SIEGE")) { "tactical board required" }
        require("tacticalInput" in root) { "tactical input missing" }
        val input = root.getValue("tacticalInput").jsonObject
        require(input.keys == setOf("schemaVersion", "board", "attacker", "defender", "gate"))
        require(input.int("schemaVersion") == 1)
        val boardPin = input.getValue("board").jsonObject
        require(boardPin.keys == setOf("id", "tileset", "terrainRowsSha256"))
        val boardId = boardPin.int("id")
        require(boardId == root.int("battlefieldId"))
        val board = catalog.boards.singleOrNull { it.battlefield.id == boardId }
            ?: throw IllegalArgumentException("battlefield not in catalog")
        require(board.landEligible)
        require(board.tileset == boardPin.int("tileset"))
        require(board.battlefield.kind == if (battleKind == "SIEGE") "FORTRESS" else "FIELD")
        val rows = board.battlefield.rows.joinToString("").toByteArray(Charsets.US_ASCII)
        require(rows.size == 4096 && sha(rows) == boardPin.string("terrainRowsSha256")) {
            "battlefield row pin mismatch"
        }

        val attacker = parseDeployment(input.getValue("attacker").jsonObject, BattleSide.ATTACKER)
        val defender = parseDeployment(input.getValue("defender").jsonObject, BattleSide.DEFENDER)
        val retinues = attacker.retinues + defender.retinues
        require(retinues.map { it.id }.distinct().size == retinues.size)
        require(retinues.map { it.general.id }.distinct().size == retinues.size)
        ticket.participants.forEach { participant ->
            val side = if (participant.side == "ATTACKER") attacker else defender
            require(side.retinues.any { it.general.id == participant.generalId }) {
                "battle participant not in frozen side"
            }
        }
        val gate = input.getValue("gate")
        val gateRow: Int?
        val gateCol: Int?
        val gateHp: Int
        if (battleKind == "ENCOUNTER") {
            require(gate == JsonNull)
            gateRow = null; gateCol = null; gateHp = 0
        } else {
            val value = gate.jsonObject
            require(value.keys == setOf("row", "col", "hp"))
            gateRow = value.int("row"); gateCol = value.int("col"); gateHp = value.int("hp")
            require(gateHp > 0 && board.battlefield.at(gateRow, gateCol) == 'W')
        }
        return TacticalBattle.start(ticket.seed, board.battlefield, attacker.deployment, defender.deployment,
            gateRow = gateRow, gateCol = gateCol, gateHp = gateHp)
    }

    private data class FrozenSide(val deployment: Deployment, val retinues: List<Retinue>)

    private fun parseDeployment(value: JsonObject, side: BattleSide): FrozenSide {
        require(value.keys == setOf("commanderGeneralId", "retinues"))
        val commander = value.int("commanderGeneralId")
        require(commander > 0)
        val raw = value.getValue("retinues").jsonArray
        require(raw.size <= 6)
        val retinues = raw.map { parseRetinue(it.jsonObject) }
        require(retinues.map { it.id } == retinues.map { it.id }.sorted())
        require(retinues.map { it.id }.distinct().size == retinues.size)
        require(retinues.map { it.general.id }.distinct().size == retinues.size)
        val deployment = BattleDeployment.default(side, commander, retinues)
        require(deployment.slots.size == retinues.size) { "frozen retinue not deployable" }
        return FrozenSide(deployment, retinues)
    }

    private fun parseRetinue(value: JsonObject): Retinue {
        require(value.keys == setOf("id", "generalId", "leadership", "strength", "intelligence",
            "politics", "charisma", "troops", "kind", "training", "morale", "fatigue", "supply",
            "accompaniesCorps"))
        val general = GeneralStats(value.int("generalId"), value.int("leadership"),
            value.int("strength"), value.int("intelligence"), value.int("politics"), value.int("charisma"))
        val retinue = Retinue(value.int("id"), general, value.int("troops"),
            UnitKind.valueOf(value.string("kind")), value.int("training"), value.int("morale"),
            value.int("fatigue"), value.int("supply"), value.getValue("accompaniesCorps").jsonPrimitive.boolean)
        require(retinue.troops > 0 && retinue.accompaniesCorps)
        return retinue
    }

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JsonObject.number(key: String): JsonPrimitive = getValue(key).jsonPrimitive.also {
        require(!it.isString) { "$key must be a JSON number" }
    }

    private fun JsonObject.int(key: String): Int = number(key).int
    private fun JsonObject.long(key: String): Long = number(key).content.toLong()
    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.also {
        require(it.isString) { "$key must be a JSON string" }
    }.content
}
