package opensamguk.gameapi.battle.realtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import opensamguk.common.world.WorldId
import opensamguk.infra.battle.realtime.BattleSessionHead
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.infra.battle.realtime.FrozenBattleTicket

data class BattleJoinIdentity(
    val worldId: WorldId,
    val battleId: String,
    val accountId: Int,
    val participantId: Int,
    val generalId: Int,
    val side: String,
    val sessionEpoch: Long,
    val authorityRevision: Long,
    val expiresAt: Instant,
)

/** The caller must supply a clock in the DB lease/deadline time domain when wiring this service. */
class BattleJoinTicketService(
    private val store: BattleSessionStore,
    secret: ByteArray,
    private val clock: Clock,
) {
    private val key = secret.copyOf().also { require(it.size >= 32) { "battle join secret must be at least 32 bytes" } }
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val prefix = "BTJ1"

    fun issue(worldId: WorldId, battleId: String, authenticatedAccountId: Int): String {
        require(authenticatedAccountId > 0)
        val ticket = store.ticket(worldId, battleId) ?: throw SecurityException("battle join unavailable")
        val head = store.head(worldId, battleId) ?: throw SecurityException("battle join unavailable")
        val now = clock.instant()
        val participant = activeParticipant(ticket, head, authenticatedAccountId, now)
        val expiresAt = minOf(now.plusSeconds(60), head.deadlineAt)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(worldId.value)
            out.writeUTF(battleId)
            out.writeInt(authenticatedAccountId)
            out.writeInt(participant.participantId)
            out.writeInt(participant.generalId)
            out.writeUTF(participant.side)
            out.writeLong(head.sessionEpoch)
            out.writeLong(participant.authorityRevision)
            out.writeLong(now.toEpochMilli())
            out.writeLong(expiresAt.toEpochMilli())
        }
        val unsigned = "$prefix.${encoder.encodeToString(bytes.toByteArray())}"
        return "$unsigned.${encoder.encodeToString(mac(unsigned))}"
    }

    fun verify(token: String, worldId: WorldId, battleId: String,
               authenticatedAccountId: Int): BattleJoinIdentity {
        try {
            require(authenticatedAccountId > 0 && token.length in 20..1024)
            val pieces = token.split('.')
            require(pieces.size == 3 && pieces[0] == prefix)
            val unsigned = "${pieces[0]}.${pieces[1]}"
            val payload = decoder.decode(pieces[1])
            val signature = decoder.decode(pieces[2])
            require(payload.size in 1..512 && signature.size == 32)
            require(encoder.encodeToString(payload) == pieces[1] && encoder.encodeToString(signature) == pieces[2])
            require(MessageDigest.isEqual(signature, mac(unsigned)))
            val source = ByteArrayInputStream(payload)
            val claims = DataInputStream(source).use { input ->
                val signedWorldId = WorldId(input.readInt())
                val signedBattleId = input.readUTF()
                val signedAccountId = input.readInt()
                val participantId = input.readInt()
                val generalId = input.readInt()
                val side = input.readUTF()
                val epoch = input.readLong()
                val authorityRevision = input.readLong()
                val issuedAt = Instant.ofEpochMilli(input.readLong())
                val expiresAt = Instant.ofEpochMilli(input.readLong())
                require(signedWorldId == worldId && signedBattleId == battleId &&
                    signedAccountId == authenticatedAccountId && side in setOf("ATTACKER", "DEFENDER"))
                val now = clock.instant()
                require(!issuedAt.isAfter(now.plusSeconds(5)) && expiresAt.isAfter(now) &&
                    expiresAt.isAfter(issuedAt) && !expiresAt.isAfter(issuedAt.plusSeconds(60)))
                BattleJoinIdentity(signedWorldId, signedBattleId, signedAccountId,
                    participantId, generalId, side, epoch, authorityRevision, expiresAt)
            }
            require(source.available() == 0)
            val ticket = store.ticket(worldId, battleId) ?: error("battle ticket missing")
            val head = store.head(worldId, battleId) ?: error("battle session missing")
            val participant = activeParticipant(ticket, head, authenticatedAccountId, clock.instant())
            require(claims.participantId == participant.participantId &&
                claims.generalId == participant.generalId && claims.side == participant.side &&
                claims.authorityRevision == participant.authorityRevision &&
                claims.sessionEpoch == head.sessionEpoch && !claims.expiresAt.isAfter(head.deadlineAt))
            return claims
        } catch (_: Exception) {
            throw SecurityException("invalid battle join ticket")
        }
    }

    private fun activeParticipant(ticket: FrozenBattleTicket, head: BattleSessionHead,
                                  accountId: Int, now: Instant): FrozenBattleParticipant {
        if (ticket.worldId != head.worldId || ticket.battleId != head.battleId ||
            ticket.deadlineAt != head.deadlineAt || head.sessionEpoch <= 0 ||
            head.phase !in setOf(BattleSessionPhase.JOINING, BattleSessionPhase.RUNNING) ||
            head.leaseUntil?.isAfter(now) != true || !head.deadlineAt.isAfter(now))
            throw SecurityException("battle join unavailable")
        return ticket.participants.singleOrNull { it.accountId == accountId }
            ?: throw SecurityException("battle participant unavailable")
    }

    private fun mac(unsigned: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(unsigned.toByteArray(StandardCharsets.US_ASCII))
    }
}
