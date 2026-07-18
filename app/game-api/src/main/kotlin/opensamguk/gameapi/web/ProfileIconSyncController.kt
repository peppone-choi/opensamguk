package opensamguk.gameapi.web

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.reserve.CommandReserveService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * OPENSAM-94 — 프로필 아이콘 typed sync 인테이크 컨트롤러 (server-to-server, gateway → game-api).
 *
 * gateway-api가 OPENSAM-91 DB commit 후 각 게임 서버로 fan-out하는 sync를 받아 shape 검증 후
 * `TurnDaemonCommand.ProfileIconSync`를 즉시 발행한다(Join과 동일한 `publishImmediate` Model-B 경로).
 * eligibility 재평가·owner/npc predicate·flush는 game-engine 핸들러 소관 — 이 컨트롤러는 발행만 한다.
 *
 * 인증: 내부망 전용 엔드포인트(nginx 미프록시). [syncToken]이 설정되면 `X-Profile-Sync-Token` 헤더
 * 일치를 요구하고, 미설정(기본)이면 내부망 신뢰로 통과한다(Versionㆍactuator fan-out과 동일 신뢰 모델).
 * PII를 로그/응답에 남기지 않는다(criterion 12).
 */
@RestController
@RequestMapping("/api/internal/profile-icon-sync")
class ProfileIconSyncController(
    private val reserve: CommandReserveService,
    @Value("\${PROFILE_SYNC_TOKEN:}") private val syncToken: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class ProfileIconSyncRequest(
        val userId: Long = 0,
        val picture: String = "",
        val imgsvr: Int = 0,
        val grade: Int = 0,
    )

    data class ProfileIconSyncResponse(val status: String, val requestId: String? = null)

    @PostMapping
    fun sync(
        @RequestHeader(name = "X-Profile-Sync-Token", required = false) token: String?,
        @RequestBody request: ProfileIconSyncRequest,
    ): ResponseEntity<ProfileIconSyncResponse> {
        if (syncToken.isNotBlank() && token != syncToken) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(ProfileIconSyncResponse(status = "UNAUTHORIZED"))
        }
        if (!validShape(request)) {
            log.debug("profile-icon.sync intake rejected: invalid shape")
            return ResponseEntity.badRequest().body(ProfileIconSyncResponse(status = "INVALID"))
        }
        val result = reserve.publishImmediate(
            TurnDaemonCommand.ProfileIconSync(
                userId = request.userId,
                picture = request.picture,
                imgsvr = request.imgsvr,
                grade = request.grade,
            ),
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(ProfileIconSyncResponse(status = "ACCEPTED", requestId = result.requestId))
    }

    /** 신뢰 경계 shape 검증 — canonical managed 파일명만, traversal/경로 구분자 거부. */
    private fun validShape(r: ProfileIconSyncRequest): Boolean {
        if (r.userId <= 0) return false
        if (r.imgsvr != 0 && r.imgsvr != 1) return false
        if (r.grade < 0) return false
        val pic = r.picture
        if (pic.isBlank() || pic.length > MAX_PICTURE_LEN) return false
        if (pic.contains('/') || pic.contains('\\') || pic.contains("..")) return false
        return true
    }

    private companion object {
        const val MAX_PICTURE_LEN = 128
    }
}
