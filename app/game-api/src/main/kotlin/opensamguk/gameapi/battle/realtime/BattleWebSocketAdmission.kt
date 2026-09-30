package opensamguk.gameapi.battle.realtime

import java.net.URI
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.web.socket.BinaryMessage
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.PongMessage
import org.springframework.web.socket.SubProtocolCapable
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse

/** A short ticket is carried only as a requested subprotocol, never in the URL or response. */
class BattleWebSocketAdmission(
    private val tickets: BattleJoinTicketService,
    private val processWorld: GameApiProcessWorld,
    private val generals: GeneralResolver,
    allowedOrigins: String,
    private val sessions: BattleWebSocketSessions,
) : HandshakeInterceptor {
    private val origins = allowedOrigins.split(',').map(String::trim).filter(String::isNotEmpty).toSet().also {
        require(it.isNotEmpty() && it.all { origin ->
            val uri = runCatching { URI(origin) }.getOrNull()
            uri != null && uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
                uri.path.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null &&
                origin != "*"
        }) { "battle websocket origin configuration is invalid" }
    }

    override fun beforeHandshake(request: ServerHttpRequest, response: ServerHttpResponse,
                                 wsHandler: org.springframework.web.socket.WebSocketHandler,
                                 attributes: MutableMap<String, Any>): Boolean {
        fun deny(): Boolean { response.setStatusCode(HttpStatus.FORBIDDEN); return false }
        val origin = request.headers.origin ?: return deny()
        if (origin !in origins || request.headers.containsKey("Authorization") ||
            request.headers.containsKey("Cookie")) return deny()
        val match = PATH.matchEntire(request.uri.rawPath) ?: return deny()
        val (serverId, worldText, battleId) = match.destructured
        val worldId = worldText.toIntOrNull()?.takeIf { it == processWorld.worldId.value }
            ?.let(::WorldId) ?: return deny()
        val query = request.uri.rawQuery
        val lastSeen = if (query == null) null else {
            val value = LAST_SEEN.matchEntire(query)?.groupValues?.get(1) ?: return deny()
            value.toLongOrNull()?.takeIf { it >= 0 } ?: return deny()
        }
        val protocols = request.headers["Sec-WebSocket-Protocol"]?.flatMap { it.split(',') }
            ?.map(String::trim) ?: return deny()
        if (protocols.size != 2 || protocols[0] != PROTOCOL || !TOKEN.matches(protocols[1])) return deny()
        val identity = try {
            tickets.verifyBearer(protocols[1], serverId, worldId, battleId)
        } catch (_: SecurityException) { return deny() }
        val owned = runCatching { generals.resolveGeneralId(identity.accountId.toLong()) }.getOrNull()
        if (owned != identity.generalId) return deny()
        val reservation = sessions.reserve(identity)
        attributes[IDENTITY] = identity
        attributes[LAST_SEEN_ATTRIBUTE] = lastSeen ?: 0L
        attributes[BattleWebSocketSessions.RESERVATION_ATTRIBUTE] = reservation
        return true
    }

    override fun afterHandshake(request: ServerHttpRequest, response: ServerHttpResponse,
                                wsHandler: org.springframework.web.socket.WebSocketHandler,
                                exception: Exception?) = Unit

    companion object {
        const val PROTOCOL = "battle.v1"
        const val IDENTITY = "battle.join.identity"
        const val LAST_SEEN_ATTRIBUTE = "battle.last.seen.event.seq"
        private val PATH = Regex("/ws/battles/([a-z0-9]{1,48})/([1-9][0-9]{0,9})/([A-Za-z0-9_-]{1,128})")
        private val LAST_SEEN = Regex("lastSeenEventSeq=([0-9]{1,19})")
        private val TOKEN = Regex("BTJ2\\.[A-Za-z0-9_-]{1,768}\\.[A-Za-z0-9_-]{43}")
    }
}

/** Admission-only endpoint: later slices add faction projection and command dispatch. */
class BattleWebSocketHandler(private val sessions: BattleWebSocketSessions) : TextWebSocketHandler(), SubProtocolCapable {
    override fun getSubProtocols(): List<String> = listOf(BattleWebSocketAdmission.PROTOCOL)

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val reservation = session.attributes[BattleWebSocketSessions.RESERVATION_ATTRIBUTE]
            as? BattleWebSocketSessions.Reservation
        if (reservation == null || !sessions.attach(reservation, session)) {
            session.close(CloseStatus.POLICY_VIOLATION)
        }
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        (session.attributes[BattleWebSocketSessions.RESERVATION_ATTRIBUTE]
            as? BattleWebSocketSessions.Reservation)?.let(sessions::release)
    }

    override fun handleTransportError(session: WebSocketSession, exception: Throwable) {
        sessions.close(session, CloseStatus.GOING_AWAY)
    }

    override fun handleTextMessage(session: WebSocketSession, message: TextMessage) {
        sessions.close(session, CloseStatus.POLICY_VIOLATION)
    }

    override fun handleBinaryMessage(session: WebSocketSession, message: BinaryMessage) {
        sessions.close(session, CloseStatus.POLICY_VIOLATION)
    }

    override fun handlePongMessage(session: WebSocketSession, message: PongMessage) {
        sessions.pong(session)
    }
}

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@EnableScheduling
@ConditionalOnProperty(prefix = "battle.join-ticket", name = ["enabled"], havingValue = "true")
class BattleWebSocketConfiguration(
    tickets: BattleJoinTicketService,
    processWorld: GameApiProcessWorld,
    generals: GeneralResolver,
    @Value("\${battle.websocket.allowed-origins:}") private val allowedOrigins: String,
) : WebSocketConfigurer {
    private val sessions = BattleWebSocketSessions(tickets, generals)
    private val admission = BattleWebSocketAdmission(tickets, processWorld, generals, allowedOrigins, sessions)

    @Bean
    fun battleWebSocketSessions(): BattleWebSocketSessions = sessions

    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(BattleWebSocketHandler(sessions), "/ws/battles/*/*/*")
            .addInterceptors(admission)
            .setAllowedOrigins(*allowedOrigins.split(',').map(String::trim).filter(String::isNotEmpty).toTypedArray())
    }
}
