package opensamguk.logic.world

import kotlinx.serialization.json.JsonPrimitive
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.RawAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3 — `UnblockScoutAction` 충실 포트 게이트 (PHP grand truth `Event/Action/UnblockScoutAction.php:7-24`).
 *
 * 검증:
 *   - **draw 0**: leaf 본문은 RNG를 전혀 받지 않고 생성하지도 않는다(결정적 effect만).
 *   - **effect**: 모든 국가 `scout=0` set (PHP `$db->update('nation',['scout'=>0])`).
 *   - **조건부 KV**: 생성자 인자 `blockChangeScout`가 null이면 game_env KV 미설정, true/false면 그 값으로 set.
 *   - **register by-name**: F2 factory에 `UnblockScoutAction` 이름으로 등록되고, args 디코드가 PHP의
 *     nullable bool 생성자를 따른다.
 *   - **richer-ctx seam**: 월드 뷰가 없으면 no-op(데몬 미배선 시 방어).
 */
class UnblockScoutActionTest {

    /** UnblockScoutWorldView의 기록 fake — 호출 여부/인자만 캡처. RNG는 일절 받지 않는다(draw-0 증명). */
    private class RecordingWorld : UnblockScoutWorldView {
        var scoutSetTo: Int? = null
        var scoutSetCount = 0
        var kvSetTo: Boolean? = null
        var kvSetCount = 0

        override fun setAllNationScout(value: Int) {
            scoutSetTo = value
            scoutSetCount++
        }

        override fun setGameEnvBlockChangeScout(value: Boolean) {
            kvSetTo = value
            kvSetCount++
        }
    }

    /** richer 틱 컨텍스트 stub: ENV_WORLD에 fake 월드 뷰만 싣는다(RNG 키 없음 = leaf가 draw할 길 없음). */
    private fun ctxWith(world: UnblockScoutWorldView?): EventActionContext =
        object : EventActionContext {
            override val env: Map<String, Any?> =
                linkedMapOf<String, Any?>("year" to 200, "month" to 1, "currentEventID" to null).apply {
                    if (world != null) put(UnblockScoutAction.ENV_WORLD, world)
                }
        }

    @Test
    fun `effect — 모든 국가 scout를 0으로 set (PHP update nation scout=0)`() {
        val world = RecordingWorld()
        UnblockScoutAction().run(ctxWith(world))

        assertEquals(1, world.scoutSetCount, "scout 일괄 set은 정확히 1회")
        assertEquals(0, world.scoutSetTo, "PHP는 scout=0(정찰 해제)으로 set")
    }

    @Test
    fun `blockChangeScout가 null이면 game_env KV를 건드리지 않는다 (PHP null-가드)`() {
        val world = RecordingWorld()
        UnblockScoutAction(blockChangeScout = null).run(ctxWith(world))

        assertEquals(0, world.kvSetCount, "blockChangeScout=null → KV set 생략")
        assertNull(world.kvSetTo)
        // scout 해제는 KV와 무관하게 항상 일어난다.
        assertEquals(0, world.scoutSetTo)
    }

    @Test
    fun `blockChangeScout=true면 block_change_scout KV를 true로 set`() {
        val world = RecordingWorld()
        UnblockScoutAction(blockChangeScout = true).run(ctxWith(world))

        assertEquals(1, world.kvSetCount, "blockChangeScout=true → KV set 1회")
        assertEquals(true, world.kvSetTo)
        assertEquals(0, world.scoutSetTo, "scout 해제도 동반")
    }

    @Test
    fun `blockChangeScout=false면 block_change_scout KV를 false로 set`() {
        val world = RecordingWorld()
        UnblockScoutAction(blockChangeScout = false).run(ctxWith(world))

        assertEquals(1, world.kvSetCount, "false도 null이 아니므로 set 1회")
        assertEquals(false, world.kvSetTo)
    }

    @Test
    fun `draw 0 — leaf는 RNG를 받지도 생성하지도 않는다`() {
        // env에 어떤 RNG/시드 키도 넣지 않은 컨텍스트로 실행해도 effect가 정상 수행된다 =
        // leaf 본문이 난수에 의존하지 않음을 증명(결정적 effect만).
        val world = RecordingWorld()
        UnblockScoutAction(blockChangeScout = true).run(ctxWith(world))

        assertEquals(0, world.scoutSetTo)
        assertEquals(true, world.kvSetTo)
        // 같은 입력 → 같은 출력(난수 분기 없음): 두 번 더 돌려도 인자 동일.
        val world2 = RecordingWorld()
        UnblockScoutAction(blockChangeScout = true).run(ctxWith(world2))
        assertEquals(world.scoutSetTo, world2.scoutSetTo)
        assertEquals(world.kvSetTo, world2.kvSetTo)
    }

    @Test
    fun `월드 뷰가 없으면 no-op (데몬 미배선 방어)`() {
        // 던지지 않고 조용히 반환해야 한다(A-family richer-ctx 관례: 뷰 부재 시 no-op).
        UnblockScoutAction(blockChangeScout = true).run(ctxWith(world = null))
        // 도달 = no throw. 추가 단언 불필요.
        assertTrue(true)
    }

    @Test
    fun `register — F2 factory에 UnblockScoutAction 이름으로 등록되고 무인자 생성된다`() {
        val factory = EventActionFactory()
        UnblockScoutAction.register(factory)
        assertTrue(factory.has("UnblockScoutAction"), "leaf는 PHP 클래스명으로 등록")

        // 시드 args 없음 → blockChangeScout=null(KV 미설정).
        val action: EventAction = factory.create(RawAction("UnblockScoutAction", emptyList()))
        assertTrue(action is UnblockScoutAction)

        val world = RecordingWorld()
        action.run(ctxWith(world))
        assertEquals(0, world.scoutSetTo)
        assertEquals(0, world.kvSetCount, "무인자 시드는 KV를 건드리지 않음")
    }

    @Test
    fun `register — args 0번 bool이 blockChangeScout로 디코드된다 (PHP nullable bool 생성자)`() {
        val factory = EventActionFactory()
        UnblockScoutAction.register(factory)

        val trueAct = factory.create(RawAction("UnblockScoutAction", listOf(JsonPrimitive(true))))
        val worldT = RecordingWorld()
        trueAct.run(ctxWith(worldT))
        assertEquals(true, worldT.kvSetTo, "args[0]=true → KV true")

        val falseAct = factory.create(RawAction("UnblockScoutAction", listOf(JsonPrimitive(false))))
        val worldF = RecordingWorld()
        falseAct.run(ctxWith(worldF))
        assertEquals(false, worldF.kvSetTo, "args[0]=false → KV false")
        assertFalse(worldF.kvSetTo!!)
    }
}
