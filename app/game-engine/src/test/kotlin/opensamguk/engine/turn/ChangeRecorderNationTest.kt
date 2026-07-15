package opensamguk.engine.turn

import opensamguk.logic.domain.Nation as LogicNation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FF1 — the P2 widening of [ChangeRecorder] to the satellite write-set: `diffNation`
 * (gold/rice/capset/capital/name/color + aux meta-diff), dest-general diff (dual-general commands
 * emit >1 dirty general), and the rank var 3-Map collapse (`Increment` | `Set`, at most one per
 * type — faithful to `General.php:641-670` increaseRankVar/setRankVar).
 *
 * The recorder stays the SINGLE dirty source (design Risk #4): nation/rank patches mark the
 * satellite rows dirty exactly the way the P1 general/city patches do.
 */
class ChangeRecorderNationTest {

    private fun nation(
        id: Int = 1,
        gold: Int = 1000,
        rice: Int = 1000,
        capset: Int = 0,
        capitalCityId: Int? = 5,
        name: String = "촉",
        color: String = "#00ff00",
        meta: Map<String, Any?> = linkedMapOf("rate" to 20, "bill" to 20, "gennum" to 3, "capset" to capset),
    ) = LogicNation(
        id = id,
        level = 5,
        capitalCityId = capitalCityId,
        name = name,
        color = color,
        typeCode = "che_중립",
        gold = gold,
        rice = rice,
        tech = 0.0,
        gennum = 3,
        capset = capset,
        meta = meta,
    )

    @Test
    fun `no nation change yields null patch and nothing dirty`() {
        val pre = nation()
        val recorder = ChangeRecorder()

        val patch = recorder.diffNation(pre, pre.copy())

        assertNull(patch, "identical pre/post → null patch (not dirty)")
        assertTrue(recorder.dirtyNationIds().isEmpty())
        assertTrue(recorder.nationPatches().isEmpty())
    }

    @Test
    fun `changed gold and capset listed exactly (unchanged columns absent)`() {
        // a 증축/감축/천도 bumps capset (term-stack invalidation) AND spends gold.
        val pre = nation(gold = 1000, capset = 0)
        val post = pre.copy(
            gold = 700,
            capset = 1,
            meta = linkedMapOf("rate" to 20, "bill" to 20, "gennum" to 3, "capset" to 1),
        )
        val recorder = ChangeRecorder()

        val patch = recorder.diffNation(pre, post)!!

        // exactly the changed scalar columns (rice/name/color/capital/tech/level unchanged → absent)
        assertEquals(setOf("gold"), patch.columns.keys, "only the changed scalar column is listed")
        assertEquals(700, patch.columns["gold"])
        // capset rides meta → the meta patch lists exactly the changed key
        assertEquals(setOf("capset"), patch.meta.keys, "only the changed meta key (capset) is listed")
        assertEquals(1, patch.meta["capset"])

        assertEquals(setOf(1), recorder.dirtyNationIds())
        assertEquals(patch, recorder.nationPatches().single())
        assertEquals(1, patch.id)
        assertTrue(recorder.isDirty)
    }

    @Test
    fun `changed name color capital listed for a founding nation`() {
        val pre = nation(name = "", color = "", capitalCityId = null)
        val post = pre.copy(name = "위", color = "#0000ff", capitalCityId = 12)
        val recorder = ChangeRecorder()

        val patch = recorder.diffNation(pre, post)!!

        assertEquals(setOf("name", "color", "capital_city_id"), patch.columns.keys)
        assertEquals("위", patch.columns["name"])
        assertEquals("#0000ff", patch.columns["color"])
        assertEquals(12, patch.columns["capital_city_id"])
    }

    @Test
    fun `dual-general appoint emits two dirty generals`() {
        val g1 = generalLogic(id = 1, officerLevel = 1)
        val g2 = generalLogic(id = 2, officerLevel = 0)
        val recorder = ChangeRecorder()

        // 발탁/등용 등 dual-general 명령: the actor AND the dest general both change.
        recorder.diffGeneral(g1, g1.copy(officerLevel = 12))
        recorder.diffGeneral(g2, g2.copy(officerLevel = 5))

        assertEquals(setOf(1, 2), recorder.dirtyGeneralIds(), "both the actor and the dest general are dirty")
        assertEquals(2, recorder.generalPatches().size)
    }

    @Test
    fun `rank Increment then Set on the same type collapses to one Set write`() {
        val recorder = ChangeRecorder()

        // increaseRankVar(occupied,+1) then setRankVar(occupied,5): setRankVar removes the pending
        // increase and writes a Set (General.php:662-670). At most one delta survives per type.
        recorder.recordRankIncrease(generalId = 10, column = RankColumn.OCCUPIED, value = 1)
        recorder.recordRankSet(generalId = 10, column = RankColumn.OCCUPIED, value = 5)

        val deltas = recorder.rankDeltas(generalId = 10)
        assertEquals(1, deltas.size, "Increment+Set on the same type collapses to one delta")
        assertEquals(RankDelta.Set(5), deltas[RankColumn.OCCUPIED])
    }

    @Test
    fun `rank Increment then Increment on the same type accumulates one Increment`() {
        val recorder = ChangeRecorder()

        recorder.recordRankIncrease(generalId = 10, column = RankColumn.WARNUM, value = 1)
        recorder.recordRankIncrease(generalId = 10, column = RankColumn.WARNUM, value = 2)

        val deltas = recorder.rankDeltas(generalId = 10)
        assertEquals(1, deltas.size)
        assertEquals(RankDelta.Increment(3), deltas[RankColumn.WARNUM], "two increases of the same type accumulate")
    }

    @Test
    fun `inheritance increase starts from loaded KV and resets base on aux mismatch`() {
        val recorder = ChangeRecorder(
            initialInheritancePoints = mapOf(
                77 to mapOf(
                    "active_action" to listOf(9.0, mapOf("source" to "old")),
                ),
            ),
        )

        recorder.recordInheritancePointIncrease(77, "active_action", 1.0, mapOf("source" to "old"))
        recorder.recordInheritancePointIncrease(77, "active_action", 1.0, mapOf("source" to "new"))
        recorder.recordInheritancePointIncrease(77, "active_action", 1.0, mapOf("source" to "new"))

        assertEquals(
            listOf(12.0, 3.0, 6.0),
            recorder.inheritanceKvWrites().map { (it.value as List<*>)[0] as Double },
        )
    }

    @Test
    fun `RankColumn has exactly 37 cases with the PHP backing-value column names in order`() {
        // Verified against sammo\Enums\RankColumn (hwe/sammo/Enums/RankColumn.php) — 37 cases; the
        // enum case backing VALUES are the rank_data `type` column names.
        assertEquals(37, RankColumn.entries.size)
        assertEquals(
            listOf(
                "firenum", "warnum", "killnum", "deathnum", "killcrew", "deathcrew",
                "ttw", "ttd", "ttl", "ttg", "ttp",
                "tlw", "tld", "tll", "tlg", "tlp",
                "tsw", "tsd", "tsl", "tsg", "tsp",
                "tiw", "tid", "til", "tig", "tip",
                "betwin", "betgold", "betwingold",
                "killcrew_person", "deathcrew_person",
                "occupied",
                "inherit_earned", "inherit_spent",
                "inherit_earned_dyn", "inherit_earned_act", "inherit_spent_dyn",
            ),
            RankColumn.entries.map { it.column },
        )
    }

    private fun generalLogic(id: Int, officerLevel: Int) = opensamguk.logic.domain.General(
        id = id,
        nationId = 1,
        cityId = 5,
        leadership = 80,
        strength = 70,
        intel = 60,
        injury = 0,
        experience = 0.0,
        dedication = 0.0,
        officerLevel = officerLevel,
        gold = 100,
        rice = 50,
    )
}
