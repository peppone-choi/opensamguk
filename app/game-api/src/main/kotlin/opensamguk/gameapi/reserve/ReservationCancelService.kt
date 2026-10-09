package opensamguk.gameapi.reserve

import opensamguk.gameapi.config.GameApiProcessWorld
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.util.UUID

@Service
class ReservationCancelService(private val repository: ReservationCancelRepository, processWorld: GameApiProcessWorld) {
    private val world = processWorld.worldId.value

    fun cancel(owner: Long?, actor: Int?, slot: Int?, revision: String?, key: String?): ReservationCancelDto {
        if (owner == null || owner !in 1..Int.MAX_VALUE.toLong()) throw ReservationCancelRejected("UNAUTHORIZED", 401)
        if (actor == null || actor <= 0 || slot == null || slot < 0) throw ReservationCancelRejected("INVALID_SLOT", 400)
        val identity = uuid(key, "INVALID_IDEMPOTENCY_KEY")
        val expected = uuid(revision, "INVALID_REVISION")
        val intent = listOf("v1", "QUEUE_MUTATION", ReservationCancelRepository.OPERATION,
            world.toString(), owner.toString(), actor.toString(), slot.toString(), expected).joinToString("\u001f")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(intent.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        repository.receipt(identity, owner.toInt(), fingerprint)?.let { return it }
        return try {
            repository.cancel(identity, owner.toInt(), actor, slot, expected, fingerprint)
        } catch (denied: ReservationCancelRejected) {
            // The losing transaction is rolled back before a concurrently committed receipt is read.
            repository.receipt(identity, owner.toInt(), fingerprint) ?: throw denied
        }
    }

    private fun uuid(raw: String?, code: String): String {
        val parsed = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (parsed == null || !parsed.toString().equals(raw, ignoreCase = true)) throw ReservationCancelRejected(code, 400)
        return parsed.toString()
    }
}
