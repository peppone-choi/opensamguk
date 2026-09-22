package opensamguk.logic.input

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.StrategicTopologySnapshot

/** Identity snapshot only: no resource copies and no assertion that defenders are allies. */
data class HwihaEncounterParticipant(
    val orderId: String,
    val ownerGeneralId: Int,
    val commanderGeneralId: Int,
    val nationId: Int,
    val bugokIds: List<Int>,
) {
    init {
        require(orderId.isNotBlank() && orderId.length <= 128)
        require(ownerGeneralId > 0 && commanderGeneralId > 0 && nationId >= 0)
        require(bugokIds.isNotEmpty() && bugokIds.all { it > 0 } && bugokIds.distinct().size == bugokIds.size)
    }

    fun requireBinding(corps: HwihaDeployedCorps) {
        require(orderId == corps.orderId && ownerGeneralId == corps.ownerGeneralId &&
            commanderGeneralId == corps.commanderGeneralId && nationId == corps.nationId &&
            bugokIds.sorted() == corps.bugokIds.sorted()) { "Encounter participant differs from deployed corps" }
    }

    internal fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "orderId" to orderId, "ownerGeneralId" to ownerGeneralId, "commanderGeneralId" to commanderGeneralId,
        "nationId" to nationId, "bugokIds" to bugokIds.sorted(),
    )

    companion object {
        fun from(corps: HwihaDeployedCorps) = HwihaEncounterParticipant(corps.orderId, corps.ownerGeneralId,
            corps.commanderGeneralId, corps.nationId, corps.bugokIds.sorted())

        internal fun read(raw: Any?): HwihaEncounterParticipant {
            val row = raw as? Map<*, *> ?: invalid()
            require(row.keys == setOf("orderId", "ownerGeneralId", "commanderGeneralId", "nationId", "bugokIds"))
            val ids = (row["bugokIds"] as? List<*>)?.map { it as? Int ?: invalid() } ?: invalid()
            require(ids == ids.sorted()) { "Encounter unit identities must be canonical" }
            return HwihaEncounterParticipant(row["orderId"] as? String ?: invalid(),
                row["ownerGeneralId"] as? Int ?: invalid(), row["commanderGeneralId"] as? Int ?: invalid(),
                row["nationId"] as? Int ?: invalid(), ids)
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid encounter participant")
    }
}

/** Pending encounter, not a battle result. The same value is stored on each participating commander. */
data class HwihaCorpsEncounter(
    val attacker: HwihaEncounterParticipant,
    val defenders: List<HwihaEncounterParticipant>,
    val province: StrategicNodeRef.LandProvince,
    val approachFrom: StrategicNodeRef.LandProvince,
    val phase: HwihaPhase,
    val topologyRevision: String,
    val topologyHash: String,
) {
    init {
        require(defenders.isNotEmpty())
        require(province != approachFrom && province.id.isNotBlank() && approachFrom.id.isNotBlank())
        require(topologyRevision.isNotBlank() && topologyHash.matches(Regex("[0-9a-f]{64}")))
        val participants = listOf(attacker) + defenders
        require(participants.map { it.commanderGeneralId }.distinct().size == participants.size)
        require(participants.map { it.orderId }.distinct().size == participants.size)
        val units = participants.flatMap { it.bugokIds }
        require(units.distinct().size == units.size) { "A unit cannot participate twice" }
    }

    private val orderedDefenders get() = defenders.sortedWith(compareBy(
        HwihaEncounterParticipant::commanderGeneralId, HwihaEncounterParticipant::ownerGeneralId,
        HwihaEncounterParticipant::orderId))

    /** Fixed schema plus UTF-8 byte lengths prevents delimiter and participant-boundary collisions. */
    val encounterId: String get() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { stream ->
            fun field(value: String) {
                val encoded = value.toByteArray(Charsets.UTF_8)
                stream.writeInt(encoded.size); stream.write(encoded)
            }
            fun participant(row: HwihaEncounterParticipant) {
                field(row.orderId); field(row.ownerGeneralId.toString()); field(row.commanderGeneralId.toString())
                field(row.nationId.toString()); field(row.bugokIds.size.toString())
                row.bugokIds.sorted().forEach { field(it.toString()) }
            }
            field("hwihaCorpsEncounter:v1"); field(topologyRevision); field(topologyHash)
            field(province.canonicalKey); field(approachFrom.canonicalKey)
            field(phase.year.toString()); field(phase.month.toString()); field(phase.phase.toString())
            participant(attacker); field(defenders.size.toString()); orderedDefenders.forEach(::participant)
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun requireParticipant(storageGeneralId: Int) {
        require(storageGeneralId == attacker.commanderGeneralId || defenders.any { it.commanderGeneralId == storageGeneralId }) {
            "Encounter must be stored on a participating commander"
        }
    }

    fun toMetaValue(): Map<String, Any> = linkedMapOf(
        "version" to 1, "encounterId" to encounterId, "attacker" to attacker.toMetaValue(),
        "defenders" to orderedDefenders.map { it.toMetaValue() }, "province" to province.id,
        "approachFrom" to approachFrom.id, "phase" to phase.toMetaValue(),
        "topologyRevision" to topologyRevision, "topologyHash" to topologyHash,
    )

    companion object {
        const val META_KEY = "hwihaCorpsEncounter"
        private val fields = setOf("version", "encounterId", "attacker", "defenders", "province", "approachFrom",
            "phase", "topologyRevision", "topologyHash")

        fun read(meta: Map<String, Any?>, topology: StrategicTopologySnapshot): HwihaCorpsEncounter? {
            if (META_KEY !in meta) return null
            val row = meta[META_KEY] as? Map<*, *> ?: invalid()
            require(row.keys == fields && row["version"] == 1) { "Invalid encounter schema" }
            val encounter = HwihaCorpsEncounter(HwihaEncounterParticipant.read(row["attacker"]),
                (row["defenders"] as? List<*>)?.map(HwihaEncounterParticipant::read) ?: invalid(),
                StrategicNodeRef.LandProvince(row["province"] as? String ?: invalid()),
                StrategicNodeRef.LandProvince(row["approachFrom"] as? String ?: invalid()), HwihaPhase.read(row["phase"]),
                row["topologyRevision"] as? String ?: invalid(), row["topologyHash"] as? String ?: invalid())
            require(encounter.topologyRevision == topology.topologyRevision && encounter.topologyHash == topology.contentHash)
            require(topology.containsNode(encounter.province) && topology.containsNode(encounter.approachFrom))
            require(row["encounterId"] == encounter.encounterId) { "Encounter identity mismatch" }
            require(encounter.defenders == encounter.orderedDefenders) { "Encounter defenders must be canonical" }
            return encounter
        }
        private fun invalid(): Nothing = throw IllegalArgumentException("Invalid HWIHA corps encounter")
    }
}
