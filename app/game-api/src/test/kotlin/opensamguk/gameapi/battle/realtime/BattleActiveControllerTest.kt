package opensamguk.gameapi.battle.realtime

import java.security.MessageDigest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    private val participant = FrozenBattleParticipant(1, 42, 7, "ATTACKER", 3)
    private val payload = """{"kind":"ENCOUNTER"}"""
    private val ticket = FrozenBattleTicket(world, "battle-1", payload, sha(payload),
        "a".repeat(64), "b".repeat(64), "c".repeat(64), 17, 1, 1,
        Instant.parse("2026-10-01T08:00:00Z"), Instant.parse("2026-10-01T08:05:00Z"),
        listOf(participant))
    private val row = BattleActiveSessionRow(world, "battle-1", BattleSessionPhase.JOINING,
        1, 0, 0, ticket.joinDeadlineAt, 1, "ATTACKER", 3)
    private val generals = mock(GeneralResolver::class.java)
    private val store = mock(BattleSessionStore::class.java)
    private val frozen = mock(BattleFrozenInputCodec::class.java)
    private val sessions = BattleActiveSessionReader { _, _, _, _ -> listOf(row) }
    private val controller = BattleActiveController(GameApiProcessWorld(1), generals, sessions, store, frozen)

    @Test
    fun `owner sees only own side seats with separate persistent id and formation slot`() {
        `when`(generals.resolveGeneralId(42L)).thenReturn(7)
        `when`(store.ticket(world, "battle-1")).thenReturn(ticket)
        val field = Battlefield(1, "FIELD", List(64) { "P".repeat(64) })
        fun retinue(id: Int, generalId: Int) = Retinue(id, GeneralStats(generalId, 50, 50, 50, 50, 50),
            100, UnitKind.INFANTRY, 50, 50, 0, 50, true)
        val state = TacticalBattle.start(17, field,
            BattleDeployment.default(BattleSide.ATTACKER, 7, listOf(retinue(701, 7))),
            BattleDeployment.default(BattleSide.DEFENDER, 8, listOf(retinue(801, 8))))
        `when`(frozen.initialState(ticket)).thenReturn(state)

        val response = controller.active(42L, 7)
        assertEquals(HttpStatus.OK, response.statusCode)
        val entries = assertNotNull(response.body)
        assertEquals(1, entries.size)
        assertEquals("FIELD", entries.single().kind)
        assertEquals(listOf(BattleActiveSeat(701, "CENTER", 7)), entries.single().seats)
        assertEquals(HttpStatus.NOT_FOUND, controller.active(42L, 8).statusCode)
        assertEquals(HttpStatus.UNAUTHORIZED, controller.active(null, 7).statusCode)
        verify(store).ticket(world, "battle-1")
    }

    private fun sha(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
