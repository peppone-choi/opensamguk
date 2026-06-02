package opensamguk.logic.actions.intake

import opensamguk.logic.domain.General
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-logic parity tests for the troop launch()/validateArgs guard chains. Oracle = the verbatim
 * PHP return strings in `legacy/devsam-core/hwe/sammo/API/Troop/`.
 */
class TroopActionsTest {

    private fun gen(id: Int, troop: Int = 0, nationId: Int = 1, officerLevel: Int = 2) = General(
        id = id, nationId = nationId, cityId = 5,
        leadership = 10, strength = 10, intel = 10, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = officerLevel,
        gold = 1000, rice = 1000, troop = troop,
    )

    // ── NewTroop ──────────────────────────────────────────────────────────────────────────────
    @Test
    fun `newTroop creates when name valid and general free + in a nation`() {
        val out = TroopActions.newTroop(gen(id = 7, troop = 0, nationId = 1), "제1군단")
        assertEquals(TroopActions.NewTroopOutcome.Create("제1군단"), out)
    }

    @Test
    fun `newTroop denies over-width name (mb_strwidth gt 18)`() {
        // 10 Hangul syllables = display width 20 > 18.
        val out = TroopActions.newTroop(gen(id = 7), "가나다라마바사아자차")
        assertTrue(out is TroopActions.NewTroopOutcome.Denied)
    }

    @Test
    fun `newTroop denies a name that neutralizes to empty`() {
        // width("0")=1 passes the validator, but neutralize("0")="" (PHP falsy) → empty-name guard.
        assertEquals(
            TroopActions.NewTroopOutcome.Denied("부대 이름이 없습니다."),
            TroopActions.newTroop(gen(id = 7), "0"),
        )
        assertEquals(
            TroopActions.NewTroopOutcome.Denied("부대 이름이 없습니다."),
            TroopActions.newTroop(gen(id = 7), "   "),
        )
    }

    @Test
    fun `newTroop denies when already in a troop, then when nationless`() {
        assertEquals(
            TroopActions.NewTroopOutcome.Denied("이미 부대에 소속되어 있습니다."),
            TroopActions.newTroop(gen(id = 7, troop = 7), "부대"),
        )
        assertEquals(
            TroopActions.NewTroopOutcome.Denied("국가에 소속되어 있지 않습니다."),
            TroopActions.newTroop(gen(id = 7, troop = 0, nationId = 0), "부대"),
        )
    }

    // ── JoinTroop ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `joinTroop proceeds when free + in a nation, else denies`() {
        assertEquals(TroopActions.JoinTroopOutcome.Proceed(1), TroopActions.joinTroop(gen(id = 7)))
        assertEquals(
            TroopActions.JoinTroopOutcome.Denied("이미 부대에 소속되어 있습니다."),
            TroopActions.joinTroop(gen(id = 7, troop = 3)),
        )
        assertEquals(
            TroopActions.JoinTroopOutcome.Denied("국가에 소속되어 있지 않습니다."),
            TroopActions.joinTroop(gen(id = 7, nationId = 0)),
        )
    }

    // ── ExitTroop ─────────────────────────────────────────────────────────────────────────────
    @Test
    fun `exitTroop distinguishes not-in-troop, member exit, and leader disband`() {
        assertEquals(
            TroopActions.ExitTroopOutcome.Denied("부대에 소속되어 있지 않습니다."),
            TroopActions.exitTroop(gen(id = 7, troop = 0)),
        )
        // member: troop id != own id.
        assertEquals(TroopActions.ExitTroopOutcome.ExitMember, TroopActions.exitTroop(gen(id = 7, troop = 3)))
        // leader: troop id == own id.
        assertEquals(TroopActions.ExitTroopOutcome.Disband, TroopActions.exitTroop(gen(id = 7, troop = 7)))
    }

    // ── KickFromTroop ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `kickFromTroop kicks a matching non-leader member`() {
        // target in troop 3, arg troopID 3, target id 9 != 3 (not leader) → Kick.
        assertEquals(TroopActions.KickOutcome.Kick, TroopActions.kickFromTroop(gen(id = 9, troop = 3), 3))
    }

    @Test
    fun `kickFromTroop denies not-in-troop, mismatched troop, and leader target`() {
        assertEquals(
            TroopActions.KickOutcome.Denied("부대에 소속되어 있지 않습니다."),
            TroopActions.kickFromTroop(gen(id = 9, troop = 0), 3),
        )
        assertEquals(
            TroopActions.KickOutcome.Denied("다른 부대에 소속되어 있습니다."),
            TroopActions.kickFromTroop(gen(id = 9, troop = 3), 4),
        )
        // target id == troop id == arg → the target IS the leader.
        assertEquals(
            TroopActions.KickOutcome.Denied("부대장을 추방할 수 없습니다."),
            TroopActions.kickFromTroop(gen(id = 3, troop = 3), 3),
        )
    }

    // ── SetTroopName ──────────────────────────────────────────────────────────────────────────
    @Test
    fun `setTroopName allows the leader of the troop regardless of permission`() {
        // me.id == troopID → own troop → permission ignored.
        assertEquals(
            TroopActions.SetNameOutcome.Rename("새이름", 1),
            TroopActions.setTroopName(gen(id = 5), troopIdArg = 5, rawTroopName = "새이름", permission = 0),
        )
    }

    @Test
    fun `setTroopName allows a high-permission officer on another troop`() {
        assertEquals(
            TroopActions.SetNameOutcome.Rename("새이름", 1),
            TroopActions.setTroopName(gen(id = 5), troopIdArg = 9, rawTroopName = "새이름", permission = 4),
        )
    }

    @Test
    fun `setTroopName denies a low-permission officer on another troop`() {
        assertEquals(
            TroopActions.SetNameOutcome.Denied("권한이 부족합니다."),
            TroopActions.setTroopName(gen(id = 5), troopIdArg = 9, rawTroopName = "새이름", permission = 3),
        )
    }

    @Test
    fun `setTroopName denies over-width then empty-after-neutralize name`() {
        assertTrue(
            TroopActions.setTroopName(gen(id = 5), 5, "가나다라마바사아자차", 4) is TroopActions.SetNameOutcome.Denied,
        )
        assertEquals(
            TroopActions.SetNameOutcome.Denied("부대 이름이 없습니다."),
            TroopActions.setTroopName(gen(id = 5), 5, "0", 4),
        )
    }
}
