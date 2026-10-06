package opensamguk.logic.creation

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.nio.charset.StandardCharsets

/** Stable semantic body hash for the (account, world, clientRequestId) admission key. */
object CreationRequestFingerprint {
    sealed interface Choice {
        data class Custom(val value: CreationAdmission.Custom) : Choice
        data class Historical(val value: CreationAdmission.Historical) : Choice
    }

    private fun DataOutputStream.writeString(value: String) {
        val utf8 = value.toByteArray(StandardCharsets.UTF_8)
        writeInt(utf8.size)
        write(utf8)
    }

    fun sha256(expectedWorldId: Int, choice: Choice): String {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(2) // fingerprint schema version; CUSTOM role is part of request identity
            out.writeInt(expectedWorldId)
            when (choice) {
                is Choice.Custom -> {
                    out.writeByte(1)
                    out.writeString(choice.value.name) // raw name; normalization cannot hide a changed body
                    out.writeInt(choice.value.nativeCountyId)
                    choice.value.stats.values().forEach(out::writeInt)
                    out.writeString(choice.value.ideologyId)
                    out.writeString(choice.value.traitId)
                    out.writeString(choice.value.role.name)
                }
                is Choice.Historical -> {
                    out.writeByte(2)
                    out.writeInt(choice.value.generalId)
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    /** Internal command-inbox key; the public requestId remains the client's unchanged UUID. */
    fun commandRequestId(accountId: Long, worldId: Int, clientRequestId: String): String {
        require(accountId > 0 && worldId > 0)
        val uuid = UUID.fromString(clientRequestId)
        require(uuid.toString() == clientRequestId) { "clientRequestId must be canonical lowercase UUID" }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeString("general-creation-command-v1")
            out.writeLong(accountId)
            out.writeInt(worldId)
            out.writeLong(uuid.mostSignificantBits)
            out.writeLong(uuid.leastSignificantBits)
        }
        return "creation:" + MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
