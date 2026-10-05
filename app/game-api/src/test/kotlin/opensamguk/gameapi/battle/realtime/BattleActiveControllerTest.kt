package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.infra.battle.realtime.BattleActiveSessionReader
import opensamguk.infra.battle.realtime.BattleActiveSessionRow
import opensamguk.infra.battle.realtime.BattleSessionPhase
import opensamguk.infra.battle.realtime.BattleSessionStore
import opensamguk.infra.battle.realtime.FrozenBattleParticipant
import opensamguk.infra.battle.realtime.FrozenBattleTicket
import opensamguk.logic.battle.realtime.BattleDeployment
import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.Battlefield
import opensamguk.logic.battle.realtime.GeneralStats
import opensamguk.logic.battle.realtime.Retinue
import opensamguk.logic.battle.realtime.TacticalBattle
import opensamguk.logic.battle.realtime.UnitKind
import org.mockito.Mockito.*
import org.springframework.http.HttpStatus

class BattleActiveControllerTest {
    private val world = WorldId(1)
    private val observedAt = Instant.parse("2026-10-05T08:00:00Z")
    private val joinDeadline = observedAt.plusSeconds(60)
    private val participant = FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3)
    private val payload = """{"kind":"ENCOUNTER"}"""
    private val ticket = FrozenBattleTicket(world, "battle-1", payload, sha(payload),
        "a".repeat(64), "b".repeat(64), "c".repeat(64), 17, 1, 1,
        joinDeadline, joinDeadline.plusSeconds(300), listOf(participant))
    private val row = BattleActiveSessionRow(world, "battle-1", BattleSessionPhase.JOINING,
        1, "ATTACKER", 3, joinDeadline, observedAt)
    private var rows = listOf(row)
    private val generals = mock(GeneralResolver::class.java)
    private val store = mock(BattleSessionStore::class.java)
    private val frozen = mock(BattleFrozenInputCodec::class.java)
    private val sessions = BattleActiveSessionReader { _, _, _, _ -> rows }
    private val query = BattleActiveQuery(GameApiProcessWorld(1), generals, sessions, store, frozen)
    private val controller = BattleActiveController(query)

    @Test
    fun `owned participant sees only their actual retinue source keys and pinned phase`() {
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        `when`(store.ticket(world, "battle-1")).thenReturn(ticket)
        val field = Battlefield(1, "FIELD", List(64) { "P".repeat(64) })
        fun retinue(id: Int, generalId: Int) = Retinue(id, GeneralStats(generalId, 50, 50, 50, 50, 50),
            100, UnitKind.INFANTRY, 50, 50, 0, 50, true)
        val state = TacticalBattle.start(17, field,
            BattleDeployment.default(BattleSide.ATTACKER, 7,
                listOf(retinue(701, 7), retinue(702, 9))),
            BattleDeployment.default(BattleSide.DEFENDER, 8, listOf(retinue(801, 8))))
        `when`(frozen.initialState(ticket)).thenReturn(state)

        val entry = (assertNotNull(controller.active(42L, "7").body) as List<BattleActiveEntry>).single()
        assertEquals("battle-1", entry.battleId)
        assertEquals("1", entry.worldId)
        assertEquals("FIELD", entry.kind)
        assertEquals("JOINING", entry.sourcePhase)
        assertEquals("JOINING", entry.phase)
        assertEquals(joinDeadline, entry.joinDeadlineAt)
        assertEquals(observedAt, entry.observedAt)
        assertEquals(listOf(BattleActiveSourceKey("RETINUE", "701")), entry.mySeat.sourceKeys)
        assertNull(entry.pacingMode)
        assertEquals("SOURCE_NOT_AVAILABLE", entry.pacingModeUnavailableReason)
        assertNull(entry.controller)
        assertNull(entry.replayId)
        verify(store).ticket(world, "battle-1")
    }

    @Test
    fun `authentication and current general are checked before session lookup`() {
        val anonymous = controller.active(null, "bad")
        assertEquals(HttpStatus.UNAUTHORIZED, anonymous.statusCode)
        assertEquals("AUTH_REQUIRED", (anonymous.body as BattleActiveError).error.code)
        assertEquals("no-store", anonymous.headers.cacheControl)
        val malformed = controller.active(42L, "bad")
        assertEquals(HttpStatus.BAD_REQUEST, malformed.statusCode)
        assertEquals("INVALID_GENERAL_ID", (malformed.body as BattleActiveError).error.code)
        assertEquals("no-store", malformed.headers.cacheControl)
        assertEquals(HttpStatus.FORBIDDEN, controller.active(42L, "7").statusCode)
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        val verifying = controller.active(42L, "8")
        assertEquals(HttpStatus.FORBIDDEN, verifying.statusCode)
        assertEquals("FORBIDDEN", (verifying.body as BattleActiveError).error.code)
        assertEquals("no-store", verifying.headers.cacheControl)
        verifyNoInteractions(store, frozen)
    }

    @Test
    fun `READY has no countdown RUNNING is LIVE and blocked phase stays blocked`() {
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        `when`(store.ticket(world, "battle-1")).thenReturn(ticket)
        val field = Battlefield(1, "FIELD", List(64) { "P".repeat(64) })
        val own = Retinue(701, GeneralStats(7, 50, 50, 50, 50, 50), 100,
            UnitKind.INFANTRY, 50, 50, 0, 50, true)
        val other = Retinue(801, GeneralStats(8, 50, 50, 50, 50, 50), 100,
            UnitKind.INFANTRY, 50, 50, 0, 50, true)
        `when`(frozen.initialState(ticket)).thenReturn(TacticalBattle.start(17, field,
            BattleDeployment.default(BattleSide.ATTACKER, 7, listOf(own)),
            BattleDeployment.default(BattleSide.DEFENDER, 8, listOf(other))))

        for ((source, expected) in listOf(
            BattleSessionPhase.READY to "READY",
            BattleSessionPhase.RUNNING to "LIVE",
            BattleSessionPhase.RESOLVING to "RESOLVING",
            BattleSessionPhase.RESULT_PENDING to "RESULT_PENDING",
            BattleSessionPhase.RESULT_BLOCKED to "RESULT_BLOCKED",
            BattleSessionPhase.QUARANTINED to "QUARANTINED",
        )) {
            rows = listOf(row.copy(sourcePhase = source))
            val entry = (assertNotNull(controller.active(42L, "7").body) as List<BattleActiveEntry>).single()
            assertEquals(source.name, entry.sourcePhase)
            assertEquals(expected, entry.phase)
            assertNull(entry.joinDeadlineAt)
        }
    }

    @Test
    fun `real empty list differs from missing ticket or unavailable reader`() {
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        rows = emptyList()
        assertEquals(emptyList<BattleActiveEntry>(), controller.active(42L, "7").body)
        rows = listOf(row)
        val missing = controller.active(42L, "7")
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, missing.statusCode)
        assertEquals("SOURCE_UNAVAILABLE", (missing.body as BattleActiveError).error.code)
        assertEquals("no-store", missing.headers.cacheControl)
        val unavailable = BattleActiveSessionReader { _, _, _, _ -> error("source unavailable") }
        val unavailableController = BattleActiveController(
            BattleActiveQuery(GameApiProcessWorld(1), generals, unavailable, store, frozen))
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unavailableController.active(42L, "7").statusCode)
    }

    @Test
    fun `frozen participant mismatch never becomes a false empty list`() {
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        `when`(store.ticket(world, "battle-1")).thenReturn(ticket.copy(
            participants = listOf(participant.copy(generalId = 9))))
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.active(42L, "7").statusCode)
        verifyNoInteractions(frozen)
    }

    @Test
    fun `unpaged response refuses overflow instead of silently dropping battles`() {
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        rows = List(101) { row.copy(battleId = "battle-$it") }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, controller.active(42L, "7").statusCode)
        verifyNoInteractions(store, frozen)
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
