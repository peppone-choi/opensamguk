package opensamguk.battlewebsockettest

import java.net.Socket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import opensamguk.common.world.WorldId
import opensamguk.gameapi.battle.realtime.BattleJoinTicketService
import opensamguk.gameapi.battle.realtime.BattleFrozenInputCodec
import opensamguk.gameapi.battle.realtime.BattleSessionCoordinator
import opensamguk.gameapi.battle.realtime.BattleWebSocketConfiguration
import opensamguk.gameapi.battle.realtime.BattleWebSocketSessions
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.battle.realtime.BattleSessionHead
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.infra.battle.realtime.FrozenBattleTicket
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
import org.springframework.scheduling.config.FixedDelayTask
import org.springframework.scheduling.config.ScheduledTaskHolder

@SpringBootConfiguration
@EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class,
    HibernateJpaAutoConfiguration::class, RedisAutoConfiguration::class,
    RedisRepositoriesAutoConfiguration::class, SecurityAutoConfiguration::class,
    UserDetailsServiceAutoConfiguration::class, ManagementWebSecurityAutoConfiguration::class])
@Import(BattleWebSocketConfiguration::class)
private class BattleWebSocketTestApplication {
    @Bean fun store(): BattleSessionStore = mock(BattleSessionStore::class.java)
    @Bean fun tickets(store: BattleSessionStore): BattleJoinTicketService =
        BattleJoinTicketService(store, ByteArray(32) { 7 },
            Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC), "pep")
    @Bean fun generals(): GeneralResolver = mock(GeneralResolver::class.java)
    @Bean fun processWorld() = GameApiProcessWorld(1)
    @Bean fun frozen(): BattleFrozenInputCodec = mock(BattleFrozenInputCodec::class.java)
    @Bean fun coordinator(store: BattleSessionStore) = BattleSessionCoordinator(store)
}

@SpringBootTest(classes = [BattleWebSocketTestApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["battle.join-ticket.enabled=true", "battle.websocket.allowed-origins=http://localhost"])
class BattleWebSocketHandshakeIT @Autowired constructor(
    private val tickets: BattleJoinTicketService,
    private val generals: GeneralResolver,
    private val store: BattleSessionStore,
    private val sessions: BattleWebSocketSessions,
    private val scheduledTasks: ScheduledTaskHolder,
) {
    @LocalServerPort private var port: Int = 0
    private val world = WorldId(1)
    private val now = Instant.parse("2026-09-29T00:00:00Z")
    private val participant = FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3)
    private val ticket = FrozenBattleTicket(world, "battle-1", "{}", "a".repeat(64),
        "a".repeat(64), "a".repeat(64), "a".repeat(64), 17, 4, 2,
        now.minusSeconds(60), now.plusSeconds(300), listOf(participant))
    private fun head(epoch: Long = 1) = BattleSessionHead(world, "battle-1", BattleSessionPhase.RUNNING,
        epoch, 0, 0, 0, "actor", now.plusSeconds(30), ticket.joinDeadlineAt, ticket.deadlineAt)

    @BeforeEach
    fun resetStore() { reset(store, generals) }

    private fun validTicket(): String {
        `when`(store.ticket(world, "battle-1")).thenReturn(ticket)
        `when`(store.head(world, "battle-1")).thenReturn(head())
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        return tickets.issue(world, "battle-1", 42, 7)
    }

    private fun handshake(protocols: String, origin: String = "http://localhost",
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

    private fun assertStatus(response: String, code: Int) {
        assertTrue(response.lineSequence().firstOrNull()?.startsWith("HTTP/1.1 $code") == true)
    }

    @Test
    fun `session revalidation sweep is scheduled by the Spring context`() {
        assertTrue(scheduledTasks.scheduledTasks.any { scheduled ->
            val task = scheduled.task as? FixedDelayTask ?: return@any false
            task.runnable.toString() == "${BattleWebSocketSessions::class.java.name}.sweep" &&
                task.intervalDuration == Duration.ofSeconds(5)
        }, "registered tasks: ${scheduledTasks.scheduledTasks.map { "${it.task}:${it.task.runnable}" }}")
    }

    @Test
    fun `valid short ticket upgrades with only fixed protocol echoed`() {
        val token = validTicket()
        val response = handshake("battle.v1, $token")
        assertStatus(response, 101)
        assertContains(response.lowercase(), "sec-websocket-protocol: battle.v1")
        assertFalse(response.contains(token))
    }

    @Test
    fun `invalid ticket foreign origin server and epoch do not upgrade`() {
        val token = validTicket()
        val badSignature = token.dropLast(1) + if (token.last() == 'A') 'B' else 'A'
        assertStatus(handshake("battle.v1, $badSignature"), 403)
        assertStatus(handshake("battle.v1, $token", origin = "http://other.example"), 403)
        assertStatus(handshake("battle.v1, $token", path = "/ws/battles/other/1/battle-1"), 403)
        `when`(store.head(world, "battle-1")).thenReturn(head(epoch = 2))
        assertStatus(handshake("battle.v1, $token"), 403)
    }
}
