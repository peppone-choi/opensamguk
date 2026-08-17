package opensamguk.gameapi.v2

import opensamguk.gameapi.owner.GeneralResolver
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

/**
 * OPENSAM-154 (v2 R5) — v2 도시 자원 수송 인테이크 엔드포인트. **v2 샌드박스 전용, 새 파일**
 * (v1 인테이크 분류기를 확장하지 않는다). 게이트·소유권 가드·202 회신 규약은
 * [V2GarrisonRecruitController](R4)와 동형이다.
 */
@RestController
@Profile(V2SandboxGate.PROFILE)
@ConditionalOnProperty(name = [V2SandboxGate.PROPERTY], havingValue = "true", matchIfMissing = false)
@RequestMapping("/api/v2/city-transport")
class V2CityTransportController(
    private val reserve: CommandReserveService,
    private val resolver: GeneralResolver,
) {
    data class IntakeAcceptedResponse(val status: String, val requestId: String, val code: String)

    /** `POST /api/v2/city-transport?generalId=` — `{fromCityId, toCityId, gold, rice, garrison}` 본문. */
    @PostMapping
    fun transport(
        @AuthenticationPrincipal userId: Long?,
        @RequestParam generalId: Int,
        @RequestBody(required = false) argJson: String? = null,
    ): ResponseEntity<Any> {
        if (userId != null && generalId != resolver.resolveGeneralId(userId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        }
        val reserved = reserve.reserve(
            generalId = generalId,
            actionCode = "v2CityTransport",
            turnIdx = 0,
            argJson = argJson,
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(IntakeAcceptedResponse(status = "AVAILABLE", requestId = reserved.requestId, code = "v2CityTransport"))
    }
}
