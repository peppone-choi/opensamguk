package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port-faithful tests for `preUpdateMonthly` (P3 / AREA B1 / Task PRE1) — research Unit 8.
 *
 * PHP grand truth `func_gamerule.php:189-257` — the ordered P1-P7 side-effect set. **Runs at L6 of the
 * MonthlyPipeline, BETWEEN PreMonth events and turnDate — ORDER load-bearing** (the G1 log-sequence +
 * numeric gate):
 *
 *   P1  $result = LogHistory();  if ($result == false) return false;     // abort the tick
 *   P2  read startyear/year/month from game_env
 *   P3  general_access_log.refresh_score_total = floor(refresh_score_total * 0.99)   // SQL floor, NOT phpRound
 *   P4  general.makelimit = greatest(0, makelimit - 1)
 *   P5  nation.strategic_cmd_limit = greatest(0, -1); surlimit = greatest(0, -1); rate_tmp = rate
 *   P6  gameStor.develcost = (year - startyear + 10) * 2
 *   P7  city.state CASE machine (31→0,32→31,33→0,34→33,41→0,42→41,43→42, else unchanged);
 *       term = greatest(0, term - 1); conflict = '{}' IF term == 0 (after decrement);
 *       per-nation spy decay: entry<=1 unset, else -1.
 *
 * **rate_tmp = rate is the parity-critical snapshot** — ProcessIncome reads rate_tmp NOT rate, so tax is
 * frozen at month-start. A subsequent rate change must NOT affect rate_tmp.
 */
class PreUpdateMonthlyTest {

    private fun nation(
        id: Int, strategicCmdLimit: Int = 0, surlimit: Int = 0, rate: Double = 0.0,
        spy: Map<Int, Int> = emptyMap(),
    ) = PreUpdateNation(id, strategicCmdLimit, surlimit, rate, spy)

    private fun city(
        id: Int, state: Int = 0, term: Int = 0, conflict: String = "{1:2}",
    ) = PreUpdateCity(id, state, term, conflict)

    // --- P1: LogHistory abort ---

    @Test
    fun `LogHistory false aborts and returns false`() {
        val result = preUpdateMonthly(
            logHistorySucceeded = false,
            year = 200, startYear = 184,
            accessLogs = listOf(PreUpdateAccessLog(1, 1000)),
            generals = listOf(PreUpdateGeneral(1, makelimit = 5)),
            nations = listOf(nation(1)),
            cities = listOf(city(1)),
        )
        assertFalse(result.succeeded)
        // Aborted: no side-effect payload produced.
        assertTrue(result.accessLogs.isEmpty())
        assertTrue(result.generals.isEmpty())
        assertTrue(result.nations.isEmpty())
        assertTrue(result.cities.isEmpty())
    }

    @Test
    fun `LogHistory true succeeds`() {
        val result = preUpdateMonthly(
            logHistorySucceeded = true,
            year = 200, startYear = 184,
            accessLogs = emptyList(), generals = emptyList(), nations = emptyList(), cities = emptyList(),
        )
        assertTrue(result.succeeded)
    }

    // --- P3: refresh_score_total floored ×0.99 ---

    @Test
    fun `refresh score floored times 0_99`() {
        val result = preUpdateMonthly(
            logHistorySucceeded = true, year = 200, startYear = 184,
            accessLogs = listOf(
                PreUpdateAccessLog(1, 1000),   // floor(990.0) = 990
                PreUpdateAccessLog(2, 101),    // floor(99.99) = 99
                PreUpdateAccessLog(3, 0),      // floor(0) = 0
            ),
            generals = emptyList(), nations = emptyList(), cities = emptyList(),
        )
        assertEquals(990, result.accessLogs.single { it.generalId == 1 }.newRefreshScore)
        assertEquals(99, result.accessLogs.single { it.generalId == 2 }.newRefreshScore)
        assertEquals(0, result.accessLogs.single { it.generalId == 3 }.newRefreshScore)
    }

    // --- P4: makelimit greatest(0, -1) ---

    @Test
    fun `makelimit decrements floored at zero`() {
        val result = preUpdateMonthly(
            logHistorySucceeded = true, year = 200, startYear = 184,
            accessLogs = emptyList(),
            generals = listOf(
                PreUpdateGeneral(1, makelimit = 5),  // 4
                PreUpdateGeneral(2, makelimit = 0),  // 0 (floored)
                PreUpdateGeneral(3, makelimit = 1),  // 0
            ),
            nations = emptyList(), cities = emptyList(),
        )
        assertEquals(4, result.generals.single { it.generalId == 1 }.newMakelimit)
        assertEquals(0, result.generals.single { it.generalId == 2 }.newMakelimit)
        assertEquals(0, result.generals.single { it.generalId == 3 }.newMakelimit)
    }

    // --- P5: limits decrement + rate_tmp snapshot ---

    @Test
    fun `nation limits decrement floored and rate_tmp snapshots rate`() {
        val result = preUpdateMonthly(
            logHistorySucceeded = true, year = 200, startYear = 184,
            accessLogs = emptyList(), generals = emptyList(),
            nations = listOf(
                nation(1, strategicCmdLimit = 3, surlimit = 72, rate = 15.0),
                nation(2, strategicCmdLimit = 0, surlimit = 0, rate = 20.5),
            ),
            cities = emptyList(),
        )
        val n1 = result.nations.single { it.nationId == 1 }
        assertEquals(2, n1.newStrategicCmdLimit)
        assertEquals(71, n1.newSurlimit)
        assertEquals(15.0, n1.newRateTmp)
        val n2 = result.nations.single { it.nationId == 2 }
        assertEquals(0, n2.newStrategicCmdLimit)   // floored
        assertEquals(0, n2.newSurlimit)             // floored
        assertEquals(20.5, n2.newRateTmp)
    }

    @Test
    fun `rate_tmp is a snapshot - later rate change does not affect it`() {
        val n = nation(1, rate = 18.0)
        val result = preUpdateMonthly(
            logHistorySucceeded = true, year = 200, startYear = 184,
            accessLogs = emptyList(), generals = emptyList(),
            nations = listOf(n), cities = emptyList(),
        )
        // The snapshot captured 18.0; mutating the input afterward is irrelevant (it's a value copy),
        // and the produced rate_tmp equals the rate AT snapshot time.
        assertEquals(18.0, result.nations.single().newRateTmp)
    }

    // --- P6: develcost ---

    @Test
    fun `develcost is year minus startyear plus ten times two`() {
        val result = preUpdateMonthly(
            logHistorySucceeded = true, year = 200, startYear = 184,
            accessLogs = emptyList(), generals = emptyList(), nations = emptyList(), cities = emptyList(),
        )
        assertEquals((200 - 184 + 10) * 2, result.develcost)   // 52
    }

    @Test
    fun `develcost at start year is twenty`() {
        val result = preUpdateMonthly(
            logHistorySucceeded = true, year = 184, startYear = 184,
            accessLogs = emptyList(), generals = emptyList(), nations = emptyList(), cities = emptyList(),
        )
        assertEquals(20, result.develcost)   // (0 + 10) * 2
    }

    // --- P7: city.state CASE machine ---

    @Test
    fun `city state CASE machine transitions each arm`() {
        val states = mapOf(31 to 0, 32 to 31, 33 to 0, 34 to 33, 41 to 0, 42 to 41, 43 to 42)
        val cities = states.keys.mapIndexed { i, s -> city(id = i + 1, state = s, term = 5) }
        val result = preUpdateMonthly(
            logHistorySucceeded = true, year = 200, startYear = 184,
            accessLogs = emptyList(), generals = emptyList(), nations = emptyList(),
            cities = cities,
        )
        cities.forEachIndexed { i, c ->
            val out = result.cities.single { it.cityId == c.id }
            assertEquals(states.getValue(c.state), out.newState, "state ${c.state} should map to ${states.getValue(c.state)}")
        }
    }

    @Test
    fun `city state unchanged for non-CASE values`() {
        val result = preUpdateMonthly(
            logHistorySucceeded = true, year = 200, startYear = 184,
            accessLogs = emptyList(), generals = emptyList(), nations = emptyList(),
            cities = listOf(city(id = 1, state = 0, term = 3), city(id = 2, state = 50, term = 3)),
        )
        assertEquals(0, result.cities.single { it.cityId == 1 }.newState)
        assertEquals(50, result.cities.single { it.cityId == 2 }.newState)
    }

    @Test
    fun `city term decrements floored and conflict cleared only when term hits zero`() {
        val result = preUpdateMonthly(
            logHistorySucceeded = true, year = 200, startYear = 184,
            accessLogs = emptyList(), generals = emptyList(), nations = emptyList(),
            cities = listOf(
                city(id = 1, state = 0, term = 1, conflict = "{1:2}"),  // term→0 → conflict '{}'
                city(id = 2, state = 0, term = 3, conflict = "{1:2}"),  // term→2 → conflict unchanged
                city(id = 3, state = 0, term = 0, conflict = "{1:2}"),  // term stays 0 → conflict '{}'
            ),
        )
        val c1 = result.cities.single { it.cityId == 1 }
        assertEquals(0, c1.newTerm); assertEquals("{}", c1.newConflict)
        val c2 = result.cities.single { it.cityId == 2 }
        assertEquals(2, c2.newTerm); assertEquals("{1:2}", c2.newConflict)
        val c3 = result.cities.single { it.cityId == 3 }
        assertEquals(0, c3.newTerm); assertEquals("{}", c3.newConflict)
    }

    // --- P7 (cont): per-nation spy decay ---

    @Test
    fun `spy decay decrements and removes entries that hit one or below`() {
        val result = preUpdateMonthly(
            logHistorySucceeded = true, year = 200, startYear = 184,
            accessLogs = emptyList(), generals = emptyList(),
            nations = listOf(
                nation(1, spy = mapOf(10 to 3, 11 to 1, 12 to 2)),  // 10→2, 11 removed, 12→1
                nation(2, spy = emptyMap()),                          // no change
            ),
            cities = emptyList(),
        )
        val n1 = result.nations.single { it.nationId == 1 }.newSpy
        assertEquals(mapOf(10 to 2, 12 to 1), n1)   // city 11 (was 1) removed
        assertTrue(result.nations.single { it.nationId == 2 }.newSpy.isEmpty())
    }

    // --- ORDER: rate_tmp (P5) precedes develcost (P6) precedes state decay (P7) ---

    @Test
    fun `side-effect order is recorded P3 to P7`() {
        val result = preUpdateMonthly(
            logHistorySucceeded = true, year = 200, startYear = 184,
            accessLogs = listOf(PreUpdateAccessLog(1, 1000)),
            generals = listOf(PreUpdateGeneral(1, makelimit = 5)),
            nations = listOf(nation(1, strategicCmdLimit = 3, surlimit = 72, rate = 15.0)),
            cities = listOf(city(1, state = 31, term = 1)),
        )
        // The result exposes the ordered phase log so G1 can diff the side-effect sequence.
        assertEquals(
            listOf("refresh_score", "makelimit", "nation_limits_rate_tmp", "develcost", "city_state", "spy"),
            result.phaseOrder,
        )
    }

    // --- F1 interface adapter ---

    @Test
    fun `PreUpdateMonthlyHook implements F1 interface and applies the result`() {
        var applied: PreUpdateMonthlyResult? = null
        val ctx = object : PreUpdateMonthlyContext {
            override val env: Map<String, Any?> = mapOf("year" to 200, "startYear" to 184)
            override val currentEventID: Int = 0
            override fun logHistory(): Boolean = true
            override fun accessLogs() = listOf(PreUpdateAccessLog(1, 1000))
            override fun generals() = listOf(PreUpdateGeneral(1, makelimit = 5))
            override fun nations() = listOf(nation(1, rate = 15.0))
            override fun cities() = listOf(city(1, state = 31, term = 1))
            override fun apply(result: PreUpdateMonthlyResult) { applied = result }
        }
        val hook = PreUpdateMonthlyHook(ctx)
        assertTrue(hook.run())
        assertEquals(990, applied!!.accessLogs.single().newRefreshScore)
        assertEquals(52, applied!!.develcost)
        assertEquals(0, applied!!.cities.single().newState)   // 31 → 0
    }

    @Test
    fun `PreUpdateMonthlyHook returns false and does not apply when LogHistory fails`() {
        var applied: PreUpdateMonthlyResult? = null
        val ctx = object : PreUpdateMonthlyContext {
            override val env: Map<String, Any?> = mapOf("year" to 200, "startYear" to 184)
            override val currentEventID: Int = 0
            override fun logHistory(): Boolean = false
            override fun accessLogs() = emptyList<PreUpdateAccessLog>()
            override fun generals() = emptyList<PreUpdateGeneral>()
            override fun nations() = emptyList<PreUpdateNation>()
            override fun cities() = emptyList<PreUpdateCity>()
            override fun apply(result: PreUpdateMonthlyResult) { applied = result }
        }
        val hook = PreUpdateMonthlyHook(ctx)
        assertFalse(hook.run())
        assertEquals(null, applied)   // aborted before apply
    }
}
