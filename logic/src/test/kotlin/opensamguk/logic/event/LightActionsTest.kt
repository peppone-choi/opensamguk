package opensamguk.logic.event

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * B5 / Task LT1 — the genuinely-light Month/PreMonth Action leaves:
 *   - NewYear            (PHP Event/Action/NewYear.php:10-30): general.age+1 (all) + belong+1 WHERE nation!=0 + 2 logs.
 *   - ResetOfficerLock   (ResetOfficerLock.php:9-22): nation.chief_set=0 (all) + city.officer_set=0 (all).
 *   - NoticeToHistoryLog (NoticeToHistoryLog.php:14-22): push msg to GLOBAL history log; throw if year+month absent.
 *   - AddGlobalBetray    (AddGlobalBetray.php:13-19): general.betray += cnt WHERE betray <= ifMax.
 *
 * Each leaf reads a [LightActionWorld] from `env[LightActionWorld.ENV_KEY]` (the richer-context seam the
 * A-family established for RaiseDisaster/AssignGeneralSpeciality); absent → no-op. Each is registered into
 * the F2-owned [EventActionFactory] by name, and F2's [EventStore.withDefaults] seed names them at the
 * exact slots (NewYear month==1 after RandomizeCityTradeRate; ResetOfficerLock month 1/4/7/10; the two
 * AddGlobalBetray in the DateRelative 4/1 row).
 *
 * MergeInheritPointRank is NOT here — it is Task LT3 (non-trivial, P6-inheritance bound).
 */
class LightActionsTest {

    /** A recording stub world: every leaf mutation + log push lands here for assertion. */
    private class StubWorld : LightActionWorld {
        var generalAgeIncremented = false
        var belongIncrementedWhereNationNonZero = false
        var nationChiefSetCleared = false
        var cityOfficerSetCleared = false
        val betrayBumps = mutableListOf<Pair<Int, Int>>() // (cnt, ifMax)
        val globalActionLogs = mutableListOf<String>()
        val globalHistoryLogs = mutableListOf<Pair<String, Int>>() // (msg, type)
        val generalHistoryLogs = mutableListOf<Pair<String, Int>>()

        override fun incrementAllGeneralAge() { generalAgeIncremented = true }
        override fun incrementBelongWhereNationNonZero() { belongIncrementedWhereNationNonZero = true }
        override fun clearAllNationChiefSet() { nationChiefSetCleared = true }
        override fun clearAllCityOfficerSet() { cityOfficerSetCleared = true }
        override fun addGlobalBetray(cnt: Int, ifMax: Int) { betrayBumps.add(cnt to ifMax) }
        override fun pushGlobalActionLog(msg: String) { globalActionLogs.add(msg) }
        override fun pushGlobalHistoryLog(msg: String, type: Int) { globalHistoryLogs.add(msg to type) }
        override fun pushGeneralHistoryLog(msg: String, type: Int) { generalHistoryLogs.add(msg to type) }
    }

    private fun env(world: LightActionWorld?, year: Int? = 200, month: Int? = 1): MutableMap<String, Any?> {
        val m = mutableMapOf<String, Any?>("currentEventID" to 1)
        if (world != null) m[LightActionWorld.ENV_KEY] = world
        if (year != null) m["year"] = year
        if (month != null) m["month"] = month
        return m
    }

    private fun ctx(env: MutableMap<String, Any?>): EventActionContext =
        object : EventActionContext { override val env = env }

    // ── NewYear ──────────────────────────────────────────────────────────────
    @Test
    fun `NewYear increments all general age and belong (nation!=0) and pushes logs`() {
        val w = StubWorld()
        NewYearAction().run(ctx(env(w, year = 193, month = 1)))
        assertTrue(w.generalAgeIncremented, "age+1 for ALL generals")
        assertTrue(w.belongIncrementedWhereNationNonZero, "belong+1 WHERE nation!=0")
        // <C>{year}</>년이 되었습니다. global action log (NewYear.php:16)
        assertEquals(listOf("<C>193</>년이 되었습니다."), w.globalActionLogs)
        // the general-history notice line (NewYear.php:17)
        assertEquals(1, w.generalHistoryLogs.size)
        assertTrue(w.generalHistoryLogs[0].first.contains("매너 있는 플레이"), "the NewYear notice line")
    }

    @Test
    fun `NewYear with no world is a no-op`() {
        NewYearAction().run(ctx(env(null)))
    }

    // ── ResetOfficerLock ─────────────────────────────────────────────────────
    @Test
    fun `ResetOfficerLock clears nation chief_set and city officer_set`() {
        val w = StubWorld()
        ResetOfficerLockAction().run(ctx(env(w)))
        assertTrue(w.nationChiefSetCleared, "nation.chief_set = 0 (all)")
        assertTrue(w.cityOfficerSetCleared, "city.officer_set = 0 (all)")
    }

    // ── NoticeToHistoryLog ───────────────────────────────────────────────────
    @Test
    fun `NoticeToHistoryLog pushes the message to the GLOBAL history log with its type`() {
        val w = StubWorld()
        NoticeToHistoryLogAction("<S>출병 제한이 풀렸습니다.</>", 42).run(ctx(env(w)))
        assertEquals(listOf("<S>출병 제한이 풀렸습니다.</>" to 42), w.globalHistoryLogs)
        assertTrue(w.globalActionLogs.isEmpty(), "Notice is a HISTORY log, not an action log")
    }

    @Test
    fun `NoticeToHistoryLog throws when both year and month are absent`() {
        // PHP NoticeToHistoryLog.php:16 throws RuntimeException when neither year nor month is present.
        val w = StubWorld()
        val e = env(w, year = null, month = null)
        assertFailsWith<IllegalStateException> {
            NoticeToHistoryLogAction("x", 0).run(ctx(e))
        }
    }

    @Test
    fun `NoticeToHistoryLog does NOT throw when only one of year or month is present`() {
        // PHP guard is `!key_exists('year') && !key_exists('month')` — AND, so one present is enough.
        val w = StubWorld()
        NoticeToHistoryLogAction("x", 0).run(ctx(env(w, year = 200, month = null)))
        assertEquals(1, w.globalHistoryLogs.size)
    }

    // ── AddGlobalBetray ──────────────────────────────────────────────────────
    @Test
    fun `AddGlobalBetray bumps betray by cnt where betray lte ifMax`() {
        val w = StubWorld()
        AddGlobalBetrayAction(1, 0).run(ctx(env(w)))
        AddGlobalBetrayAction(1, 1).run(ctx(env(w)))
        assertEquals(listOf(1 to 0, 1 to 1), w.betrayBumps)
    }

    @Test
    fun `AddGlobalBetray default args are cnt=1 ifMax=0 (PHP constructor defaults)`() {
        val w = StubWorld()
        AddGlobalBetrayAction().run(ctx(env(w)))
        assertEquals(listOf(1 to 0), w.betrayBumps)
    }

    // ── factory registration (the lone per-family touch) ─────────────────────
    @Test
    fun `each leaf is registered into the factory by its PHP class name`() {
        val factory = EventActionFactory().also { LightActions.register(it) }
        assertTrue(factory.has("NewYear"))
        assertTrue(factory.has("ResetOfficerLock"))
        assertTrue(factory.has("NoticeToHistoryLog"))
        assertTrue(factory.has("AddGlobalBetray"))
    }

    @Test
    fun `NoticeToHistoryLog factory decodes msg and type args verbatim`() {
        val factory = EventActionFactory().also { LightActions.register(it) }
        val raw = RawAction("NoticeToHistoryLog", listOf(JsonPrimitive("<S>1년 뒤 출병 제한이 풀립니다.</>"), JsonPrimitive("EVENT_YEAR_MONTH")))
        val action = factory.create(raw) as NoticeToHistoryLogAction
        assertEquals("<S>1년 뒤 출병 제한이 풀립니다.</>", action.msg)
    }

    @Test
    fun `AddGlobalBetray factory decodes cnt and ifMax int args`() {
        val factory = EventActionFactory().also { LightActions.register(it) }
        val raw = RawAction("AddGlobalBetray", listOf(JsonPrimitive(1), JsonPrimitive(1)))
        val action = factory.create(raw) as AddGlobalBetrayAction
        assertEquals(1, action.cnt)
        assertEquals(1, action.ifMax)
    }

    @Test
    fun `the F2 seed names the light leaves at their exact slots`() {
        val store = EventStore.withDefaults()
        // NewYear is at month==1, after RandomizeCityTradeRate, before AssignGeneralSpeciality.
        val jan = store.rowsFor(EventTarget.MONTH).first {
            it.priority == 9000 && (it.condition as? EventCondition.Date)?.month == 1
        }
        val names = jan.actions.map { it.name }
        assertEquals(names.indexOf("RandomizeCityTradeRate") + 1, names.indexOf("NewYear"),
            "NewYear directly follows RandomizeCityTradeRate")
        assertTrue(names.contains("ResetOfficerLock"))
        // The DateRelative 4/1 row carries TWO AddGlobalBetray + a NoticeToHistoryLog + DeleteEvent.
        val betrayRow = store.rowsFor(EventTarget.MONTH).first {
            it.actions.count { a -> a.name == "AddGlobalBetray" } == 2
        }
        val brNames = betrayRow.actions.map { it.name }
        assertEquals(listOf("NoticeToHistoryLog", "AddGlobalBetray", "AddGlobalBetray", "DeleteEvent"), brNames)
    }

    @Test
    fun `the default sortie-limit notices match the PHP schedule`() {
        val store = EventStore.withDefaults()
        val notices = store.rowsFor(EventTarget.MONTH)
            .filter { it.priority == 2000 && it.actions.firstOrNull()?.name == "NoticeToHistoryLog" }
            .mapNotNull { row ->
                val condition = row.condition as? EventCondition.DateRelative ?: return@mapNotNull null
                val message = row.actions.first().args.firstOrNull()?.jsonPrimitive?.content ?: ""
                (condition.year to condition.month) to message
            }
            .toMap()

        assertEquals("<S>2년 뒤 출병 제한이 풀립니다.</>", notices[1 to 1])
        assertEquals("<S>1년 뒤 출병 제한이 풀립니다.</>", notices[2 to 1])
        assertEquals("<S>6개월 뒤 출병 제한이 풀립니다. 병력을 준비해주세요.</>", notices[2 to 7])
        assertEquals("<S>출병 제한이 풀렸습니다.</>", notices[3 to 1])
    }
}
