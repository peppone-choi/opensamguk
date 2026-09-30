package opensamguk.gameapi.battle.realtime

import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import opensamguk.common.world.WorldId
import opensamguk.gameapi.owner.GeneralResolver
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PingMessage
import org.springframework.web.socket.WebSocketSession

/** Process-local admission slots permit one connection per account and battle. */
class BattleWebSocketSessions(
    private val tickets: BattleJoinTicketService,
    private val generals: GeneralResolver,
    private val monotonicNanos: () -> Long = System::nanoTime,
    private val pendingTimeout: Duration = Duration.ofSeconds(10),
    private val pingInterval: Duration = Duration.ofSeconds(15),
    private val idleTimeout: Duration = Duration.ofSeconds(60),
) {
    init {
        require(!pendingTimeout.isZero && !pendingTimeout.isNegative)
        require(!pingInterval.isZero && !pingInterval.isNegative)
        require(idleTimeout > pingInterval)
    }

    private data class Key(val serverId: String, val worldId: WorldId, val battleId: String, val accountId: Int)

    class Reservation internal constructor(val identity: BattleJoinIdentity, val createdAtNanos: Long) {
        @Volatile var session: WebSocketSession? = null
        val lastPongNanos = AtomicLong(createdAtNanos)
        val lastPingNanos = AtomicLong(createdAtNanos)
    }

    private val slots = ConcurrentHashMap<Key, Reservation>()

    fun reserve(identity: BattleJoinIdentity): Reservation {
        val reservation = Reservation(identity, monotonicNanos())
        val previous = slots.put(key(identity), reservation)
        previous?.let { old ->
            if (old.session != null) close(old, CloseStatus.GOING_AWAY)
        }
        return reservation
    }

    fun attach(reservation: Reservation, session: WebSocketSession): Boolean {
        var attached = false
        slots.computeIfPresent(key(reservation.identity)) { _, current ->
            if (current === reservation && current.session == null) {
                current.session = session
                current.lastPongNanos.set(monotonicNanos())
                current.lastPingNanos.set(monotonicNanos())
                attached = true
            }
            current
        }
        return attached
    }

    fun release(reservation: Reservation) {
        slots.remove(key(reservation.identity), reservation)
    }

    fun pong(session: WebSocketSession) {
        val reservation = reservation(session) ?: return
        if (slots[key(reservation.identity)] === reservation) {
            reservation.lastPongNanos.set(monotonicNanos())
        }
    }

    fun close(session: WebSocketSession, status: CloseStatus) {
        val reservation = reservation(session)
        if (reservation == null) {
            try {
                if (session.isOpen) session.close(status)
            } catch (_: Exception) {
                // No admission slot exists to retain.
            }
        } else {
            close(reservation, status)
        }
    }

    private fun close(reservation: Reservation, status: CloseStatus) {
        val session = reservation.session ?: return
        try {
            if (session.isOpen) session.close(status)
        } catch (_: Exception) {
            // A broken transport cannot retain an admission slot.
        } finally {
            release(reservation)
        }
    }

    @Scheduled(fixedDelay = 5_000)
    fun sweep() {
        val now = monotonicNanos()
        for (reservation in slots.values) {
            val session = reservation.session
            if (session == null) {
                if (elapsed(now, reservation.createdAtNanos, pendingTimeout)) release(reservation)
                continue
            }
            if (!session.isOpen) {
                release(reservation)
                continue
            }
            val identity = reservation.identity
            val current = try {
                tickets.isCurrent(identity) && generals.resolveGeneralId(identity.accountId.toLong()) == identity.generalId
            } catch (_: Exception) {
                false
            }
            if (!current) {
                close(session, CloseStatus.POLICY_VIOLATION)
            } else if (elapsed(now, reservation.lastPongNanos.get(), idleTimeout)) {
                close(session, CloseStatus.GOING_AWAY)
            } else if (elapsed(now, reservation.lastPingNanos.get(), pingInterval)) {
                try {
                    session.sendMessage(PingMessage())
                    reservation.lastPingNanos.set(now)
                } catch (_: Exception) {
                    close(session, CloseStatus.GOING_AWAY)
                }
            }
        }
    }

    private fun reservation(session: WebSocketSession): Reservation? =
        session.attributes[RESERVATION_ATTRIBUTE] as? Reservation

    private fun key(identity: BattleJoinIdentity) =
        Key(identity.serverId, identity.worldId, identity.battleId, identity.accountId)

    private fun elapsed(now: Long, since: Long, limit: Duration): Boolean =
        now - since >= limit.toNanos()

    companion object {
        const val RESERVATION_ATTRIBUTE = "battle.join.reservation"
    }
}
