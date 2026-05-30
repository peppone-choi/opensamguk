package opensamguk.logic.world

import opensamguk.common.constants.GameConst
import opensamguk.logic.domain.Nation
import opensamguk.logic.log.HistoryTokens
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3 / Task NL2 — `UpdateNationLevel` per-level-up side-effect sequence.
 *
 * Port target: `Event/Action/UpdateNationLevel.php:71-132`. The ORDER is load-bearing for the G1
 * log-sequence gate:
 *   (1) `level=new, gold += newLevel*1000, rice += newLevel*1000`        (:69-75)
 *   (2) `new ActionLogger(0, nationID, year, month, false)`               (:81)
 *   (3) switch(newLevel) → 작위 history strings (global+national); case7 ALSO sets aux
 *       `can_국기변경=1 / can_국호변경=1` (decode/set/encode, insertion-order preserved); cases 0/1
 *       emit NO log. 8/9 = the NEW 작위 templates (the APPEND, F7 tokens reuse the case-7 옹립).  (:84-114)
 *   (4) nation update                                                     (:117)
 *   (5) logger.flush()                                                    (:118)
 *   (6) insertIgnore nation_turn 휴식 rows for `chiefLevel in
 *       getNationChiefLevel(NEW level)..11` (`Util::range(a,12)` half-open) × `turnIdx 0..11`
 *       (`Util::range(maxChiefTurn=12)`).                                  (:120-130)
 *
 * The aux read-modify-write rides `meta['aux']` (opensamguk has no `nation.aux` column — aux is in
 * the `meta` jsonb; insertion order is the byte-comparable source).
 */
class UpdateNationLevelSideEffectTest {

    private fun nation(level: Int, gold: Int = 100, rice: Int = 50, aux: Map<String, Any?>? = null): Nation =
        Nation(
            id = 3, level = level, capitalCityId = 1, name = "촉", gold = gold, rice = rice,
            meta = if (aux == null) linkedMapOf() else linkedMapOf("aux" to LinkedHashMap(aux)),
        )

    private fun apply(level: Int, cityCnt: Int, lordName: String = "유비"): UpdateNationLevel.LevelUpEffects {
        val n = nation(level)
        val lu = UpdateNationLevel.computeLevelUp(n.level, cityCnt)!!
        return UpdateNationLevel.applyLevelUp(n, lordName, year = 200, month = 1, levelUp = lu)
    }

    @Test
    fun `gold and rice grant equals newLevel times 1000`() {
        // current 5 → cityCnt 16 → newLevel 6 → grant 6000.
        val e = apply(level = 5, cityCnt = 16)
        assertEquals(6, e.nation.level)
        assertEquals(100 + 6000, e.nation.gold)
        assertEquals(50 + 6000, e.nation.rice)
    }

    @Test
    fun `case 7 emits 옹립 and unlocks aux can flags insertion-ordered`() {
        // current 6 → cityCnt 21 → newLevel 7 (황제).
        val e = apply(level = 6, cityCnt = 21, lordName = "조조")
        val newText = GameConst.nationLevelByCityCnt09[7][0] as String   // 황제
        val oldText = GameConst.nationLevelByCityCnt09[6][0] as String   // 왕
        assertEquals(HistoryTokens.nationLevelUpGlobal(7, "촉", "조조", oldText, newText), e.globalHistoryLog)
        assertEquals(HistoryTokens.nationLevelUpNational(7, "촉", "조조", oldText, newText), e.nationalHistoryLog)

        @Suppress("UNCHECKED_CAST")
        val aux = e.nation.meta["aux"] as Map<String, Any?>
        assertEquals(1, (aux["can_국기변경"] as Number).toInt())
        assertEquals(1, (aux["can_국호변경"] as Number).toInt())
        // insertion order: can_국기변경 before can_국호변경 (PHP set order).
        assertEquals(listOf("can_국기변경", "can_국호변경"), aux.keys.toList())
    }

    @Test
    fun `lv8 and lv9 emit the new 작위 옹립 templates`() {
        val e8 = apply(level = 7, cityCnt = 28)
        val text8 = GameConst.nationLevelByCityCnt09[8][0] as String     // 대황제
        assertEquals(8, e8.nation.level)
        assertTrue(e8.globalHistoryLog.contains("【작위】"))
        assertTrue(e8.globalHistoryLog.contains("옹립"))
        assertTrue(e8.globalHistoryLog.contains(text8))

        val e9 = apply(level = 8, cityCnt = 36)
        val text9 = GameConst.nationLevelByCityCnt09[9][0] as String     // 천자
        assertEquals(9, e9.nation.level)
        assertTrue(e9.globalHistoryLog.contains(text9))
        assertTrue(e9.globalHistoryLog.contains("옹립"))
    }

    @Test
    fun `levels 0 and 1 emit no history log`() {
        // current 0 → cityCnt 1 → newLevel 1 (호족) → no log.
        val e = apply(level = 0, cityCnt = 1)
        assertEquals(1, e.nation.level)
        assertEquals("", e.globalHistoryLog)
        assertEquals("", e.nationalHistoryLog)
    }

    @Test
    fun `case 2 독립 and cases 3-6 emit the expected token`() {
        val e2 = apply(level = 1, cityCnt = 2)          // 군벌
        assertTrue(e2.globalHistoryLog.contains("독립"))
        val e3 = apply(level = 2, cityCnt = 5)          // 주자사
        assertTrue(e3.globalHistoryLog.contains("임명"))
        val e6 = apply(level = 5, cityCnt = 16)         // 왕
        assertTrue(e6.globalHistoryLog.contains("책봉"))
    }

    @Test
    fun `nation_turn 휴식 seed spans chief range times 12 turns`() {
        // newLevel 7 → getNationChiefLevel(7)=5 → chiefLevel 5..11 (7 levels) × 12 turns = 84 rows.
        val e = apply(level = 6, cityCnt = 21)
        val expectedChiefStart = GameConst.getNationChiefLevel(7)   // 5
        assertEquals(5, expectedChiefStart)
        val chiefLevels = e.nationTurnSeed.map { it.officerLevel }.distinct().sorted()
        assertEquals((5..11).toList(), chiefLevels)
        val turnIdxs = e.nationTurnSeed.filter { it.officerLevel == 5 }.map { it.turnIdx }.sorted()
        assertEquals((0..11).toList(), turnIdxs)
        assertEquals(7 * 12, e.nationTurnSeed.size)
        // every row is a 휴식 with null arg, brief 휴식, on this nation.
        assertTrue(e.nationTurnSeed.all { it.action == "휴식" && it.arg == null && it.brief == "휴식" && it.nationId == 3 })
    }

    @Test
    fun `nation_turn seed uses the REASSIGNED new level chief range (not old)`() {
        // jump 0→9 (cityCnt 36). getNationChiefLevel(9)=5 → range 5..11, NOT getNationChiefLevel(0)=11.
        val e = apply(level = 0, cityCnt = 36)
        assertEquals(9, e.nation.level)
        assertEquals(5, GameConst.getNationChiefLevel(9))
        assertEquals((5..11).toList(), e.nationTurnSeed.map { it.officerLevel }.distinct().sorted())
    }
}
