package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.CommandWireMapper
import opensamguk.logic.actions.instant.InstantActionRegistry
import opensamguk.logic.actions.instant.inherit.InheritActionRegistry
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * instant / inherit-action 인테이크 엔드포인트 (Area4 전제붕괴 마감용 FOUNDATION).
 *
 * PHP `API/General/{DieOnPrestart,DropItem,InstantRetreat}.php` + `API/InheritAction/{ResetStat,
 * CheckOwner}.php`의 instant 핸들러군을 받는 **유일한 game-api 진입점**. 예약 커맨드(`che_*`)의
 * [opensamguk.gameapi.web.CommandController]에 대응하는 instant 버전.
 *
 * ## 어떻게 sanctioned 데몬 write 경로로 흐르는가 (one-daemon-write-rule 준수)
 * 이 컨트롤러는 **어떤 엔티티도 직접 write하지 않는다**(JPA write 없음). 대신
 * [opensamguk.gameapi.reserve.CommandReserveService.reserve]로 인테이크만 한다 —
 * [opensamguk.gameapi.controller.DiplomaticMessageController.accept]가 외교 수락을 예약하는 것과,
 * [opensamguk.gameapi.web.CommandController]가 `che_*`를 예약하는 것과 **동일한** sanctioned 경로다.
 *
 * instant/inherit-action 코드는 [CommandWireMapper.intakeCodes]에 등록되므로(각 명령 에이전트가 자기
 * 코드를 추가) `reserve.reserve(...)` 내부의 [CommandWireMapper.toCommand]가 typed
 * [opensamguk.common.wire.TurnDaemonCommand]를 command stream에 발행한다 → 데몬
 * [opensamguk.engine.run.TurnDaemonCommandDispatcher]가 핸들러로 라우팅 → ChangeRecorder →
 * JdbcFlushExecutor JDBC batch flush. 즉 **Model B**(typed daemon-command intake)로 흐른다.
 * game-api는 Redis XADD만, 실제 write(general 사망/아이템 삭제/스탯 재설정/메시지 발송)는 전부 데몬.
 *
 * ## FOUNDATION 가드 — 본체 미배선 코드는 409
 * 본 단계에서 명령 본체(toCommand 분기 + 엔진 핸들러)는 아직 없다(Impl 페이즈 소유). 명령 에이전트가
 * 자기 코드를 [CommandWireMapper.intakeCodes]에 등록하기 전까지 `toCommand`는 null을 반환하고, 그러면
 * reserve가 **잘못된 turn-reserved 링 경로**로 떨어진다(instant API는 링에 없으므로 액션 유실 = 패러티
 * 깨짐). 이를 막기 위해, 등록된 instant/inherit 코드가 아직 [CommandWireMapper.isIntakeCommand]가
 * 아니면 **409 Conflict**(`"아직 배선되지 않은 즉시 액션입니다."`)로 거른다 — 조용한 유실 대신 명시적
 * 미배선 신호. 코드가 배선되면 그대로 202로 흐른다(컨트롤러 수정 불요).
 *
 * ## 소유권 가드
 * 인증된 principal이 있으면 `?generalId=`는 본인 소유 장수여야 한다([GeneralResolver]) — 불일치 시 403.
 * (CommandController.command/ownershipGuard와 동일.) principal 부재(F2 전환기)면 param을 그대로 신뢰.
 *
 * ## precheck (FOUNDATION 범위 밖, 명시)
 * PHP instant API의 launch()는 자체 게이트(프리스타트 시각/국가 미소속/아이템 보유/유산 포인트/천하통일
 * 등)를 갖는다. 이 게이트는 **엔진 핸들러**가 본체와 함께 충실히 재현한다(InheritResetHandler가
 * isUnited/previousPoint/aux를 read해 deny string을 산출하는 패턴). 컨트롤러 레벨 precheck는 `che_*`의
 * [opensamguk.gameapi.precheck.CommandPrecheckService] 같은 공유 라이브러리가 instant 게이트를 커버하지
 * 않으므로 의도적으로 두지 않는다(있는 척 fabricate 금지). 명백한 무효(소유권)만 컨트롤러가 거른다.
 */
@RestController
@RequestMapping("/api/instant-action")
class InstantActionController(
    private val reserve: CommandReserveService,
    private val resolver: GeneralResolver,
) {
    /** 202 인테이크 성공 본문(CommandController.ReservedResponse와 동형). */
    data class IntakeAcceptedResponse(val status: String, val requestId: String, val code: String)

    /**
     * `POST /api/instant-action/{code}?generalId=` — instant/inherit 액션 1건 인테이크.
     *
     * @param code instant-action([InstantActionRegistry.INSTANT_ACTION_CODES]) 또는
     *   inherit-action([InheritActionRegistry.INHERIT_ACTION_CODES]) 코드.
     * @param generalId 행동 장수(인증 시 본인 소유 강제).
     * @param argJson DropItem(itemType)/ResetStat(leadership/strength/intel/inheritBonusStat)/
     *   CheckOwner(destGeneralID) 등 인자 JSON 본문. 무인자(DieOnPrestart/InstantRetreat)면 null.
     */
    @PostMapping("/{code}")
    fun instantAction(
        @AuthenticationPrincipal userId: Long?,
        @PathVariable code: String,
        @RequestParam generalId: Int,
        @RequestBody(required = false) argJson: String? = null,
    ): ResponseEntity<Any> {
        // (1) 분류 — 등록된 instant/inherit-action 코드만 받는다.
        val isInstant = InstantActionRegistry.isInstantAction(code)
        val isInherit = InheritActionRegistry.isInheritAction(code)
        if (!isInstant && !isInherit) {
            return ResponseEntity.badRequest().body(mapOf("error" to "알 수 없는 즉시 액션입니다."))
        }

        // (2) 소유권 가드 — 인증 시 generalId는 본인 소유여야 함(CommandController.ownershipGuard 동일).
        if (userId != null && generalId != resolver.resolveGeneralId(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }

        // (3) FOUNDATION 가드 — 본체(toCommand 분기 + 핸들러)가 아직 안 배선된 코드는 409로 명시 거부.
        //     (배선 = 명령 에이전트가 CommandWireMapper.intakeCodes에 코드 추가 → isIntakeCommand=true.)
        //     이 가드 없이 reserve하면 toCommand=null → 잘못된 turn-reserved 링 경로로 떨어져 액션 유실.
        if (!CommandWireMapper.isIntakeCommand(code)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("error" to "아직 배선되지 않은 즉시 액션입니다.", "code" to code))
        }

        // (4) 인테이크 — sanctioned Model B 경로(typed daemon-command 발행). game-api는 write 안 함.
        val reserved = reserve.reserve(generalId = generalId, actionCode = code, turnIdx = 0, argJson = argJson)
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(IntakeAcceptedResponse(status = "AVAILABLE", requestId = reserved.requestId, code = code))
    }
}
