package opensamguk.logic.event

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * FE2 — [EventCodec] JSON-array wire (de)serializer + [EventActionFactory].
 *
 * Faithful port of PHP `Condition::build` (Condition.php:7-44, RECURSIVE) + `Action::build`
 * (Action.php:12-26, NON-recursive). The `event` table stores `condition` jsonb (a single condition
 * value — bool or `[Class,...args]`) and `action` jsonb (an ARRAY of `[Class,...args]` action
 * arrays). The codec decodes the condition into an [EventCondition] tree and each action into a
 * [RawAction] (name + verbatim args) the [EventActionFactory] instantiates by name.
 */
class EventCodecTest {

    // ── Condition::build is RECURSIVE (Condition.php:7-44) ───────────────────
    @Test
    fun `decode bool condition -- ConstBool`() {
        assertEquals(EventCondition.ConstBool(true), EventCodec.decodeCondition(jsonOf("true")))
        assertEquals(EventCondition.ConstBool(false), EventCodec.decodeCondition(jsonOf("false")))
    }

    @Test
    fun `decode Date condition -- cmp year month (real PHP signature)`() {
        val c = EventCodec.decodeCondition(jsonOf("""["Date","==",null,1]"""))
        assertTrue(c is EventCondition.Date)
        assertEquals("==", c.cmp)
        assertEquals(null, c.year)
        assertEquals(1, c.month)
    }

    @Test
    fun `decode DateRelative condition`() {
        val c = EventCodec.decodeCondition(jsonOf("""["DateRelative","==",2,7]"""))
        assertTrue(c is EventCondition.DateRelative)
        assertEquals(2, c.year)
        assertEquals(7, c.month)
    }

    @Test
    fun `decode RemainNation condition`() {
        val c = EventCodec.decodeCondition(jsonOf("""["RemainNation",">",1]"""))
        assertTrue(c is EventCondition.RemainNation)
        assertEquals(">", c.cmp)
        assertEquals(1, c.cnt)
    }

    @Test
    fun `decode logic shortcut -- and over two children recursively built`() {
        // PHP: first elem is a logic keyword → Logic(mode, ...rest) building each child.
        val c = EventCodec.decodeCondition(jsonOf("""["and",["Date","==",null,1],["RemainNation",">",1]]"""))
        assertTrue(c is EventCondition.Logic)
        assertEquals("and", c.mode)
        assertEquals(2, c.conditions.size)
        assertTrue(c.conditions[0] is EventCondition.Date)
        assertTrue(c.conditions[1] is EventCondition.RemainNation)
    }

    @Test
    fun `decode not logic -- single child`() {
        val c = EventCodec.decodeCondition(jsonOf("""["not",["RemainNation",">",1]]"""))
        assertTrue(c is EventCondition.Logic)
        assertEquals("not", c.mode)
        assertEquals(1, c.conditions.size)
    }

    @Test
    fun `decode nested logic -- or of and`() {
        val c = EventCodec.decodeCondition(
            jsonOf("""["or",["and",["Date","==",null,1]],["RemainNation","==",1]]"""),
        )
        assertTrue(c is EventCondition.Logic && c.mode == "or")
        assertTrue(c.conditions[0] is EventCondition.Logic && (c.conditions[0] as EventCondition.Logic).mode == "and")
    }

    @Test
    fun `decode unknown condition class throws`() {
        assertFailsWith<IllegalArgumentException> {
            EventCodec.decodeCondition(jsonOf("""["NoSuchCondition",1]"""))
        }
    }

    // ── Action::build is NON-recursive (Action.php:12-26) ────────────────────
    @Test
    fun `decode action -- name plus verbatim args (non-recursive)`() {
        val raw = EventCodec.decodeAction(jsonOf("""["ProcessIncome","gold"]"""))
        assertEquals("ProcessIncome", raw.name)
        assertEquals(1, raw.args.size)
        assertEquals("gold", (raw.args[0] as JsonPrimitive).content)
    }

    @Test
    fun `decode action -- nested array arg passed verbatim NOT recursed`() {
        // Action::build does array_slice(args,1) — a nested array is one verbatim arg, not parsed.
        val raw = EventCodec.decodeAction(jsonOf("""["SomeAction",["a","b"],3]"""))
        assertEquals("SomeAction", raw.name)
        assertEquals(2, raw.args.size)
        assertTrue(raw.args[0] is JsonArray) // the nested array is passed whole, not decoded
    }

    @Test
    fun `decode action -- non-array throws (Action_php 13)`() {
        assertFailsWith<IllegalArgumentException> { EventCodec.decodeAction(jsonOf("true")) }
    }

    @Test
    fun `decode action list -- the event-table action column is an array of action arrays`() {
        val list = EventCodec.decodeActionList(jsonOf("""[["UpdateCitySupply"],["ProcessWarIncome"]]"""))
        assertEquals(listOf("UpdateCitySupply", "ProcessWarIncome"), list.map { it.name })
    }

    // ── EventActionFactory (F2-owned registry; families register leaves) ─────
    @Test
    fun `factory registers a leaf by name and instantiates it from raw args`() {
        val factory = EventActionFactory()
        factory.register("Echo") { args -> EchoAction((args[0] as JsonPrimitive).content) }
        val raw = EventCodec.decodeAction(jsonOf("""["Echo","hi"]"""))
        val action = factory.create(raw)
        assertTrue(action is EchoAction)
        assertEquals("hi", action.msg)
    }

    @Test
    fun `factory throws on unregistered action name`() {
        val factory = EventActionFactory()
        val raw = EventCodec.decodeAction(jsonOf("""["Unregistered"]"""))
        assertFailsWith<IllegalArgumentException> { factory.create(raw) }
    }

    // ── round-trip a $defaultEvents row (GameConstBase.php:454-465 month==1) ──
    @Test
    fun `round-trip condition encode then decode`() {
        val original = EventCodec.decodeCondition(jsonOf("""["Date","==",null,1]"""))
        val reEncoded = EventCodec.encodeCondition(original)
        val decoded = EventCodec.decodeCondition(reEncoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `round-trip an action list preserves names and order`() {
        val wire = """[["MergeInheritPointRank"],["ProcessSemiAnnual","gold"],["ProcessIncome","gold"]]"""
        val list = EventCodec.decodeActionList(jsonOf(wire))
        val reEncoded = EventCodec.encodeActionList(list)
        val again = EventCodec.decodeActionList(reEncoded)
        assertEquals(list.map { it.name }, again.map { it.name })
        assertEquals(
            listOf("MergeInheritPointRank", "ProcessSemiAnnual", "ProcessIncome"),
            again.map { it.name },
        )
    }

    private fun jsonOf(s: String) = Json.parseToJsonElement(s)

    /** A tiny test-only leaf to prove the factory registration + instantiation path. */
    private class EchoAction(val msg: String) : EventAction {
        override fun run(ctx: EventActionContext) { /* no-op for the test */ }
    }
}
