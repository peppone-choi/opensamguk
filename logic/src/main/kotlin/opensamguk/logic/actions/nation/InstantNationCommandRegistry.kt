package opensamguk.logic.actions.nation

import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition

/**
 * instant-nation 커맨드 레지스트리/로더 (A2 공통 인프라 — FOUNDATION).
 *
 * 턴 커맨드의 `NATION_TURN_COMMAND_KEYS` 대응물. **메시지-수락(accept) 경로가 외교 수락 커맨드를
 * dispatch할 때 참조하는 정본 키-집합**이다. 외교 서신(불가침/종전/불가침파기)을 수신국 군주가 수락하면,
 * 수락 커맨드(`che_불가침수락`/`che_종전수락`/`che_불가침파기수락`)가 **즉시(instant)** 실행되어야 한다
 * (PHP `DiplomaticMessage::agreeMessage()` → `$commandObj->run()`).
 *
 * ## 왜 별도 채널/엔진 definition map을 새로 만들지 않는가
 * 세 수락 커맨드는 이미 [CommandRegistry]에 정식 [NationCommand]로 등록되어 있고(CommandRegistry.kt:170-172),
 * game-api 수락 intake([opensamguk.gameapi.controller.DiplomaticMessageController]의 `/{id}/accept`)가
 * `CommandReserveService.reserve`로 **sanctioned turn-reserved 경로**(general_turn 예약 row + 데몬 poke,
 * one-daemon-write-rule 위반 아님)에 적재한다. 데몬은 [opensamguk.engine.turn.ReservedTurnHandler]가
 * 턴 해소 시 `CommandRegistry.resolve(code).resolve(...)`로 실행한다 — 즉 dispatch 경로는 이미 존재한다.
 *
 * 따라서 이 객체는 **두 번째 dispatch 진리**(별도 instant definition map)를 만들지 않고, 그 위에서
 * "이 코드가 외교 수락(instant-nation)인가?"를 판정하는 **정본 분류기 + 검증 로더**만 제공한다.
 * accept intake가 이 키-집합으로 dispatch 대상을 식별하고, [loadInstantNationActionSpecs]로 각 키가
 * 레지스트리에 실재(미배선 fallback 아님)하는지 부팅/검증 시점에 확인한다.
 *
 * ## hasFullConditionMet — constraint 게이트
 * PHP `agreeMessage()`는 `run()` 진입 시 `hasFullConditionMet()`를 강제한다(불만족이면 INVALID 반환).
 * opensamguk에서 이 게이트는 [ReservedTurnHandler]의 FULL-mode constraint 평가(`buildConstraints` →
 * `evaluateConstraints`)가 담당한다 — Deny면 휴식 폴백 + deny-log(PHP getFailString 동등). accept intake는
 * 예약 전 [DiplomaticMessageController]에서 사전 검증(만료/우편함 소유권/destGeneralID/year·month)을
 * 수행해 명백히 무효한 수락을 400으로 거른다. 두 게이트가 함께 PHP의 `hasFullConditionMet` 의미를 이룬다.
 */
object InstantNationCommandRegistry {

    /**
     * 외교 수락(instant-nation) 커맨드 키-집합 — PHP `DiplomaticMessage::agreeMessage()`가 서신 타입별로
     * 실행하는 세 수락 커맨드. accept intake가 dispatch 대상을 분류하는 정본 키-집합.
     *
     *  - `che_불가침수락` ← TYPE_NO_AGGRESSION 서신 수락 (che_불가침제의 짝)
     *  - `che_종전수락` ← TYPE_STOP_WAR 서신 수락 (che_종전제의 짝)
     *  - `che_불가침파기수락` ← TYPE_CANCEL_NA 서신 수락 (che_불가침파기제의 짝)
     */
    val INSTANT_NATION_COMMAND_KEYS: Set<String> = linkedSetOf(
        "che_불가침수락",
        "che_종전수락",
        "che_불가침파기수락",
    )

    /** [code]가 외교 수락(instant-nation) 커맨드인가 — accept intake의 dispatch 분류기. */
    fun isInstantNationCommand(code: String): Boolean = code in INSTANT_NATION_COMMAND_KEYS

    /**
     * 외교 서신 [DiplomaticActionType]별 수락 커맨드 키 — 서신 본문 `option.action` 코드를 수락 커맨드로
     * 매핑하는 정본 dispatcher. [opensamguk.logic.messaging.DiplomaticMessage.acceptCommandKey]와 동일한
     * 매핑(중복 진리가 아니라, 이 객체가 instant-nation 도메인의 정본 소유자이므로 여기에 둔다).
     *
     * @param actionCode 서신 본문 `option.action`(`no_aggression`/`cancel_na`/`stop_war`).
     * @return 대응 수락 커맨드 key, 또는 알 수 없는 action이면 null.
     */
    fun acceptCommandKeyFor(actionCode: String): String? = when (actionCode) {
        "no_aggression" -> "che_불가침수락"
        "stop_war" -> "che_종전수락"
        "cancel_na" -> "che_불가침파기수락"
        else -> null
    }

    /**
     * 동적 importer/로더 — [INSTANT_NATION_COMMAND_KEYS]의 각 키를 [CommandRegistry]에서 해석해
     * `(key → GeneralActionDefinition)` 맵으로 반환한다. 턴 커맨드의 `loadNationTurnActionSpecs` 대응물.
     *
     * fallback(미등록 시 [CommandRegistry.fallback])으로 떨어진 키는 **제외**한다 — 즉 실재 등록된
     * 수락 커맨드만 반환한다. accept intake/엔진이 부팅 시 이를 호출해 세 수락 커맨드가 모두 배선되었는지
     * 검증할 수 있다(반환 맵 크기 < 3이면 누락 = 외교 수락 family 미완).
     *
     * @param registry 정식 커맨드 레지스트리.
     * @return instant-nation 키 → 해석된 정의 맵(삽입 순서 보존, fallback 제외).
     */
    fun loadInstantNationActionSpecs(registry: CommandRegistry): Map<String, GeneralActionDefinition> {
        val out = LinkedHashMap<String, GeneralActionDefinition>()
        for (key in INSTANT_NATION_COMMAND_KEYS) {
            val def = registry.resolve(key)
            if (def !== registry.fallback) out[key] = def
        }
        return out
    }
}
