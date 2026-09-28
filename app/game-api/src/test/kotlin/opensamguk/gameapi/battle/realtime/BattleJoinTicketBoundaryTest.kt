package opensamguk.gameapi.battle.realtime

import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.battle.realtime.BattleSessionStore
import org.mockito.Mockito.*
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcOperations
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class BattleJoinTicketBoundaryTest {
    private val store = mock(BattleSessionStore::class.java)
    private val jdbc = mock(NamedParameterJdbcTemplate::class.java)
    private val jdbcOperations = mock(JdbcOperations::class.java)
    private val context = ApplicationContextRunner()
        .withUserConfiguration(BattleJoinTicketConfiguration::class.java)
        .withBean(BattleSessionStore::class.java, Supplier { store })
        .withBean(NamedParameterJdbcTemplate::class.java, Supplier { jdbc })

    init {
        `when`(jdbc.jdbcOperations).thenReturn(jdbcOperations)
    }

    @Test
    fun `disabled mode creates neither signer nor database clock`() {
        context.run { result ->
            assertEquals(null, result.startupFailure)
            assertFalse(result.containsBean("battleJoinTicketService"))
            assertFalse(result.containsBean("battleJoinDbClock"))
        }
    }

    @Test
    fun `enabled mode requires a dedicated canonical key of at least 32 bytes`() {
        for (key in listOf("", Base64.getEncoder().encodeToString(ByteArray(16)), "invalid!!")) {
            context.withPropertyValues("battle.join-ticket.enabled=true", "battle.join-ticket.key-base64=$key")
                .run { result -> assertNotNull(result.startupFailure) }
        }
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 4 })
        context.withPropertyValues("battle.join-ticket.enabled=true", "battle.join-ticket.key-base64=$key")
            .run { result ->
                assertEquals(null, result.startupFailure)
                assertTrue(result.containsBean("battleJoinTicketService"))
                assertTrue(result.getBeanNamesForType(Clock::class.java).isEmpty())
                assertFalse(result.containsBean("battleJoinDbClock"))
            }
    }

    @Test
    fun `battle clock reads database wall time on every call`() {
        val operations = mock(JdbcOperations::class.java)
        val first = Instant.parse("2026-09-28T00:00:00Z")
        val second = first.plusSeconds(3)
        `when`(operations.queryForObject("SELECT clock_timestamp()", Timestamp::class.java))
            .thenReturn(Timestamp.from(first), Timestamp.from(second))
        val clock = BattleJoinDbClock(operations)
        assertEquals(first, clock.instant())
        assertEquals(second, clock.withZone(ZoneOffset.ofHours(9)).instant())
        verify(operations, times(2)).queryForObject("SELECT clock_timestamp()", Timestamp::class.java)
    }

    @Test
    fun `controller requires current owned general and process world`() {
        val generals = mock(GeneralResolver::class.java)
        val tickets = mock(BattleJoinTicketService::class.java)
        val controller = BattleJoinTicketController(GameApiProcessWorld(1), generals, tickets)
        assertEquals(HttpStatus.UNAUTHORIZED, controller.issue(null, 1, "battle-1").statusCode)
        assertEquals(HttpStatus.NOT_FOUND, controller.issue(42L, 2, "battle-1").statusCode)
        assertEquals(HttpStatus.NOT_FOUND, controller.issue(Long.MAX_VALUE, 1, "battle-1").statusCode)
        assertEquals(HttpStatus.NOT_FOUND, controller.issue(42L, 1, "battle-1").statusCode)
        doReturn(null).`when`(generals).resolveGeneralId(42L)
        assertEquals(HttpStatus.NOT_FOUND, controller.issue(42L, 1, "battle-1").statusCode)
        verifyNoInteractions(tickets)

        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        `when`(tickets.issue(opensamguk.common.world.WorldId(1), "battle-1", 42, 7)).thenReturn("signed")
        val issued = controller.issue(42L, 1, "battle-1")
        assertEquals(HttpStatus.OK, issued.statusCode)
        assertEquals("signed", issued.body?.joinTicket)
        assertTrue(issued.headers.cacheControl?.contains("no-store") == true)
        `when`(tickets.issue(opensamguk.common.world.WorldId(1), "battle-1", 42, 7))
            .thenThrow(SecurityException("battle participant unavailable"))
        val denied = controller.issue(42L, 1, "battle-1")
        assertEquals(HttpStatus.NOT_FOUND, denied.statusCode)
        assertEquals(null, denied.body)
    }
}
