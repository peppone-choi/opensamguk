package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.infra.battle.realtime.BattleCommandReceipt
import opensamguk.logic.battle.realtime.TacticalV2Cell
import opensamguk.logic.battle.realtime.TacticalV2SourceKey
import opensamguk.logic.battle.realtime.TacticalV2SourceKind

/** Exact web v2 frames; no v1 slot conversion or permissive Jackson scalar coercion. */
class BattleV2WireCodec(mapper: ObjectMapper) {
    private val json = mapper.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)

    fun deploymentMove(text: String): BattleV2DeploymentMoveWire {
        val root = json.factory.createParser(text).use { parser ->
            val node = json.readTree<JsonNode>(parser)
            require(parser.nextToken() == null) { "trailing JSON" }
            node
        }
        root.fieldsExactly("schemaVersion", "t", "clientCommandId", "sourceKey", "targetCell",
            "expectedEpoch", "expectedAuthorityRevision", "expectedDeploymentRevision")
        require(root["schemaVersion"].integer() == 2 && root["t"].string() == "DEPLOYMENT_MOVE")
        val key = root["sourceKey"]
        val kind = TacticalV2SourceKind.valueOf(key["kind"].string())
        val cityId = when (kind) {
            TacticalV2SourceKind.RETINUE -> { key.fieldsExactly("kind", "sourceId"); null }
            TacticalV2SourceKind.CITY_GARRISON_BUGOK -> {
                key.fieldsExactly("kind", "sourceId", "cityId")
                key["cityId"].integer().also { require(it > 0) }
            }
        }
        val sourceId = key["sourceId"].sourceId()
        val cell = root["targetCell"].also { it.fieldsExactly("row", "col") }
        return BattleV2DeploymentMoveWire(2, "DEPLOYMENT_MOVE", root["clientCommandId"].string(),
            TacticalV2SourceKey(kind, sourceId, cityId),
            TacticalV2Cell(cell["row"].integer(), cell["col"].integer()),
            root["expectedEpoch"].longString(), root["expectedAuthorityRevision"].longString(),
            root["expectedDeploymentRevision"].longString())
    }

    /** Canonical intent includes every expectation; an ID cannot silently rebase after a rejection. */
    fun intent(move: BattleV2DeploymentMoveWire): String = json.writeValueAsString(linkedMapOf(
        "schemaVersion" to 2, "t" to move.t, "clientCommandId" to move.clientCommandId,
        "sourceKey" to linkedMapOf("kind" to move.sourceKey.kind.name,
            "sourceId" to move.sourceKey.sourceId, "cityId" to move.sourceKey.cityId),
        "targetCell" to linkedMapOf("row" to move.targetCell.row, "col" to move.targetCell.col),
        "expectedEpoch" to move.expectedEpoch.toString(),
        "expectedAuthorityRevision" to move.expectedAuthorityRevision.toString(),
        "expectedDeploymentRevision" to move.expectedDeploymentRevision.toString()))

    fun ack(battleId: String, sessionEpoch: Long, receipt: BattleCommandReceipt): String =
        json.writeValueAsString(linkedMapOf("schemaVersion" to 2, "t" to "ACK",
            "battleId" to battleId, "sessionEpoch" to sessionEpoch.toString(),
            "clientCommandId" to receipt.clientCommandId,
            "inputId" to receipt.eventSeq?.toString(), "replayed" to receipt.replayed,
            "receipt" to linkedMapOf("verdict" to receipt.verdict.name,
                "reasonCode" to receipt.reasonCode,
                "deploymentRevisionAfter" to receipt.deploymentRevisionAfter?.toString())))

    private fun JsonNode.fieldsExactly(vararg keys: String) {
        require(isObject && fieldNames().asSequence().toSet() == keys.toSet()) { "invalid frame fields" }
    }
    private fun JsonNode.string(): String { require(isTextual); return textValue() }
    private fun JsonNode.integer(): Int { require(isIntegralNumber && canConvertToInt()); return intValue() }
    private fun JsonNode.sourceId(): String {
        if (!isTextual) return integer().also { require(it > 0) }.toString()
        return string().also {
            require(it.length <= 10 && it.matches(Regex("[1-9][0-9]*")))
            require(it.toInt() > 0)
        }
    }
    private fun JsonNode.longString(): Long = string().also {
        require(it.length <= 19 && it.matches(Regex("0|[1-9][0-9]*")))
    }.toLong()
}
