package opensamguk.logic.world

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory

/**
 * A3 — `BlockScoutAction` 이벤트 리프(정찰/임관 차단 토글). draw 0.
 *
 * PHP grand truth: `Event/Action/BlockScoutAction.php:12-23`.
 *   1. `$db->update('nation', ['scout'=>1], true)` — **모든 국가**의 `scout` 플래그를 1로 설정
 *      (3번째 `true` 인자는 WHERE 없는 전체 업데이트 허용 가드 — 관측 동작은 전체 국가 갱신).
 *      opensamguk에서 `nation.scout`은 `nation.meta['scout']`에 탑승(임관 차단 검사 = Presets.kt:866-872).
 *   2. `$this->blockChangeScout !== null` 이면 `game_env` KV `block_change_scout`을 그 bool 값으로 set.
 *      생성자 인자가 null(미지정)이면 KV는 건드리지 않음(PHP 분기 그대로).
 *   3. `return [__CLASS__, $db->affectedRows()]` — 디스패처가 결과를 버림(EventAction.run = Unit).
 *
 * **RNG draw 없음**: 본문은 순수 일괄 갱신 + 조건부 KV set 뿐 — RandUtil/LiteHashDrbg 미사용(0-draw).
 *
 * **월드 변이 seam**: 전체 국가 `scout=1` 갱신 + `game_env` KV set은 데몬/G1 월드 컨텍스트가 구현하는
 * [BlockScoutWorld]에 위임(RaiseDisaster/AssignGeneralSpeciality가 세운 richer-context 관례). 짝
 * 액션 `UnblockScoutAction`(scout=0)이 동일 seam을 재사용한다. seam이 없으면 no-op(다른 리프와 동일).
 *
 * F2가 sealed base + [EventActionFactory]를 소유하므로, 이 리프는 이름으로 자기 등록만 한다
 * (plan §append protocol — 유일한 per-family 터치).
 */
class BlockScoutAction(
    /** PHP `?bool $blockChangeScout = null` — null이면 `block_change_scout` KV를 건드리지 않음. */
    val blockChangeScout: Boolean? = null,
) : EventAction {

    override fun run(ctx: EventActionContext) {
        val world = ctx.env[ENV_WORLD] as? BlockScoutWorld ?: return
        // (1) UPDATE nation SET scout=1 (모든 국가).
        world.setAllNationScout(1)
        // (2) blockChangeScout가 명시(non-null)된 경우에만 game_env KV set.
        if (blockChangeScout != null) {
            world.setBlockChangeScout(blockChangeScout)
        }
    }

    companion object {
        /** PHP 클래스명(F2 EventStore 시드의 `BlockScoutAction` 토큰과 일치). */
        const val NAME = "BlockScoutAction"

        /** 데몬이 월드 뷰를 스레딩하는 env 키(A-family ENV_WORLD 관례). */
        const val ENV_WORLD = "blockScoutWorld"

        /**
         * F2-owned [EventActionFactory]에 이름으로 등록(plan §append protocol).
         * 생성자 인자 `blockChangeScout`은 args[0]가 있으면 bool로 디코드, 없으면 null(PHP 기본값).
         */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args -> BlockScoutAction(decodeBlockChangeScout(args)) }

        /** args[0]를 nullable bool로 디코드(없으면 null = PHP `?bool ... = null`). */
        internal fun decodeBlockChangeScout(args: List<JsonElement>): Boolean? {
            if (args.isEmpty()) return null
            val content = (args[0] as JsonPrimitive).content
            return content.toBooleanStrictOrNull() ?: (content.toIntOrNull()?.let { it != 0 })
        }
    }
}

/**
 * `BlockScoutAction`(과 짝 `UnblockScoutAction`)이 변이하는 월드 seam — 데몬의 richer-context가 구현.
 * 최소·A3-owned로 유지해 리프가 자기 파일 경계 안에 머문다(F1/G1 데몬 어댑터가 구현).
 */
interface BlockScoutWorld {
    /** PHP `$db->update('nation', ['scout'=>value])` — **모든 국가**의 scout 플래그 설정(Block=1, Unblock=0). */
    fun setAllNationScout(value: Int)

    /** PHP `KVStorage::getStorage($db,'game_env')->setValue('block_change_scout', value)`. */
    fun setBlockChangeScout(value: Boolean)
}
