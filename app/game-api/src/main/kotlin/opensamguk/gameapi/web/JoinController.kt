package opensamguk.gameapi.web

import opensamguk.common.constants.GameConst
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * B1 장수생성(재야→일반/Join) 인테이크 컨트롤러.
 *
 * PHP `hwe/sammo/API/General/Join.php` 의 validateArgs + launch() 중 인테이크/검증 부분을 담당.
 * POST `/api/join` — 로그인 유저가 새 장수를 생성(재야 등록). 즉시 데몬커맨드
 * (`TurnDaemonCommand.MakeGeneral`)를 발행해 엔진 핸들러가 RNG draw + INSERT를 수행.
 *
 * Precheck (PHP Join.php:41–85, 178–256):
 *   - 로그인 필요
 *   - user당 1장수 (general.user_id = userId 이미 존재 시 deny)
 *   - 이름 중복 불가 (general.name = name 이미 존재 시 deny)
 *   - stat 합계 ≤ defaultStatTotal(165), 각 stat ∈ [defaultStatMin(15), defaultStatMax(80)]
 *
 * 응답 규약:
 *   - 성공 → 202 Accepted + {status:"AVAILABLE", requestId}
 *   - deny → 200 OK + {status:"BLOCKED", reason}
 *   - 미인증 → 401 Unauthorized
 */
@RestController
@RequestMapping("/api/join")
class JoinController(
    private val generals: GeneralReadRepository,
    private val reserve: CommandReserveService,
) {

    data class JoinRequest(
        val name: String,
        val leadership: Int,
        val strength: Int,
        val intel: Int,
        val character: String = "Random",
        val pic: Boolean = true,
    )

    data class JoinResponse(
        val status: String,
        val reason: String? = null,
        val requestId: String? = null,
    )

    @PostMapping
    fun join(
        @AuthenticationPrincipal userId: Long?,
        @RequestBody request: JoinRequest,
    ): ResponseEntity<JoinResponse> {
        if (userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(JoinResponse(status = "BLOCKED", reason = "로그인이 필요합니다."))
        }

        // 1. One general per user (Join.php:181)
        if (generals.findByUserId(userId.toString()) != null) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "이미 등록하셨습니다!"),
            )
        }

        // 2. Name non-empty + uniqueness (Join.php:184)
        val trimmedName = request.name.trim()
        if (trimmedName.isEmpty()) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "이름을 입력해 주세요."),
            )
        }
        if (generals.existsByName(trimmedName)) {
            return ResponseEntity.ok(
                JoinResponse(status = "BLOCKED", reason = "이미 존재하는 이름입니다."),
            )
        }

        // 3. Stat range checks (Join.php:196)
        val l = request.leadership
        val s = request.strength
        val i = request.intel
        if (l < GameConst.defaultStatMin || l > GameConst.defaultStatMax ||
            s < GameConst.defaultStatMin || s > GameConst.defaultStatMax ||
            i < GameConst.defaultStatMin || i > GameConst.defaultStatMax
        ) {
            return ResponseEntity.ok(
                JoinResponse(
                    status = "BLOCKED",
                    reason = "능력치는 ${GameConst.defaultStatMin}~${GameConst.defaultStatMax} 사이여야 합니다.",
                ),
            )
        }
        val total = l + s + i
        if (total > GameConst.defaultStatTotal) {
            return ResponseEntity.ok(
                JoinResponse(
                    status = "BLOCKED",
                    reason = "능력치 합계가 ${GameConst.defaultStatTotal}를 초과합니다.",
                ),
            )
        }

        // 4. Publish immediate daemon command (no general_turn ring — Model B intake)
        val command = TurnDaemonCommand.MakeGeneral(
            userId = userId.toInt(),
            name = trimmedName,
            leadership = l,
            strength = s,
            intel = i,
            character = request.character,
        )
        val result = reserve.publishImmediate(command)
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(JoinResponse(status = "AVAILABLE", requestId = result.requestId))
    }
}
