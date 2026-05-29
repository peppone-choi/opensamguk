package opensamguk.logic.event

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FE3 — [EventDispatcher] (PHP `runEventHandler`, TurnExecutionHelper.php:370-391) + [EventStore]
 * (the F2-authored `$defaultEvents` seed, GameConstBase.php:447-531).
 *
 * Pins: rows fire `ORDER BY priority DESC, id ASC`; a falsy condition SKIPS all actions; actions run
 * in array order; `currentEventID` is injected per row; DeleteEvent removes the row ONCE and the
 * in-flight (frozen) set is unaffected by store mutation (a self-inserted row does NOT fire in the
 * same dispatch); the seed reproduces the GameConstBase row+action-array order EXACTLY.
 */
class EventDispatcherTest {

    /** A recording leaf that appends `name#currentEventID` to a shared log when run. */
    private class RecordingAction(val tag: String, val log: MutableList<String>) : EventAction {
        override fun run(ctx: EventActionContext) {
            log.add("$tag#${ctx.currentEventID}")
        }
    }

    private fun factoryWith(log: MutableList<String>): EventActionFactory {
        val f = EventActionFactory()
        for (name in listOf("A", "B", "C", "D")) {
            f.register(name) { args ->
                val tag = if (args.isNotEmpty()) "$name:${(args[0] as JsonPrimitive).content}" else name
                RecordingAction(tag, log)
            }
        }
        return f
    }

    // ── ORDER BY priority DESC, id ASC ───────────────────────────────────────
    @Test
    fun `rows fire priority DESC then id ASC`() {
        val log = mutableListOf<String>()
        val store = EventStore()
        // insertion order (=id ASC): low-pri(1000), high-pri(9000), high-pri(9000 again).
        store.insert("month", 1000, EventCodec.decodeCondition(jsonTrue()), listOf(raw("A")))     // id 1
        store.insert("month", 9000, EventCodec.decodeCondition(jsonTrue()), listOf(raw("B")))     // id 2
        store.insert("month", 9000, EventCodec.decodeCondition(jsonTrue()), listOf(raw("C")))     // id 3
        val dispatcher = EventDispatcher(store, factoryWith(log))

        dispatcher.run(EventTarget.MONTH) { mutableMapOf("year" to 190, "month" to 1) }

        // 9000 before 1000; within 9000, id 2 (B) before id 3 (C).
        assertEquals(listOf("B#2", "C#3", "A#1"), log)
    }

    // ── falsy condition skips ALL actions ────────────────────────────────────
    @Test
    fun `falsy condition skips all actions of that row`() {
        val log = mutableListOf<String>()
        val store = EventStore()
        store.insert("month", 9000, EventCondition.ConstBool(false), listOf(raw("A"), raw("B"))) // id1 skipped
        store.insert("month", 9000, EventCondition.ConstBool(true), listOf(raw("C")))            // id2 runs
        val dispatcher = EventDispatcher(store, factoryWith(log))

        dispatcher.run(EventTarget.MONTH) { mutableMapOf<String, Any?>() }

        assertEquals(listOf("C#2"), log)
    }

    // ── actions run in array order ───────────────────────────────────────────
    @Test
    fun `actions run in array order within a row`() {
        val log = mutableListOf<String>()
        val store = EventStore()
        store.insert("pre_month", 9000, EventCondition.ConstBool(true), listOf(raw("A"), raw("B"), raw("C")))
        val dispatcher = EventDispatcher(store, factoryWith(log))

        dispatcher.run(EventTarget.PRE_MONTH) { mutableMapOf<String, Any?>() }

        assertEquals(listOf("A#1", "B#1", "C#1"), log)
    }

    // ── only the requested target_code fires ─────────────────────────────────
    @Test
    fun `only rows of the requested target fire`() {
        val log = mutableListOf<String>()
        val store = EventStore()
        store.insert("pre_month", 9000, EventCondition.ConstBool(true), listOf(raw("A")))
        store.insert("month", 9000, EventCondition.ConstBool(true), listOf(raw("B")))
        val dispatcher = EventDispatcher(store, factoryWith(log))

        dispatcher.run(EventTarget.MONTH) { mutableMapOf<String, Any?>() }

        assertEquals(listOf("B#2"), log)
    }

    // ── DeleteEvent applied once; in-flight set frozen ───────────────────────
    @Test
    fun `DeleteEvent removes the row from the store but not the in-flight set`() {
        val log = mutableListOf<String>()
        val store = EventStore()
        // A row whose action deletes itself, then a later row that also runs.
        store.insert("month", 9000, EventCondition.ConstBool(true), listOf(raw("DeleteSelf")))  // id1
        store.insert("month", 9000, EventCondition.ConstBool(true), listOf(raw("B")))           // id2
        val factory = EventActionFactory()
        factory.register("DeleteSelf") { _ ->
            object : EventAction {
                override fun run(ctx: EventActionContext) {
                    log.add("del#${ctx.currentEventID}")
                    store.delete(ctx.currentEventID)
                }
            }
        }
        factory.register("B") { _ -> RecordingAction("B", log) }
        val dispatcher = EventDispatcher(store, factory)

        dispatcher.run(EventTarget.MONTH) { mutableMapOf<String, Any?>() }

        // Both ran (frozen set), id1 deleted from store.
        assertEquals(listOf("del#1", "B#2"), log)
        assertTrue(store.rowsFor(EventTarget.MONTH).none { it.id == 1 }, "id1 deleted")
        assertEquals(1, store.rowsFor(EventTarget.MONTH).size, "only id2 remains")
    }

    @Test
    fun `a self-inserted row does NOT fire in the same dispatch (frozen set)`() {
        val log = mutableListOf<String>()
        val store = EventStore()
        store.insert("month", 9000, EventCondition.ConstBool(true), listOf(raw("Spawner"))) // id1
        val factory = EventActionFactory()
        factory.register("Spawner") { _ ->
            object : EventAction {
                override fun run(ctx: EventActionContext) {
                    log.add("spawn")
                    store.insert("month", 9000, EventCondition.ConstBool(true), listOf(raw("Child")))
                }
            }
        }
        factory.register("Child") { _ -> RecordingAction("child", log) }
        val dispatcher = EventDispatcher(store, factory)

        dispatcher.run(EventTarget.MONTH) { mutableMapOf<String, Any?>() }

        assertEquals(listOf("spawn"), log) // the spawned Child did NOT fire this dispatch
        assertEquals(2, store.rowsFor(EventTarget.MONTH).size) // but it IS in the store now
    }

    // ── currentEventID injected per row ──────────────────────────────────────
    @Test
    fun `currentEventID equals each rows id during its actions`() {
        val log = mutableListOf<String>()
        val store = EventStore()
        store.insert("month", 9000, EventCondition.ConstBool(true), listOf(raw("A"))) // id1
        store.insert("month", 9000, EventCondition.ConstBool(true), listOf(raw("B"))) // id2
        val dispatcher = EventDispatcher(store, factoryWith(log))

        dispatcher.run(EventTarget.MONTH) { mutableMapOf<String, Any?>() }

        assertEquals(listOf("A#1", "B#2"), log)
    }

    // ── the F2-authored seed reproduces GameConstBase order EXACTLY ──────────
    @Test
    fun `default seed pre_month row is UpdateCitySupply then ProcessWarIncome at 9000`() {
        val store = EventStore.withDefaults()
        val pre = store.rowsFor(EventTarget.PRE_MONTH)
        assertEquals(1, pre.size)
        assertEquals(9000, pre[0].priority)
        assertEquals(listOf("UpdateCitySupply", "ProcessWarIncome"), pre[0].actions.map { it.name })
    }

    @Test
    fun `default seed month-1 row carries the 8 actions in exact order`() {
        val store = EventStore.withDefaults()
        val month = store.rowsFor(EventTarget.MONTH)
        // The month==1 / 9000 row is the first MONTH row in the seed (GameConstBase order, id ASC).
        val jan = month.first { it.priority == 9000 && it.condition is EventCondition.Date && (it.condition as EventCondition.Date).month == 1 }
        assertEquals(
            listOf(
                "MergeInheritPointRank", "ProcessSemiAnnual", "ProcessIncome", "ResetOfficerLock",
                "RaiseDisaster", "RandomizeCityTradeRate", "NewYear", "AssignGeneralSpeciality",
            ),
            jan.actions.map { it.name },
        )
        // ProcessSemiAnnual / ProcessIncome carry the "gold" arg.
        assertEquals("gold", (jan.actions[1].args[0] as JsonPrimitive).content)
        assertEquals("gold", (jan.actions[2].args[0] as JsonPrimitive).content)
    }

    @Test
    fun `default seed has the month-1000 UpdateNationLevel row and the united-5000 row`() {
        val store = EventStore.withDefaults()
        val lvl = store.rowsFor(EventTarget.MONTH).first { it.priority == 1000 }
        assertEquals(listOf("UpdateNationLevel", "ProvideNPCTroopLeader"), lvl.actions.map { it.name })
        val united = store.rowsFor(EventTarget.UNITED)
        assertEquals(1, united.size)
        assertEquals(5000, united[0].priority)
        assertEquals(listOf("MergeInheritPointRank"), united[0].actions.map { it.name })
    }

    @Test
    fun `default seed month-rows are ordered priority DESC then id ASC by rowsFor`() {
        val store = EventStore.withDefaults()
        val priorities = store.rowsFor(EventTarget.MONTH).map { it.priority }
        // Non-increasing priority; the 9000 block (4 rows) precedes the 2000 block (5 rows) precedes 1000.
        assertEquals(priorities.sortedDescending(), priorities)
        assertEquals(9000, priorities.first())
        assertEquals(1000, priorities.last())
    }

    @Test
    fun `default seed ids are assigned in array order across all targets`() {
        val store = EventStore.withDefaults()
        // First seed entry is the pre_month row → id 1.
        assertEquals(1, store.rowsFor(EventTarget.PRE_MONTH)[0].id)
    }

    @Test
    fun `ignoreDefaults yields an empty store`() {
        val store = EventStore.withDefaults(ignoreDefaultEvents = true)
        assertEquals(0, store.allRows().size)
    }

    private fun jsonTrue() = kotlinx.serialization.json.JsonPrimitive(true)
    private fun raw(name: String, vararg args: String): RawAction =
        RawAction(name, args.map { JsonPrimitive(it) })
}
