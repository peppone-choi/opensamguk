package opensamguk.gameapi.v2

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.controller.InstantActionController.IntakeAcceptedResponse
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.infra.v2.V2SandboxGate
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import opensamguk.logic.v2.command.V2CommandAvailability

/**
 * OPENSAM-153 (v2 R4) — v2 도시병사 보충 인테이크 엔드포인트. **v2 샌드박스 전용, 새 파일**
 * ([opensamguk.gameapi.controller.InstantActionController]의 v1 분류기를 확장하지 않는다).
 *
 * `@Profile`/`@ConditionalOnProperty` 게이트는 [V2SandboxGate]와 동일 조합 — 두 조건이 모두 참일 때만
 * 빈이 등록되므로 v1 프로덕션 컨텍스트에는 존재하지 않는다([opensamguk.gameapi.v2.V2SandboxConfiguration]과
 * 같은 게이트). 소유권 가드·인테이크 패턴은 `InstantActionController`와 동형 — `CommandReserveService.reserve`
 * 로 typed [opensamguk.common.wire.CityGarrisonRecruit]를 command stream에 발행하고(Model B, ring 없음)
 * 202 + requestId를 회신한다. 도메인 규칙(비용·상한)은 [opensamguk.engine.v2.V2GarrisonRecruitHandler]가
 * OPENSAM-153 도메인 파트에서 채운다 — 이 컨트롤러는 배선만 한다.
 */
@RestController
@Profile(V2SandboxGate.PROFILE)
@ConditionalOnProperty(name = [V2SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/v2/garrison-recruit")
class V2GarrisonRecruitController(
    private val reserve: CommandReserveService,
    private val resolver: GeneralResolver,
) {
    /**
     * `POST /api/v2/garrison-recruit?generalId=` — `{cityId, amount}` 본문을 typed
     * [opensamguk.common.wire.CityGarrisonRecruit]로 인테이크한다.
     */
    @PostMapping
    fun recruit(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody(required = false) argJson: String? = null,
    ): ResponseEntity<Any> {
        if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        if (generalId != resolver.resolveGeneralId(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val availability = validateLegacyV2Arguments("v2GarrisonRecruit", argJson)
        if (availability !is V2CommandAvailability.Available) return availability.legacyError("v2GarrisonRecruit")
        val reserved = reserve.reserveForOwner(
            generalId = generalId,
            actionCode = "v2GarrisonRecruit",
            turnIdx = 0,
            argJson = argJson,
            ownerUserId = Math.toIntExact(userId),
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(
                IntakeAcceptedResponse(
                    status = "AVAILABLE",
                    requestId = reserved.requestId,
                    code = "v2GarrisonRecruit",
                ),
            )
    }
}
