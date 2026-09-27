package opensamguk.logic.vassal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import opensamguk.logic.economy.Resources

data class VassalTributeReceipt(
    val contractId: String,
    val year: Int,
    val month: Int,
    val due: Resources,
    val paid: Resources,
    val unpaid: Resources,
) {
    init {
        require(contractId.isNotBlank() && year > 0 && month in 1..12)
        require(paid.credit(unpaid) == due) { "tribute receipt does not conserve resources" }
    }
}

data class VassalState(val contracts: List<VassalContract>, val receipts: List<VassalTributeReceipt>) {
    init {
        require(contracts.map { it.id }.distinct().size == contracts.size)
        val ids = contracts.map { it.id }.toSet()
        require(receipts.all { it.contractId in ids }) { "orphan tribute receipt" }
        require(receipts.map { Triple(it.contractId, it.year, it.month) }.distinct().size == receipts.size) {
            "duplicate monthly tribute receipt"
        }
    }
}

data class VassalReadView(val contract: VassalContract, val tributeHistory: List<VassalTributeReceipt>)

object VassalStateCodec {
    const val META_KEY = "vassalContracts"

    fun view(state: VassalState, contractId: String): VassalReadView? {
        val contract = state.contracts.singleOrNull { it.id == contractId } ?: return null
        return VassalReadView(contract, state.receipts.filter { it.contractId == contractId }.sortedWith(compareBy({ it.year }, { it.month })))
    }

    fun encode(state: VassalState): String = buildJsonObject {
        put("version", 1)
        put("contracts", buildJsonArray {
            state.contracts.sortedBy { it.id }.forEach { contract ->
                add(buildJsonObject {
                    put("id", contract.id)
                    put("sovereignLordId", contract.sovereignLordId)
                    put("vassalLordId", contract.vassalLordId)
                    put("nationId", contract.nationId)
                    put("fiefCountyIds", buildJsonArray { contract.fiefCountyIds.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("tributePercent", contract.tributePercent)
                    put("reinforcementTroops", contract.reinforcementTroops)
                    put("autonomy", buildJsonArray { contract.autonomy.map { it.name }.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("diplomacyRight", contract.diplomacyRight.name)
                    put("breachConditions", buildJsonArray { contract.breachConditions.map { it.name }.sorted().forEach { add(JsonPrimitive(it)) } })
                    put("loyalty", contract.loyalty)
                    put("signedTurn", contract.signedTurn)
                    contract.expiresTurn?.let { put("expiresTurn", it) }
                    contract.endedTurn?.let { put("endedTurn", it) }
                })
            }
        })
        put("receipts", buildJsonArray {
            state.receipts.sortedWith(compareBy({ it.contractId }, { it.year }, { it.month })).forEach { receipt ->
                add(buildJsonObject {
                    put("contractId", receipt.contractId)
                    put("year", receipt.year)
                    put("month", receipt.month)
                    put("due", resources(receipt.due))
                    put("paid", resources(receipt.paid))
                    put("unpaid", resources(receipt.unpaid))
                })
            }
        })
    }.toString()

    fun decode(raw: String?): VassalState {
        if (raw == null) return VassalState(emptyList(), emptyList())
        val root = Json.parseToJsonElement(raw) as? JsonObject ?: invalid()
        require(root.keys == setOf("version", "contracts", "receipts") && root["version"]?.jsonPrimitive?.int == 1)
        val contracts = (root["contracts"] as? JsonArray ?: invalid()).map { element ->
            val row = element as? JsonObject ?: invalid()
            require(row.keys.containsAll(setOf("id", "sovereignLordId", "vassalLordId", "nationId", "fiefCountyIds", "tributePercent", "reinforcementTroops", "autonomy", "diplomacyRight", "breachConditions", "loyalty", "signedTurn")))
            require(row.keys.all { it in setOf("id", "sovereignLordId", "vassalLordId", "nationId", "fiefCountyIds", "tributePercent", "reinforcementTroops", "autonomy", "diplomacyRight", "breachConditions", "loyalty", "signedTurn", "expiresTurn", "endedTurn") })
            VassalContract(
                id = row.string("id"),
                sovereignLordId = row.number("sovereignLordId"),
                vassalLordId = row.number("vassalLordId"),
                nationId = row.number("nationId"),
                fiefCountyIds = row.integerSet("fiefCountyIds"),
                tributePercent = row.number("tributePercent"),
                reinforcementTroops = row.number("reinforcementTroops"),
                autonomy = row.stringSet("autonomy").map { VassalAutonomy.valueOf(it) }.toSet(),
                diplomacyRight = VassalDiplomacyRight.valueOf(row.string("diplomacyRight")),
                breachConditions = row.stringSet("breachConditions").map { VassalBreachKind.valueOf(it) }.toSet(),
                loyalty = row.number("loyalty"),
                signedTurn = row.long("signedTurn"),
                expiresTurn = row.optionalLong("expiresTurn"),
                endedTurn = row.optionalLong("endedTurn"),
            )
        }
        val receipts = (root["receipts"] as? JsonArray ?: invalid()).map { element ->
            val row = element as? JsonObject ?: invalid()
            require(row.keys == setOf("contractId", "year", "month", "due", "paid", "unpaid"))
            VassalTributeReceipt(row.string("contractId"), row.number("year"), row.number("month"),
                parseResources(row["due"]), parseResources(row["paid"]), parseResources(row["unpaid"]))
        }
        return VassalState(contracts, receipts)
    }

    private fun resources(value: Resources) = buildJsonObject {
        put("money", value.money)
        put("grain", value.grain)
        put("iron", value.iron)
        put("timber", value.timber)
        put("horses", value.horses)
    }

    private fun parseResources(value: kotlinx.serialization.json.JsonElement?): Resources {
        val row = value as? JsonObject ?: invalid()
        require(row.keys == setOf("money", "grain", "iron", "timber", "horses"))
        return Resources(row.long("money"), row.long("grain"), row.long("iron"), row.long("timber"), row.long("horses"))
    }

    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotBlank() }?.content ?: invalid()
    private fun JsonObject.number(key: String) = (get(key) as? JsonPrimitive)?.int ?: invalid()
    private fun JsonObject.long(key: String) = (get(key) as? JsonPrimitive)?.long ?: invalid()
    private fun JsonObject.optionalLong(key: String) = get(key)?.let { (it as? JsonPrimitive)?.long ?: invalid() }
    private fun JsonObject.integerSet(key: String): Set<Int> {
        val values = (get(key) as? JsonArray ?: invalid()).map { (it as? JsonPrimitive)?.int ?: invalid() }
        require(values.distinct().size == values.size)
        return values.toSet()
    }
    private fun JsonObject.stringSet(key: String): Set<String> {
        val values = (get(key) as? JsonArray ?: invalid()).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: invalid() }
        require(values.distinct().size == values.size)
        return values.toSet()
    }
    private fun invalid(): Nothing = throw IllegalArgumentException("Invalid vassal state meta")
}
