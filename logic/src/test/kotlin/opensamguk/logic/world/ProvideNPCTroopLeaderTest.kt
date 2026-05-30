package opensamguk.logic.world

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.EventStore
import opensamguk.logic.event.EventTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A3 / Task NL4 — `ProvideNPCTroopLeader` (the paired Action) + the F2-factory registration.
 *
 * Port target: `Event/Action/ProvideNPCTroopLeader.php`. Per nation: a max NPC-troop-leader count by
 * nation level (`MaxNPCTroopLeaderCnt[level]`, defined ONLY for levels 1..7); count the nation's
 * current `npc = 5` generals; create `(max - current)` new NPC troop-leaders, each via a self-seeded
 * `troopLeader` DRBG (`simpleSerialize(hidden,'troopLeader',y,m,nationID)`); `lastNPCTroopLeaderID`
 * is a monotonic KV counter shared across nations.
 *
 * The actual GeneralBuilder / troop-table / 집합-command reservation is a P6/scenario seam; NL4 ports
 * the byte-match-critical pieces: the per-nation create-count gate, the troopLeader seed derivation,
 * and the registration of BOTH leaves into the F2 factory (the lone per-family touch-point).
 */
class ProvideNPCTroopLeaderTest {

    private val HIDDEN = "00000000000000000000000000000000"

    @Test
    fun `MaxNPCTroopLeaderCnt is defined only for levels 1 to 7`() {
        assertEquals(0, ProvideNPCTroopLeader.maxNpcTroopLeaderCnt(1))
        assertEquals(1, ProvideNPCTroopLeader.maxNpcTroopLeaderCnt(2))
        assertEquals(3, ProvideNPCTroopLeader.maxNpcTroopLeaderCnt(3))
        assertEquals(4, ProvideNPCTroopLeader.maxNpcTroopLeaderCnt(4))
        assertEquals(6, ProvideNPCTroopLeader.maxNpcTroopLeaderCnt(5))
        assertEquals(7, ProvideNPCTroopLeader.maxNpcTroopLeaderCnt(6))
        assertEquals(9, ProvideNPCTroopLeader.maxNpcTroopLeaderCnt(7))
        // levels 0, 8, 9 are NOT in the table → 0 (the PHP `?? 0`). 8/9 inherit no NPC leaders.
        assertEquals(0, ProvideNPCTroopLeader.maxNpcTroopLeaderCnt(0))
        assertEquals(0, ProvideNPCTroopLeader.maxNpcTroopLeaderCnt(8))
        assertEquals(0, ProvideNPCTroopLeader.maxNpcTroopLeaderCnt(9))
    }

    @Test
    fun `create count is max minus current npc5 count, never negative`() {
        // lv5 → max 6, currently 2 → create 4.
        assertEquals(4, ProvideNPCTroopLeader.createCount(level = 5, currentNpc5Count = 2))
        // already at/over max → 0 (the PHP `>= max` continue).
        assertEquals(0, ProvideNPCTroopLeader.createCount(level = 5, currentNpc5Count = 6))
        assertEquals(0, ProvideNPCTroopLeader.createCount(level = 5, currentNpc5Count = 9))
        // lv1 → max 0 → never creates.
        assertEquals(0, ProvideNPCTroopLeader.createCount(level = 1, currentNpc5Count = 0))
    }

    @Test
    fun `troopLeader seed is per nation per month`() {
        val expected = serializeSeed(HIDDEN, "troopLeader", 200, 7, 12)
        assertEquals(expected, ProvideNPCTroopLeader.troopLeaderSeed(HIDDEN, year = 200, month = 7, nationId = 12))
        // distinct seeds for distinct nations / months.
        assertTrue(
            ProvideNPCTroopLeader.troopLeaderSeed(HIDDEN, 200, 7, 12) !=
                ProvideNPCTroopLeader.troopLeaderSeed(HIDDEN, 200, 7, 13),
        )
        // the factory built from that seed draws deterministically.
        val a = ProvideNPCTroopLeader.troopLeaderRng(HIDDEN, 200, 7, 12)
        val b = RandUtil(LiteHashDrbg(serializeSeed(HIDDEN, "troopLeader", 200, 7, 12)))
        assertEquals(b.nextRangeInt(0, 100), a.nextRangeInt(0, 100))
    }

    @Test
    fun `lastNPCTroopLeaderID increments monotonically across the plan`() {
        // lv5 nation 7, current 2 → create 4; lv3 nation 9, current 0 → create 3. Shared counter.
        val plan = ProvideNPCTroopLeader.planNation(
            hiddenSeed = HIDDEN, year = 200, month = 1, nationId = 7, level = 5,
            currentNpc5Count = 2, startLastNpcId = 100,
        )
        assertEquals(4, plan.newLeaders.size)
        assertEquals(listOf(101, 102, 103, 104), plan.newLeaders.map { it.npcId })
        assertEquals(104, plan.nextLastNpcId)
        // the 부대장 name is the PHP sprintf('부대장%4d', id) (space-padded to width 4).
        assertEquals("부대장 101", plan.newLeaders.first().name)
    }

    @Test
    fun `both leaves register into the F2 factory by name`() {
        val factory = EventActionFactory()
        A3EventActions.register(factory)
        assertTrue(factory.has("UpdateNationLevel"))
        assertTrue(factory.has("ProvideNPCTroopLeader"))
    }

    @Test
    fun `the F2 month-1000 true row names both leaves in order (fires every month)`() {
        val store = EventStore.withDefaults()
        val monthRows = store.rowsFor(EventTarget.MONTH)
        val row1000 = monthRows.single { it.priority == 1000 }
        assertEquals(listOf("UpdateNationLevel", "ProvideNPCTroopLeader"), row1000.actions.map { it.name })
        // priority 1000 (true) fires EVERY month — it is not gated to a specific month like the 9000 rows.
    }
}
