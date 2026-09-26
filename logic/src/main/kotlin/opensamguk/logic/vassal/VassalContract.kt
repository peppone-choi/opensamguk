package opensamguk.logic.vassal

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.content.ContentLedgerValidator

enum class VassalAutonomy { COUNTY_POLICY, TAX_ALLOCATION, GARRISON_COMMAND }
enum class VassalDiplomacyRight { NONE, WITH_APPROVAL, INDEPENDENT }
enum class VassalBreachKind { MISSED_TRIBUTE, REFUSED_REINFORCEMENT, UNAUTHORIZED_DIPLOMACY }

data class VassalLord(val id: Int, val nationId: Int, val isLord: Boolean, val isRuler: Boolean) {
    init { require(id > 0 && nationId > 0) }
}

data class VassalContract(
    val id: String,
    val sovereignLordId: Int,
    val vassalLordId: Int,
    val nationId: Int,
    val fiefCountyIds: Set<Int>,
    val tributePercent: Int,
    val reinforcementTroops: Int,
    val autonomy: Set<VassalAutonomy>,
    val diplomacyRight: VassalDiplomacyRight,
    val breachConditions: Set<VassalBreachKind>,
    val loyalty: Int,
    val signedTurn: Long,
    val expiresTurn: Long? = null,
    val endedTurn: Long? = null,
) {
    init {
        require(id.isNotBlank() && sovereignLordId > 0 && vassalLordId > 0 && sovereignLordId != vassalLordId)
        require(nationId > 0 && fiefCountyIds.isNotEmpty() && fiefCountyIds.all { it > 0 })
        require(tributePercent in 0..100 && reinforcementTroops >= 0 && loyalty in 0..100 && signedTurn >= 0)
        require(expiresTurn == null || expiresTurn >= signedTurn)
        require(endedTurn == null || endedTurn >= signedTurn)
    }

    val active: Boolean get() = endedTurn == null
    fun activeAt(turn: Long): Boolean = turn >= signedTurn && (expiresTurn == null || turn <= expiresTurn) && (endedTurn == null || turn < endedTurn)
}

data class VassalRules(
    val defaultTributePercent: Int,
    val minimumTributePercent: Int,
    val maximumTributePercent: Int,
    val defaultReinforcementTroops: Int,
    val reinforcementReplyTurns: Int,
    val minimumReducedPercent: Int,
    val missedTributeMonthsForBreach: Int,
    val loyaltyLossOnBreach: Int,
) {
    init {
        require(minimumTributePercent in 0..maximumTributePercent && maximumTributePercent <= 100)
        require(defaultTributePercent in minimumTributePercent..maximumTributePercent)
        require(defaultReinforcementTroops >= 0 && reinforcementReplyTurns > 0)
        require(minimumReducedPercent in 1..100 && missedTributeMonthsForBreach > 0)
        require(loyaltyLossOnBreach in 0..100)
    }

    companion object {
        fun fromJson(raw: String): VassalRules {
            ContentLedgerValidator.requireValid(raw)
            val row = Json.parseToJsonElement(raw).jsonObject["rows"]!!.jsonArray.single().jsonObject
            require(row["id"]?.jsonPrimitive?.content == "vassal.contract-terms")
            val values = row["values"]!!.jsonObject
            fun number(key: String) = values.getValue(key).jsonObject.getValue("value").jsonPrimitive.int
            return VassalRules(number("defaultTributePercent"), number("minimumTributePercent"), number("maximumTributePercent"),
                number("defaultReinforcementTroops"), number("reinforcementReplyTurns"), number("minimumReducedPercent"),
                number("missedTributeMonthsForBreach"), number("loyaltyLossOnBreach"))
        }

        fun loadClasspath(): VassalRules = fromJson(requireNotNull(VassalRules::class.java.getResourceAsStream("/vassal/vassal-rules.json")) {
            "vassal rules ledger missing"
        }.use { it.readBytes().toString(Charsets.UTF_8) })
    }
}

object VassalContracts {
    /** Nation membership and lordship come from events, never fief ownership or an office title. */
    fun validate(
        contracts: Collection<VassalContract>,
        lords: Collection<VassalLord>,
        countyNationById: Map<Int, Int>,
        retinueRootByPerson: Map<Int, Int>,
        rules: VassalRules,
        atTurn: Long,
    ) {
        require(atTurn >= 0)
        require(contracts.map { it.id }.distinct().size == contracts.size) { "duplicate vassal contract" }
        require(lords.map { it.id }.distinct().size == lords.size) { "duplicate lord" }
        val byId = lords.associateBy { it.id }
        val active = contracts.filter { it.activeAt(atTurn) }
        require(active.map { it.vassalLordId }.distinct().size == active.size) { "one active sovereign per vassal" }
        val vassalIds = active.map { it.vassalLordId }.toSet()
        require(active.none { it.sovereignLordId in vassalIds }) { "nested vassal is forbidden" }
        val fiefs = mutableSetOf<Int>()
        active.sortedBy { it.id }.forEach { contract ->
            val sovereign = requireNotNull(byId[contract.sovereignLordId]) { "missing sovereign lord" }
            val vassal = requireNotNull(byId[contract.vassalLordId]) { "missing vassal lord" }
            require(sovereign.isLord && sovereign.isRuler && vassal.isLord && !vassal.isRuler)
            require(sovereign.nationId == contract.nationId && vassal.nationId == contract.nationId)
            require(retinueRootByPerson[sovereign.id] == sovereign.id && retinueRootByPerson[vassal.id] == vassal.id) {
                "a vassal lord cannot be a human retinue card"
            }
            require(contract.tributePercent in rules.minimumTributePercent..rules.maximumTributePercent)
            require(contract.fiefCountyIds.all { countyNationById[it] == contract.nationId && fiefs.add(it) }) {
                "fief is foreign, missing, or assigned twice"
            }
        }
    }

    fun followersOfVassal(vassalLordId: Int, followersByOwner: Map<Int, List<Int>>): List<Int> {
        val followers = requireNotNull(followersByOwner[vassalLordId]) { "vassal retinue root missing" }
        require(followers.firstOrNull() == vassalLordId && followers.distinct().size == followers.size)
        return followers
    }

    fun breachKinds(
        contract: VassalContract,
        consecutiveUnpaidMonths: Int,
        reinforcementRefused: Boolean,
        unauthorizedDiplomacy: Boolean,
        rules: VassalRules,
    ): Set<VassalBreachKind> = buildSet {
        require(consecutiveUnpaidMonths >= 0)
        if (consecutiveUnpaidMonths >= rules.missedTributeMonthsForBreach && VassalBreachKind.MISSED_TRIBUTE in contract.breachConditions) add(VassalBreachKind.MISSED_TRIBUTE)
        if (reinforcementRefused && VassalBreachKind.REFUSED_REINFORCEMENT in contract.breachConditions) add(VassalBreachKind.REFUSED_REINFORCEMENT)
        if (unauthorizedDiplomacy && VassalBreachKind.UNAUTHORIZED_DIPLOMACY in contract.breachConditions) add(VassalBreachKind.UNAUTHORIZED_DIPLOMACY)
    }

    /** Breach changes loyalty; independence remains a separate political event. */
    fun loyaltyAfterBreach(contract: VassalContract, kinds: Set<VassalBreachKind>, rules: VassalRules): Int =
        if (kinds.isEmpty()) contract.loyalty else (contract.loyalty - rules.loyaltyLossOnBreach).coerceAtLeast(0)
}
