package opensamguk.logic.world

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory

/**
 * A3 이벤트 리프 — "UnblockScoutAction"의 충실 포트 (PHP frozen historical baseline (ADR-LITE-042; not current product authority):
 * `Event/Action/UnblockScoutAction.php:7-24`). 정찰(스카웃/임관) 차단을 **해제**하는 월드 이벤트로,
 * `BlockScoutAction`(scout=1 set)의 짝이다.
 *
 * PHP `run(array $env)`은 RNG draw가 **전혀 없는** 결정적 effect 두 가지를 수행한다:
 *   1. `$db->update('nation', ['scout'=>0])` — 모든 국가의 `scout` 플래그를 0으로 (정찰 가능).
 *   2. 생성자 인자 `blockChangeScout`가 null이 아니면
 *      `KVStorage::getStorage($db,'game_env')->setValue('block_change_scout', $blockChangeScout)` —
 *      game_env KV의 `block_change_scout`를 인자값(bool)으로 set. null이면 이 단계 생략.
 * 반환값 `[__CLASS__, affectedRows()]`는 dispatcher가 버리므로(PHP `runEventHandler`가 결과 무시,
 * FE3) Kotlin `run`은 Unit이다.
 *
 * **draw 0 — RNG 사용처 없음.** 정찰 해제는 순수 bulk-update + 조건부 KV set이라 어떤 난수도 뽑지 않는다.
 *
 * **월드 변이 seam:** 기본 dispatcher [EventActionContext]는 `env`만 들고 오므로, 틱이 더 풍부한
 * 컨텍스트를 공급한다 — `env`에 [ENV_WORLD] 키로 [UnblockScoutWorldView]를 실어 보낸다 (A-family가
 * RaiseDisaster/AssignGeneralSpeciality에서 확립한 richer-context 관례). 뷰가 없으면 no-op으로 방어
 * (G1 월드 어댑터가 실제 nation 일괄 write + game_env KV set을 구현할 때까지 leaf는 문서화된 stub).
 *
 * 이 leaf는 상태를 가지지 않으며 — 단 하나의 옵션 인자 [blockChangeScout]만 EventStore 시드의 args에서
 * 디코드해 보관한다. F2가 sealed base + 시드를 소유하고, A3는 leaf 정의 + [register] by-name 등록만 한다
 * (plan §append protocol).
 *
 * @param blockChangeScout PHP 생성자 `?bool $blockChangeScout = null`의 포트. null이면 KV를 건드리지
 *   않고, true/false면 game_env의 `block_change_scout`를 그 값으로 set.
 */
class UnblockScoutAction(private val blockChangeScout: Boolean? = null) : EventAction {

    override fun run(ctx: EventActionContext) {
        val world = ctx.env[ENV_WORLD] as? UnblockScoutWorldView ?: return

        // (1) PHP `$db->update('nation', ['scout'=>0])` — 모든 국가 scout 플래그 해제.
        world.setAllNationScout(0)

        // (2) PHP: blockChangeScout가 null이 아닐 때만 game_env KV set (null이면 생략).
        if (blockChangeScout != null) {
            world.setGameEnvBlockChangeScout(blockChangeScout)
        }
    }

    companion object {
        /** PHP 클래스명 (EventStore 시드의 `UnblockScoutAction` 토큰과 일치). */
        const val NAME: String = "UnblockScoutAction"

        /** 틱이 [UnblockScoutWorldView]를 실어 보내는 env 키 (A-family ENV_WORLD 관례). */
        const val ENV_WORLD: String = "unblockScoutWorld"

        /**
         * F2-소유 [EventActionFactory]에 by-name 등록 (F2 코드에 대한 유일한 per-family 터치, plan
         * §append protocol). 시드 args는 PHP 생성자 `?bool $blockChangeScout = null`을 그대로 따른다:
         * args가 비어 있으면 null(=KV 미설정), args[0]가 있으면 그 bool 값.
         */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args -> UnblockScoutAction(decodeBlockChangeScout(args)) }

        /** EventStore 시드 args[0]을 PHP의 nullable bool 인자로 디코드 (없으면 null). */
        private fun decodeBlockChangeScout(args: List<JsonElement>): Boolean? {
            if (args.isEmpty()) return null
            val raw = (args[0] as JsonPrimitive).content
            return raw.toBooleanStrictOrNull() ?: (raw.toIntOrNull()?.let { it != 0 })
        }
    }
}

/**
 * UnblockScoutAction이 변이하는 월드 seam (틱의 richer 컨텍스트가 공급). 최소·A3-소유로 유지해 leaf가
 * 자기 파일 경계 안에 머물도록 하고, 데몬 측 월드 어댑터(F1/G1 wiring)가 이를 구현한다. 각 메서드는 PHP
 * `$db->update(...)` / `KVStorage::setValue(...)`의 직접 포트다.
 */
interface UnblockScoutWorldView {
    /** PHP `UnblockScoutAction.php:14-16` `$db->update('nation', ['scout'=>0])` — 모든 국가 scout = [value]. */
    fun setAllNationScout(value: Int)

    /**
     * PHP `UnblockScoutAction.php:18-21` — game_env KV의 `block_change_scout`를 [value]로 set.
     * leaf는 `blockChangeScout != null`일 때만 호출한다(PHP의 null-가드와 동일).
     */
    fun setGameEnvBlockChangeScout(value: Boolean)
}
