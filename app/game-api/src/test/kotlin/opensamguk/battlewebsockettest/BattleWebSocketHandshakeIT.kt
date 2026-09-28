package opensamguk.battlewebsockettest

import java.net.Socket
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import opensamguk.common.world.WorldId
import opensamguk.gameapi.battle.realtime.BattleJoinIdentity
import opensamguk.gameapi.battle.realtime.BattleJoinTicketService
import opensamguk.gameapi.battle.realtime.BattleWebSocketConfiguration
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import org.mockito.Mockito.*
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.beans.factory.annotation.Autowired

@SpringBootConfiguration
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class,
    HibernateJpaAutoConfiguration::class, RedisAutoConfiguration::class,
    RedisRepositoriesAutoConfiguration::class, SecurityAutoConfiguration::class,
    UserDetailsServiceAutoConfiguration::class, ManagementWebSecurityAutoConfiguration::class])
@Import(BattleWebSocketConfiguration::class)
private class BattleWebSocketTestApplication {
    @Bean fun tickets(): BattleJoinTicketService = mock(BattleJoinTicketService::class.java)
    @Bean fun generals(): GeneralResolver = mock(GeneralResolver::class.java)
    @Bean fun processWorld() = GameApiProcessWorld(1)
}

@SpringBootTest(classes = [BattleWebSocketTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["battle.join-ticket.enabled=true", "battle.websocket.allowed-origins=http://localhost"])
class BattleWebSocketHandshakeIT @Autowired constructor(
    private val tickets: BattleJoinTicketService,
    private val generals: GeneralResolver,
) {
    @LocalServerPort private var port: Int = 0
    private val token = "BTJ2.abc.${"A".repeat(43)}"

    private fun handshake(protocols: String = "battle.v1, $token", origin: String = "http://localhost",
                          path: String = "/ws/battles/pep/1/battle-1"): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5000
        socket.getOutputStream().write(("GET $path HTTP/1.1\r\n" +
            "Host: localhost:$port\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n" +
            "Sec-WebSocket-Protocol: $protocols\r\n" +
            "Origin: $origin\r\n\r\n").toByteArray(Charsets.US_ASCII))
        val lines = mutableListOf<String>()
        val reader = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            lines += line
        }
        lines.joinToString("\n")
    }

    @Test
    fun `valid short ticket upgrades with only fixed protocol echoed`() {
        val identity = BattleJoinIdentity("pep", WorldId(1), "battle-1", 42, 1, 7,
            "ATTACKER", 1, 3, Instant.parse("2026-09-29T00:01:00Z"))
        `when`(tickets.verifyBearer(token, "pep", WorldId(1), "battle-1")).thenReturn(identity)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        val response = handshake()
        assertContains(response, "101")
        assertContains(response.lowercase(), "sec-websocket-protocol: battle.v1")
        assertFalse(response.contains(token))
        reset(tickets, generals)
    }

    @Test
    fun `invalid ticket and foreign origin do not upgrade`() {
        `when`(tickets.verifyBearer(token, "pep", WorldId(1), "battle-1"))
            .thenThrow(SecurityException("invalid battle join ticket"))
        assertContains(handshake(), "403")
        assertContains(handshake(origin = "http://other.example"), "403")
        reset(tickets, generals)
    }
}
