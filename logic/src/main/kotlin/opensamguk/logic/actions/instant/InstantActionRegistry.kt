package opensamguk.logic.actions.instant

/**
 * instant-action(즉시 액션) 레지스트리/분류기 (Area4 전제붕괴 마감용 FOUNDATION).
 *
 * PHP에서 `DieOnPrestart`/`DropItem`/`InstantRetreat`는 예약 커맨드([CommandRegistry]의 `che_*`)가
 * **아니라** `sammo\BaseAPI`를 상속한 **instant API 핸들러**다(`API/General/` 하위 `.php`의 `launch()`). 즉
 * "다음 장수 턴에 해소"되는 게 아니라 호출 즉시 1회 실행된다(프리스타트 자살/아이템 버리기/즉시 접경귀환).
 *
 * ## 왜 InstantNationCommandRegistry와 dispatch 모델이 다른가 (가장 중요한 정합성)
 * 외교 수락 3종([opensamguk.logic.actions.nation.InstantNationCommandRegistry])은 정식
 * [opensamguk.logic.actions.GeneralActionDefinition](=NationCommand)으로 [CommandRegistry]에 등록돼
 * 있어 **Model A**(turn-reserved 링: general_turn 예약 row + 데몬 poke, [ReservedTurnHandler]가 해소)로
 * 흐른다. 반면 이 세 instant-action은 [CommandRegistry] 커맨드가 아니라 **이미 `:common`에
 * [opensamguk.common.wire.TurnDaemonCommand] variant**(`DieOnPrestart`/`DropItem`/`InstantRetreat`)가
 * 정의돼 있다 → 따라서 betting/auction/유산-reset과 동일한 **Model B**(typed daemon-command intake:
 * [opensamguk.gameapi.reserve.CommandWireMapper.toCommand]가 typed 명령을 발행 → 데몬
 * [opensamguk.engine.run.TurnDaemonCommandDispatcher]가 핸들러로 라우팅)로 흐른다. 두 모델 모두
 * **데몬이 ChangeRecorder→JdbcFlushExecutor JDBC batch로만 write**하므로 one-daemon-write-rule을 지킨다
 * — game-api는 인테이크(Redis XADD)만 한다.
 *
 * ## 따라서 이 객체의 역할 = "정본 분류기 + 인테이크-코드 계약"
 * 두 번째 dispatch 진리(별도 definition map)를 만들지 않는다. Model B의 dispatch 진리는
 * [opensamguk.common.wire.TurnDaemonCommand] variant + 엔진 dispatcher이며 그게 정본이다. 이 객체는
 * "이 코드가 General-instant 액션인가?"를 판정하고, **각 명령 에이전트가 자기 코드를 어디에 등록해야
 * 하는지**(아래 [INTAKE_WIRING] 계약)를 한 곳에 못 박는다.
 *
 * ## 각 명령 에이전트(Impl 페이즈)가 해야 할 일 — [INTAKE_WIRING] 계약
 * 명령 본체(resolve/launch 포팅)는 Impl 페이즈 소유. 본 FOUNDATION 단계는 본체를 구현하지 않는다.
 * 각 instant-action 에이전트는:
 *  1. **logic 본체** — `logic/.../actions/instant/<Cmd>.kt`에 PHP `launch()`를 순수 effect 산출로 포팅
 *     (InheritResets 패턴: world/DB 없이 입력→Outcome). 본체는 Impl 페이즈.
 *  2. **엔진 핸들러** — `app/game-engine/.../intake/<Cmd>Handler.kt`(InheritResetHandler 패턴): 행동 장수
 *     해석 + env read + 본체 호출 + ChangeRecorder 델타. one-daemon-write 준수.
 *  3. **CommandWireMapper** — `intakeCodes`에 코드 추가(creator-then-consumer; 공유 파일이므로 Tier-0
 *     stub widening에서 빈 등록만 선행) + `toCommand()` when 분기에서 이미 존재하는
 *     [opensamguk.common.wire.TurnDaemonCommand] variant로 매핑.
 *  4. **TurnDaemonCommandDispatcher** — `dispatch()` when 분기에 variant→핸들러 바인딩 추가.
 *  5. **game-api intake** — [opensamguk.gameapi.controller.InstantActionController]가 코드별 endpoint를
 *     이미 노출(precheck/소유권 가드 → [opensamguk.gameapi.reserve.CommandReserveService.reserve]). 새
 *     컨트롤러를 만들지 말 것.
 */
object InstantActionRegistry {

    /**
     * General-instant 액션 코드-집합 — PHP `API/General/{DieOnPrestart,DropItem,InstantRetreat}.php`.
     * 각 코드는 **이미 [opensamguk.common.wire.TurnDaemonCommand] variant가 존재**하므로 Model B
     * (typed daemon-command intake)로 dispatch된다. 정본 키-집합 — 인테이크가 분류에 참조한다.
     *
     *  - `DieOnPrestart` ← 프리스타트(개전 전) 장수 자살. variant `TurnDaemonCommand.DieOnPrestart`.
     *    logic 본체: [opensamguk.logic.actions.instant.DieOnPrestart] (0-draw, [DieOnPrestartOutcome]).
     *  - `DropItem`      ← 보유 아이템 버리기. variant `TurnDaemonCommand.DropItem`(itemType 인자).
     *  - `InstantRetreat`← 즉시 접경귀환(내부에서 `che_접경귀환` 커맨드를 RandUtil로 실행, RNG-bearing).
     *    variant `TurnDaemonCommand.InstantRetreat`.
     */
    val INSTANT_ACTION_CODES: Set<String> = linkedSetOf(
        "DieOnPrestart",
        "DropItem",
        "InstantRetreat",
    )

    /** [code]가 General-instant 액션인가 — 인테이크의 분류기. */
    fun isInstantAction(code: String): Boolean = code in INSTANT_ACTION_CODES

    /**
     * 각 instant-action 에이전트가 따라야 할 인테이크 배선 계약(문서 상수). 코드 → "어느 typed
     * [opensamguk.common.wire.TurnDaemonCommand] variant로 매핑되는가". 본 FOUNDATION 단계에서는 본체를
     * 구현하지 않으므로, 이 맵은 **에이전트가 [opensamguk.gameapi.reserve.CommandWireMapper.toCommand]에
     * 추가할 when 분기의 정본 목록**으로만 쓰인다(코드 중복 진리 아님 — variant 자체가 진리, 여기엔
     * variant 이름만 기록). 새 variant를 만들 필요가 없다(셋 다 `:common`에 이미 존재).
     */
    val INTAKE_WIRING: Map<String, String> = linkedMapOf(
        "DieOnPrestart" to "TurnDaemonCommand.DieOnPrestart",
        "DropItem" to "TurnDaemonCommand.DropItem(itemType)",
        "InstantRetreat" to "TurnDaemonCommand.InstantRetreat",
    )
}
