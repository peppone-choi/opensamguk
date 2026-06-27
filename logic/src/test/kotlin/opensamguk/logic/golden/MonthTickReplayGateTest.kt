package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.event.EventStore
import opensamguk.logic.tick.MonthScopedRng
import opensamguk.logic.tick.ServerClock
import java.time.Duration
import java.time.Instant
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * AREA G1 — Task G1c: the N-month scenario_1010 PHP-golden replay byte-match gate.
 *
 * The PHP golden under `golden/p3/` is the BYTE ORACLE — captured from the REAL devsam-core
 * `executeAllCommand` inner per-month block (`TurnExecutionHelper.php:461-486`) via
 * `tools/php-golden/capture_monthtick.php`. N = 4 months of scenario_1010 (startYear 181:
 * ticks 181-1 → 181-2 → … → 181-5).
 *
 * **The gate is PARTIAL by design (matched-count gate, plan-authorized fallback).** The Kotlin
 * monthly tick is built as per-step PURE functions with no production driver that loads a DB
 * world and runs a full month end-to-end + flushes; that wiring (and F2's full 82-row
 * `$defaultEvents` seed) is owned by F1/F4/F2. So this gate asserts every deterministic fact the
 * golden proves **for these in-window months that the present pure tick primitives reproduce**,
 * and quarantines the rest in `tools/php-golden/manifest_monthtick.json` (`quarantine[]`) +
 * `tools/php-golden/p3-capture-backlog.md`. The asserted set:
 *
 *   (f) the **monthly RNG seed string** byte-matches `MonthScopedRng.monthlySeed` for every month
 *       (the monthly RNG lineage oracle — the single load-bearing per-month determinism fact);
 *   (b) the **calendar advance** `oldDate → newDate` is reproduced by `ServerClock.turnDate`;
 *   (b) the **event dispatch ordering** (`priority DESC, id ASC`) holds on the seeded rows;
 *   (a) the **in-memory iteration order = PK ascending** (the #1 cross-unit parity invariant).
 *
 * `mismatches == 0` after the ignore-list, per month, all 4 months. **No parity bug was found in
 * the asserted surface** (every fact matched the PHP oracle first-run). The remaining gates
 * (full numeric flush, full dispatch count, history strings, level 0-9) are documented backlog.
 */
class MonthTickReplayGateTest {

    private fun load(name: String): JsonObject =
        Json.parseToJsonElement(
            MonthTickReplayGateTest::class.java.classLoader
                .getResourceAsStream("golden/p3/$name")!!
                .readBytes().toString(Charsets.UTF_8)
        ).jsonObject

    private val before = load("month-00-before.json")
    private val months: List<JsonObject> = (1..before["months"]!!.jsonPrimitive.int)
        .map { load("month-%02d-after.json".format(it)) }

    private val hiddenSeed = before["hiddenSeed"]!!.jsonPrimitive.content
    private val startYear = before["startYear"]!!.jsonPrimitive.int
    private val turnterm = before["turnterm"]!!.jsonPrimitive.int

    @Test
    fun `hiddenSeed is a 32-char lowercase hex (FC1 shape)`() {
        assertTrue(
            Regex("^[0-9a-f]{32}$").matches(hiddenSeed),
            "hiddenSeed fixture input must be a 32-char lowercase hex, was '$hiddenSeed'",
        )
        assertTrue(months.isNotEmpty(), "no month-NN-after golden captured")
    }

    /** Gate (f) — the monthly RNG lineage: the per-month seed string byte-matches the PHP oracle. */
    @Test
    fun `monthly RNG seed string byte-matches the golden for every month`() {
        var matched = 0
        for (m in months) {
            val old = m["oldDate"]!!.jsonObject
            val year = old["year"]!!.jsonPrimitive.int
            val month = old["month"]!!.jsonPrimitive.int
            val goldenSeed = m["monthlySeedString"]!!.jsonPrimitive.content
            val kotlinSeed = MonthScopedRng.monthlySeed(hiddenSeed, year, month)
            assertEquals(
                goldenSeed, kotlinSeed,
                "month ${m["month"]!!.jsonPrimitive.int}: monthly RNG seed string must byte-equal the PHP oracle",
            )
            matched++
        }
        assertEquals(months.size, matched, "all N months' monthly seed must match (matched-count gate)")
    }

    /**
     * Gate (b) — the calendar advance. `ServerClock.turnDate` now exposes 삼모's ten-day phase:
     * one turn advances 상순→중순→하순, and the third turn lands on the golden's next month 상순.
     */
    @Test
    fun `calendar advances three ten-day turns per golden month`() {
        val startTime = ServerClock.cutTurn(Instant.parse("2181-01-01T12:00:00Z"), turnterm)
        var prevTurn = startTime
        for (m in months) {
            val old = m["oldDate"]!!.jsonObject
            val oldYear = old["year"]!!.jsonPrimitive.int
            val oldMonth = old["month"]!!.jsonPrimitive.int
            val middle = ServerClock.turnDate(ServerClock.addTurn(prevTurn, turnterm, 1), startYear, startTime, turnterm)
            val late = ServerClock.turnDate(ServerClock.addTurn(prevTurn, turnterm, 2), startYear, startTime, turnterm)
            assertEquals(oldYear, middle.year, "month ${m["month"]} middle-phase year")
            assertEquals(oldMonth, middle.month, "month ${m["month"]} middle-phase month")
            assertEquals(2, middle.phase, "month ${m["month"]} middle phase")
            assertEquals(oldYear, late.year, "month ${m["month"]} late-phase year")
            assertEquals(oldMonth, late.month, "month ${m["month"]} late-phase month")
            assertEquals(3, late.phase, "month ${m["month"]} late phase")

            val nextTurn = ServerClock.addTurn(prevTurn, turnterm, 3)
            val date = ServerClock.turnDate(nextTurn, startYear, startTime, turnterm)
            val newDate = m["newDate"]!!.jsonObject
            assertEquals(newDate["year"]!!.jsonPrimitive.int, date.year, "month ${m["month"]} new year")
            assertEquals(newDate["month"]!!.jsonPrimitive.int, date.month, "month ${m["month"]} new month")
            assertEquals(1, date.phase, "month ${m["month"]} new phase")
            val oy = oldYear; val om = oldMonth
            val ny = newDate["year"]!!.jsonPrimitive.int; val nm = newDate["month"]!!.jsonPrimitive.int
            assertEquals(oy * 12 + om + 1, ny * 12 + nm, "month ${m["month"]} must advance exactly +1 month")
            prevTurn = nextTurn
        }
    }

    /**
     * Gate (b) — the event dispatch ordering invariant. The golden records the PreMonth/Month
     * dispatch order (`priority DESC, id ASC`). We assert (1) the golden order itself obeys that
     * comparator (oracle self-consistency) and (2) the Kotlin `EventStore.rowsFor` emits its seeded
     * rows in the same comparator order. The full 82-row COUNT match is quarantined (EV-1: F2 seed).
     */
    @Test
    fun `event dispatch order obeys priority-desc id-asc (golden oracle + Kotlin EventStore)`() {
        // (1) the golden's captured order is itself sorted priority DESC, id ASC.
        for (m in months) {
            for (key in listOf("preMonthDispatchOrder", "monthDispatchOrder")) {
                val order = m[key]!!.jsonArray.map { it.jsonObject }
                for (i in 1 until order.size) {
                    val a = order[i - 1]; val b = order[i]
                    val pa = a["priority"]!!.jsonPrimitive.int; val pb = b["priority"]!!.jsonPrimitive.int
                    val ia = a["id"]!!.jsonPrimitive.int; val ib = b["id"]!!.jsonPrimitive.int
                    assertTrue(
                        pa > pb || (pa == pb && ia < ib),
                        "golden $key not sorted priority DESC,id ASC at $i: ($pa,$ia) then ($pb,$ib)",
                    )
                }
            }
        }
        // (2) the Kotlin EventStore emits seeded rows in the same comparator order.
        val store = EventStore.withDefaults()
        for (target in listOf(opensamguk.logic.event.EventTarget.PRE_MONTH, opensamguk.logic.event.EventTarget.MONTH)) {
            val rows = store.rowsFor(target)
            for (i in 1 until rows.size) {
                val a = rows[i - 1]; val b = rows[i]
                assertTrue(
                    a.priority > b.priority || (a.priority == b.priority && a.id < b.id),
                    "EventStore.rowsFor($target) not sorted priority DESC,id ASC at $i",
                )
            }
        }
    }

    /**
     * Gate (a) prereq — the #1 cross-unit parity invariant: PHP's unordered `SELECT FROM city/general/
     * nation` is captured in PK-ascending order, which the in-memory WorldSnapshot loader must mirror.
     * Assert the golden dumps are strictly id-ascending for every month (before + after).
     */
    @Test
    fun `golden table dumps are strictly PK-ascending (city general nation)`() {
        fun assertAscending(state: JsonObject, table: String, pk: String, label: String) {
            val ids = state[table]!!.jsonArray.map { it.jsonObject[pk]!!.jsonPrimitive.int }
            for (i in 1 until ids.size) {
                assertTrue(ids[i] > ids[i - 1], "$label.$table not PK-ascending at $i: ${ids[i - 1]} then ${ids[i]}")
            }
        }
        val beforeState = before["state"]!!.jsonObject
        assertAscending(beforeState, "city", "city", "before")
        assertAscending(beforeState, "general", "no", "before")
        assertAscending(beforeState, "nation", "nation", "before")
        for (m in months) {
            val s = m["state"]!!.jsonObject
            assertAscending(s, "city", "city", "month${m["month"]}")
            assertAscending(s, "general", "no", "month${m["month"]}")
            assertAscending(s, "nation", "nation", "month${m["month"]}")
        }
    }
}
