package opensamguk.logic.input

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections

/** Pairwise combat hostility at approach. Non-hostile never implies an alliance. */
class EncounterRelations private constructor(val encounterId: String, pairs: List<PairRelation>) {
    data class PairRelation(val firstCommanderId: Int, val secondCommanderId: Int, val hostile: Boolean) {
        init { require(firstCommanderId > 0 && secondCommanderId > firstCommanderId) }
    }
    val pairs: List<PairRelation> = Collections.unmodifiableList(ArrayList(pairs.sortedWith(
        compareBy(PairRelation::firstCommanderId, PairRelation::secondCommanderId))))

    init {
        require(encounterId.matches(Regex("[0-9a-f]{64}")))
        require(this.pairs.map { it.firstCommanderId to it.secondCommanderId }.distinct().size == this.pairs.size)
    }

    val snapshotId: String get() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { stream ->
            stream.writeUTF("hwihaEncounterRelations:v1"); stream.writeUTF(encounterId)
            stream.writeInt(pairs.size)
            pairs.forEach { stream.writeInt(it.firstCommanderId); stream.writeInt(it.secondCommanderId); stream.writeBoolean(it.hostile) }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun isHostile(first: Int, second: Int): Boolean {
        require(first != second)
        return requireNotNull(pairs.singleOrNull { it.firstCommanderId == minOf(first,second) &&
            it.secondCommanderId == maxOf(first,second) }) { "Unknown encounter pair" }.hostile
    }

    fun requireBinding(encounter: CorpsEncounter) {
        require(encounterId == encounter.encounterId)
        val ids = (listOf(encounter.attacker)+encounter.defenders).map { it.commanderGeneralId }.sorted()
        val expected = ids.flatMapIndexed { index, first -> ids.drop(index+1).map { first to it } }
        require(pairs.map { it.firstCommanderId to it.secondCommanderId } == expected) { "Incomplete encounter relations" }
        require(encounter.defenders.all { isHostile(encounter.attacker.commanderGeneralId,it.commanderGeneralId) })
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "encounterId" to encounterId,
        "snapshotId" to snapshotId, "pairs" to pairs.map { linkedMapOf(
            "firstCommanderId" to it.firstCommanderId, "secondCommanderId" to it.secondCommanderId, "hostile" to it.hostile) })

    companion object {
        const val META_KEY = "hwihaEncounterRelations"

        fun capture(encounter: CorpsEncounter, state: DeploymentProjection, activeWars: Set<Pair<Int, Int>>): EncounterRelations {
            val participants = (listOf(encounter.attacker)+encounter.defenders).sortedBy { it.commanderGeneralId }
            val hostileByCommander = participants.associate { participant ->
                participant.requireBinding(requireNotNull(state.deployed.singleOrNull { it.commanderGeneralId == participant.commanderGeneralId }))
                require(state.people.singleOrNull { it.id == participant.commanderGeneralId }?.node == encounter.province)
                val assessment = MilitaryPresence.assess(participant.commanderGeneralId,state,activeWars)
                    as? MilitaryPresenceAssessment.Ready ?: throw IllegalArgumentException("Military relation authority unavailable")
                participant.commanderGeneralId to assessment.hostileCorps.mapTo(hashSetOf()) { it.commanderGeneralId }
            }
            val pairs = participants.flatMapIndexed { index, first -> participants.drop(index+1).map { second ->
                // A blocking hostile encounter permits both participants to fight back.
                PairRelation(first.commanderGeneralId,second.commanderGeneralId,
                    second.commanderGeneralId in hostileByCommander.getValue(first.commanderGeneralId) ||
                    first.commanderGeneralId in hostileByCommander.getValue(second.commanderGeneralId))
            } }
            return EncounterRelations(encounter.encounterId,pairs).also { it.requireBinding(encounter) }
        }

        /** Frozen relation history does not change when live diplomacy changes later. */
        fun read(meta: Map<String, Any?>, encounter: CorpsEncounter): EncounterRelations? {
            if (META_KEY !in meta) return null
            val raw = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(raw.keys == setOf("version","encounterId","snapshotId","pairs") && raw["version"] == 1)
            val pairs = (raw["pairs"] as? List<*>)?.map { item ->
                val row = item as? Map<*, *> ?: invalid()
                require(row.keys == setOf("firstCommanderId","secondCommanderId","hostile"))
                PairRelation(row["firstCommanderId"] as? Int ?: invalid(),row["secondCommanderId"] as? Int ?: invalid(),
                    row["hostile"] as? Boolean ?: invalid())
            } ?: invalid()
            return EncounterRelations(raw["encounterId"] as? String ?: invalid(),pairs).also {
                it.requireBinding(encounter)
                require(it.pairs == pairs && raw["snapshotId"] == it.snapshotId)
            }
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid encounter relation snapshot")
    }
}
