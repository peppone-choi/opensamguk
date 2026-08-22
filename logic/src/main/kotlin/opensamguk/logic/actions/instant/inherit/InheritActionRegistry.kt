package opensamguk.logic.actions.instant.inherit

/**
 * inherit-action(유산 액션) 레지스트리/분류기 (Area4 전제붕괴 마감용 FOUNDATION).
 *
 * PHP `API/InheritAction/` 하위 `.php`의 instant 핸들러군. 이미 포팅·배선된 유산 reset/buy 5종
 * (ResetTurnTime/ResetSpecialWar/SetNextSpecialWar/BuyHiddenBuff/BuyRandomUnique — [InheritResets]/
 * [InheritBuys] + [opensamguk.engine.intake.InheritResetHandler] + [CommandWireMapper.intakeCodes]
 * `inheritResetTurnTime`/…)과 **완전히 같은 Model B(typed daemon-command intake) 패턴**으로 흐른다.
 *
 * 본 레지스트리는 **아직 미포팅인 inherit-action 두 종**의 분류기 + 배선 계약을 못 박는다:
 *  - `ResetStat`  ← 능력치 초기화(RNG-bearing: `nextRangeInt(3,5)` → 가변길이 `choiceUsingWeight` 루프,
 *    `API/InheritAction/ResetStat.php:148-149`). 골든 Y(/parity-wave).
 *  - `CheckOwner` ← 유산 1000pt로 상대 소유자명 확인(0-draw, private 메시지 2건 발송).
 *
 * ## dispatch 모델 = Model B (유산 reset/buy와 동일)
 * 이들은 [CommandRegistry] 커맨드가 아니라 instant inherit-action이므로, turn-reserved 링이 아니라
 * **typed [opensamguk.common.wire.TurnDaemonCommand] variant → 엔진
 * [opensamguk.engine.run.TurnDaemonCommandDispatcher] → 핸들러 → ChangeRecorder → JDBC flush** 경로를
 * 탄다. one-daemon-write-rule 준수: game-api는 인테이크만, 데몬만 write.
 *
 * **InstantActionRegistry와의 차이(중요):** General-instant 3종은 `:common`에 variant가 이미 있지만,
 * `ResetStat`/`CheckOwner`는 **`:common`에 [opensamguk.common.wire.TurnDaemonCommand] variant가 아직
 * 없다**(grep 확인). 따라서 각 inherit-action 에이전트는 자기 variant를 `:common`에 신설해야 한다
 * ([INTAKE_WIRING]의 `(NEW variant)` 표기). 유산 reset variant들(InheritResetTurnTime 등)이 정확한
 * 추가 템플릿이다.
 *
 * ## 각 inherit-action 에이전트(Impl 페이즈)가 해야 할 일 — [INTAKE_WIRING] 계약
 *  1. **logic 본체** — `logic/.../actions/instant/inherit/<Cmd>.kt`(InheritResets 패턴: world/DB 없이
 *     입력→[InheritResetOutcome] 류 Outcome). ResetStat의 RNG draw 순서(nextRangeInt→choiceUsingWeight
 *     루프)는 패러티 타깃 — 골든 게이트는 /parity-wave. CheckOwner는 0-draw + 메시지 2건.
 *  2. **`:common` variant 신설** — `TurnDaemonCommand.ResetStat`/`CheckOwner` + 대응 `*Ok`/`*Fail`
 *     [opensamguk.common.wire.TurnDaemonCommandResult] + serializer when-분기 등록(InheritResetTurnTime
 *     templates).
 *  3. **엔진 핸들러** — InheritResetHandler 패턴(행동 장수 + env read + 본체 호출 + recorder 델타).
 *  4. **CommandWireMapper** — `intakeCodes`에 코드 추가 + `toCommand()` 분기.
 *  5. **TurnDaemonCommandDispatcher** — variant→핸들러 바인딩.
 *  6. **game-api intake** — [opensamguk.gameapi.controller.InstantActionController]가 endpoint를 노출.
 */
object InheritActionRegistry {

    /**
     * 미포팅 inherit-action 코드-집합 — 동결된 역사 PHP 참고(ADR-LITE-042; 현재 제품 정본 아님):
     * `API/InheritAction/{ResetStat,CheckOwner}.php`. (이미 배선된 reset/buy 5종은 [CommandWireMapper.intakeCodes]가 정본이므로 여기 두지 않는다 —
     * 중복 진리 회피.)
     */
    val INHERIT_ACTION_CODES: Set<String> = linkedSetOf(
        "ResetStat",
        "CheckOwner",
    )

    /** [code]가 (미포팅) inherit-action인가 — 인테이크의 분류기. */
    fun isInheritAction(code: String): Boolean = code in INHERIT_ACTION_CODES

    /** [code]가 RNG draw를 갖는 inherit-action인가(골든 Y). ResetStat만 true. */
    fun isRngBearing(code: String): Boolean = code == "ResetStat"

    /**
     * 각 inherit-action 에이전트가 따라야 할 인테이크 배선 계약(문서 상수). 코드 → 신설할 typed
     * [opensamguk.common.wire.TurnDaemonCommand] variant. `(NEW variant)` = `:common`에 추가 필요
     * (General-instant 3종과 달리 아직 없음).
     */
    val INTAKE_WIRING: Map<String, String> = linkedMapOf(
        "ResetStat" to "TurnDaemonCommand.ResetStat (NEW variant — leadership/strength/intel + inheritBonusStat)",
        "CheckOwner" to "TurnDaemonCommand.CheckOwner (NEW variant — destGeneralID)",
    )
}
