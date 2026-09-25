package opensamguk.logic.input

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Collections

data class EncounterUnitForce(
    val bugokId: Int,
    val ownerGeneralId: Int,
    val commanderGeneralId: Int,
    val crewTypeId: Int,
    val troops: Int,
    val training: Int,
    val morale: Int,
    val fatigue: Int,
    val provisions: Int,
    val commanderRetainerId: Int?,
) {
    init {
        require(listOf(bugokId, ownerGeneralId, commanderGeneralId, crewTypeId, troops).all { it > 0 })
        require(listOf(training, morale, fatigue).all { it in 0..100 } && provisions >= 0)
        require(commanderRetainerId == null || commanderRetainerId > 0)
    }
    internal fun toMetaValue(): Map<String, Any?> = linkedMapOf(
        "bugokId" to bugokId,
        "ownerGeneralId" to ownerGeneralId,
        "commanderGeneralId" to commanderGeneralId,
        "crewTypeId" to crewTypeId,
        "troops" to troops,
        "training" to training,
        "morale" to morale,
        "fatigue" to fatigue,
        "provisions" to provisions,
        "commanderRetainerId" to commanderRetainerId,
    )
    companion object {
        internal fun read(raw: Any?): EncounterUnitForce {
            val row = raw as? Map<*, *> ?: invalidForce()
            require(row.keys == setOf("bugokId", "ownerGeneralId", "commanderGeneralId", "crewTypeId", "troops", "training", "morale", "fatigue", "provisions", "commanderRetainerId"))
            return EncounterUnitForce(row["bugokId"] as? Int ?: invalidForce(), row["ownerGeneralId"] as? Int ?: invalidForce(), row["commanderGeneralId"] as? Int ?: invalidForce(), row["crewTypeId"] as? Int ?: invalidForce(), row["troops"] as? Int ?: invalidForce(), row["training"] as? Int ?: invalidForce(), row["morale"] as? Int ?: invalidForce(), row["fatigue"] as? Int ?: invalidForce(), row["provisions"] as? Int ?: invalidForce(), (if (row["commanderRetainerId"] == null) null else row["commanderRetainerId"] as? Int ?: invalidForce()))
        }
    }
}

data class EncounterCommanderForce(
    val generalId: Int,
    val leadership: Int,
    val strength: Int,
    val intelligence: Int,
    val politics: Int,
    val charm: Int,
) {
    init {
        require(generalId > 0)
        require(listOf(leadership, strength, intelligence, politics, charm).all { it >= 0 })
    }
    internal fun toMetaValue(): Map<String, Any?> = linkedMapOf(
        "generalId" to generalId,
        "leadership" to leadership,
        "strength" to strength,
        "intelligence" to intelligence,
        "politics" to politics,
        "charm" to charm,
    )
    companion object {
        internal fun read(raw: Any?): EncounterCommanderForce {
            val row = raw as? Map<*, *> ?: invalidForce()
            require(row.keys == setOf("generalId", "leadership", "strength", "intelligence", "politics", "charm"))
            return EncounterCommanderForce(row["generalId"] as? Int ?: invalidForce(), row["leadership"] as? Int ?: invalidForce(), row["strength"] as? Int ?: invalidForce(), row["intelligence"] as? Int ?: invalidForce(), row["politics"] as? Int ?: invalidForce(), row["charm"] as? Int ?: invalidForce())
        }
    }
}

/** Frozen observations, not live resources or approved combat coefficients. */
class EncounterForces(
    val encounterId: String,
    units: List<EncounterUnitForce>,
    commanders: List<EncounterCommanderForce>,
) {
    val units: List<EncounterUnitForce> = Collections.unmodifiableList(units.sortedBy { it.bugokId })
    val commanders: List<EncounterCommanderForce> = Collections.unmodifiableList(commanders.sortedBy { it.generalId })
    init {
        require(encounterId.matches(Regex("[0-9a-f]{64}")))
        require(this.units.isNotEmpty() && this.units.map { it.bugokId }.distinct().size == this.units.size)
        require(this.commanders.isNotEmpty() && this.commanders.map { it.generalId }.distinct().size == this.commanders.size)
    }
    fun requireBinding(encounter: CorpsEncounter) {
        require(encounterId == encounter.encounterId)
        val participants = listOf(encounter.attacker) + encounter.defenders
        require(units.map { it.bugokId }.toSet() == participants.flatMap { it.bugokIds }.toSet())
        require(commanders.map { it.generalId }.toSet() == participants.map { it.commanderGeneralId }.toSet())
        for (participant in participants) {
            val cards = units.filter { it.bugokId in participant.bugokIds }.map { it.commanderRetainerId }.distinct()
            require(cards.size == 1 && (cards.single() == null) ==
                (participant.ownerGeneralId == participant.commanderGeneralId)) { "Frozen commander card binding mismatch" }
        }
        for (participant in participants) for (id in participant.bugokIds) {
            val unit = units.single { it.bugokId == id }
            require(unit.ownerGeneralId == participant.ownerGeneralId && unit.commanderGeneralId == participant.commanderGeneralId)
        }
    }
    val snapshotId: String get() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            fun text(value: String) { val b = value.toByteArray(Charsets.UTF_8); out.writeInt(b.size); out.write(b) }
            text("hwihaEncounterForces:v1"); text(encounterId)
            out.writeInt(units.size)
            units.forEach { unit -> unit.toMetaValue().values.forEach { value ->
                out.writeBoolean(value != null); if (value != null) out.writeInt(value as Int)
            } }
            out.writeInt(commanders.size)
            commanders.forEach { commander -> commander.toMetaValue().values.forEach { out.writeInt(it as Int) } }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    fun toMetaValue(): Map<String, Any> = linkedMapOf("version" to 1, "encounterId" to encounterId,
        "snapshotId" to snapshotId, "units" to units.map { it.toMetaValue() }, "commanders" to commanders.map { it.toMetaValue() })
    companion object {
        const val META_KEY = "hwihaEncounterForces"
        fun read(meta: Map<String, Any?>, encounter: CorpsEncounter): EncounterForces? {
            if (META_KEY !in meta) return null
            val row = meta[META_KEY] as? Map<*, *> ?: invalidForce()
            require(row.keys == setOf("version", "encounterId", "snapshotId", "units", "commanders") && row["version"] == 1)
            val units = (row["units"] as? List<*>)?.map(EncounterUnitForce::read) ?: invalidForce()
            val commanders = (row["commanders"] as? List<*>)?.map(EncounterCommanderForce::read) ?: invalidForce()
            val result = EncounterForces(row["encounterId"] as? String ?: invalidForce(), units, commanders)
            require(units == result.units && commanders == result.commanders) { "Noncanonical frozen forces" }
            require(row["snapshotId"] == result.snapshotId) { "Frozen force hash mismatch" }
            result.requireBinding(encounter)
            return result
        }
    }
}
private fun invalidForce(): Nothing = throw IllegalArgumentException("Invalid frozen encounter force")
