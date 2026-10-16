package opensamguk.logic.battle.realtime

import opensamguk.logic.input.CorpsEncounter
import opensamguk.logic.input.EncounterForces
import opensamguk.logic.input.EncounterRelations
import java.util.Collections

/** The campaign producer and JDBC finalizer apply exactly the installed reader's native-owner rules. */
object CampaignBattleOwnerClassifier {
    data class Owner(val accountId: Int?, val npcState: Int, val playable: Boolean)
    sealed interface Result
    data class Accepted(val owners: Map<Int, Owner>) : Result
    data class Rejected(val detail: String, val ownerGeneralId: Int?) : Result
    private val canonicalAccount = Regex("[1-9][0-9]{0,9}")

    fun classify(worldId: Int, ownerIds: Set<Int>, native: List<BattleOwnerNativeGeneral>): Result {
        if (ownerIds.isEmpty() || ownerIds.any { it <= 0 } ||
            native.any { it.worldId != worldId || it.generalId <= 0 } ||
            native.map { it.generalId }.distinct().size != native.size)
            return Rejected("BINDING_REJECTED", null)
        val byId = native.associateBy { it.generalId }
        val owners = linkedMapOf<Int, Owner>()
        for (id in ownerIds.sorted()) {
            val row = byId[id] ?: return Rejected("BINDING_REJECTED", id)
            if (row.npcState < 0) return Rejected("BINDING_REJECTED", id)
            val user = row.userId
            val account = if (user == null) null else {
                if (!canonicalAccount.matches(user) || user.toIntOrNull() == null)
                    return Rejected("NONCANONICAL_ACCOUNT", id)
                user.toInt()
            }
            if (account == null && row.npcState < 2) return Rejected("HUMAN_METADATA_MISSING", id)
            if (account != null && row.npcState >= 2) return Rejected("NPC_WITH_ACCOUNT", id)
            if (account != null && native.count { it.userId == user } != 1)
                return Rejected("DUPLICATE_ACCOUNT", id)
            owners[id] = Owner(account, row.npcState, account != null)
        }
        return Accepted(Collections.unmodifiableMap(owners))
    }
}

/** Sealed observations only. No fence, resource revision, or authority is trusted until JDBC finalization. */
class CampaignBattleOriginDraft(
    val worldId: Int,
    encounter: CorpsEncounter,
    val forces: EncounterForces,
    val relations: EncounterRelations,
    val tilesHash: String,
) {
    val encounter: CorpsEncounter = encounter.copy(
        attacker = encounter.attacker.copy(bugokIds = Collections.unmodifiableList(encounter.attacker.bugokIds.sorted())),
        defenders = Collections.unmodifiableList(encounter.defenders.map {
            it.copy(bugokIds = Collections.unmodifiableList(it.bugokIds.sorted()))
        }.sortedWith(compareBy({ it.commanderGeneralId }, { it.ownerGeneralId }, { it.orderId }))),
    )
    init {
        require(worldId > 0 && tilesHash.matches(Regex("[0-9a-f]{64}")))
        forces.requireBinding(this.encounter)
        relations.requireBinding(this.encounter)
        require(this.encounter.defenders.map { it.nationId }.distinct().size == 1)
    }
}
