package opensamguk.logic.world

import kotlinx.serialization.json.JsonPrimitive
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.RawAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3 — `BlockScoutAction` 이벤트 리프 테스트(정찰/임관 차단 토글).
 *
 * Port target: PHP `Event/Action/BlockScoutAction.php:12-23`.
 *   - 모든 국가 `scout`=1 설정,
 *   - `blockChangeScout` non-null일 때만 `game_env` KV `block_change_scout` set,
 *   - **draw 0**(RandUtil/LiteHashDrbg 미사용 — 순수 일괄 갱신 + 조건부 KV set).
 *
 * 월드 변이는 [BlockScoutWorld] seam을 통해 검증(기록 스텁). seam이 없으면 no-op.
 */
class BlockScoutActionTest {

    /** 모든 월드 변이를 기록하는 스텁(검증용). */
    private class StubWorld : BlockScoutWorld {
        var nationScout: Int? = null
        var blockChangeScout: Boolean? = null
        var setScoutCalls = 0
        var setKvCalls = 0

        override fun setAllNationScout(value: Int) {
            nationScout = value
            setScoutCalls++
        }

        override fun setBlockChangeScout(value: Boolean) {
            blockChangeScout = value
            setKvCalls++
        }
    }

    private fun ctx(world: BlockScoutWorld?): EventActionContext {
        val env = mutableMapOf<String, Any?>("currentEventID" to 1, "year" to 200, "month" to 1)
        if (world != null) env[BlockScoutAction.ENV_WORLD] = world
        return object : EventActionContext { override val env = env }
    }

    // ── effect: 전체 국가 scout=1 ────────────────────────────────────────────
    @Test
    fun `sets scout=1 for all nations`() {
        val w = StubWorld()
        BlockScoutAction().run(ctx(w))
        assertEquals(1, w.nationScout, "UPDATE nation SET scout=1 (모든 국가)")
        assertEquals(1, w.setScoutCalls, "scout 갱신은 정확히 1회")
    }

    // ── blockChangeScout = null → KV 미터치 ──────────────────────────────────
    @Test
    fun `null blockChangeScout leaves game_env KV untouched`() {
        val w = StubWorld()
        BlockScoutAction(blockChangeScout = null).run(ctx(w))
        assertEquals(0, w.setKvCalls, "blockChangeScout=null이면 block_change_scout KV를 건드리지 않음")
        assertNull(w.blockChangeScout)
        // scout=1은 여전히 적용된다(KV 분기와 독립).
        assertEquals(1, w.nationScout)
    }

    // ── blockChangeScout = true / false → KV set ─────────────────────────────
    @Test
    fun `blockChangeScout true sets the block_change_scout KV to true`() {
        val w = StubWorld()
        BlockScoutAction(blockChangeScout = true).run(ctx(w))
        assertEquals(1, w.setKvCalls)
        assertEquals(true, w.blockChangeScout)
        assertEquals(1, w.nationScout)
    }

    @Test
    fun `blockChangeScout false sets the block_change_scout KV to false`() {
        val w = StubWorld()
        BlockScoutAction(blockChangeScout = false).run(ctx(w))
        assertEquals(1, w.setKvCalls)
        assertEquals(false, w.blockChangeScout)
        assertEquals(1, w.nationScout)
    }

    // ── seam 부재 → no-op ────────────────────────────────────────────────────
    @Test
    fun `no world view is a no-op`() {
        // BlockScoutWorld가 env에 없으면 다른 리프(NewYear/RaiseDisaster)와 동일하게 조용히 no-op.
        BlockScoutAction(blockChangeScout = true).run(ctx(null))
    }

    // ── 0-draw: 본문이 어떤 RNG도 소비하지 않음 ───────────────────────────────
    @Test
    fun `run draws zero randomness`() {
        // 본문은 RandUtil/LiteHashDrbg를 전혀 인스턴스화하지 않으며, env에 RNG도 요구하지 않는다.
        // 모든 인자 변형에서 동일한 결정적 효과만 적용됨 → draw 0 (재실행 시 동일).
        val w1 = StubWorld()
        val w2 = StubWorld()
        BlockScoutAction(blockChangeScout = true).run(ctx(w1))
        BlockScoutAction(blockChangeScout = true).run(ctx(w2))
        assertEquals(w1.nationScout, w2.nationScout)
        assertEquals(w1.blockChangeScout, w2.blockChangeScout)
        assertEquals(w1.setScoutCalls, w2.setScoutCalls)
        assertEquals(w1.setKvCalls, w2.setKvCalls)
    }

    // ── factory 등록(유일한 per-family 터치) ─────────────────────────────────
    @Test
    fun `leaf is registered into the factory by its PHP class name`() {
        val factory = EventActionFactory().also { BlockScoutAction.register(it) }
        assertTrue(factory.has("BlockScoutAction"))
    }

    @Test
    fun `factory with no args decodes blockChangeScout as null`() {
        val factory = EventActionFactory().also { BlockScoutAction.register(it) }
        val action = factory.create(RawAction("BlockScoutAction", emptyList())) as BlockScoutAction
        assertNull(action.blockChangeScout, "args 없음 → null (PHP ?bool = null)")
    }

    @Test
    fun `factory decodes a boolean blockChangeScout arg`() {
        val factory = EventActionFactory().also { BlockScoutAction.register(it) }
        val aTrue = factory.create(RawAction("BlockScoutAction", listOf(JsonPrimitive(true)))) as BlockScoutAction
        assertEquals(true, aTrue.blockChangeScout)
        val aFalse = factory.create(RawAction("BlockScoutAction", listOf(JsonPrimitive(false)))) as BlockScoutAction
        assertEquals(false, aFalse.blockChangeScout)
    }

    @Test
    fun `factory decodes an int-encoded blockChangeScout arg (1 to 0 truthiness)`() {
        // JSON 시드가 bool을 1/0 정수로 인코딩할 수 있어 PHP truthiness(0=false, !=0=true)를 따른다.
        val factory = EventActionFactory().also { BlockScoutAction.register(it) }
        val one = factory.create(RawAction("BlockScoutAction", listOf(JsonPrimitive(1)))) as BlockScoutAction
        assertEquals(true, one.blockChangeScout)
        val zero = factory.create(RawAction("BlockScoutAction", listOf(JsonPrimitive(0)))) as BlockScoutAction
        assertEquals(false, zero.blockChangeScout)
        assertFalse(zero.blockChangeScout!!)
    }
}
