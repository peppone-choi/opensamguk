package opensamguk.gameapi.web

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.read.HwihaDomesticForbidden
import opensamguk.gameapi.read.HwihaDomesticReader
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.HwihaAdmissionDenied
import opensamguk.logic.input.HwihaDomesticInput
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

/**
 * 휘하 내정 입력(배치·방침·공사). 지속 입력이라 명령 목록 12순 슬롯을 쓰지 않고(§5.1) 조정 입력과 같은 즉시 인테이크 봉투로
 * 접수한다(`HwihaCourtController` 와 같은 꼴). 경로는 `GameApiSecurityConfig` 에서 permitAll 이고 여기서 가른다:
 * principal 없음·범위 밖 401, 남의 장수 403, 사전검사 거절은 200 + `BLOCKED`, 접수는 202 + `AVAILABLE`(성공이 아니다 —
 * 결과는 `GET /api/command/result/{requestId}` 로 읽는다).
 */
@RestController
class HwihaDomesticController(private val reserve: CommandReserveService, private val reader: HwihaDomesticReader) {
    @PostMapping("/api/commands/placement/{name}")
    fun placement(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int, @PathVariable name: String,
        @RequestBody raw: String): ResponseEntity<Any> = submit(userId, generalId, "placement.$name", raw)

    @PostMapping("/api/commands/policy/{name}")
    fun policy(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int, @PathVariable name: String,
        @RequestBody raw: String): ResponseEntity<Any> = submit(userId, generalId, "policy.$name", raw)

    @PostMapping("/api/commands/work/{name}")
    fun work(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int, @PathVariable name: String,
        @RequestBody raw: String): ResponseEntity<Any> = submit(userId, generalId, "work.$name", raw)

    private fun submit(userId: Long?, generalId: Int, inputId: String, raw: String): ResponseEntity<Any> {
        if (!validUser(userId)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        if (inputId !in HwihaDomesticInput.INPUT_IDS) return ResponseEntity.ok(mapOf("status" to "BLOCKED",
            "code" to "UNKNOWN_INPUT", "reason" to "등록되지 않은 내정 입력입니다."))
        return try {
            val accepted = reserve.publishImmediate(TurnDaemonCommand.HwihaCourtInput("", generalId, userId!!.toInt(), inputId, raw),
                userId.toInt())
            ResponseEntity.status(HttpStatus.ACCEPTED).body(mapOf("status" to "AVAILABLE",
                "requestId" to accepted.requestId, "inputId" to inputId))
        } catch (_: HwihaDomesticForbidden) {
            ResponseEntity.status(HttpStatus.FORBIDDEN).build()
        } catch (denied: HwihaAdmissionDenied) {
            ResponseEntity.ok(mapOf("status" to "BLOCKED", "code" to denied.code, "reason" to denied.message))
        }
    }

    @GetMapping("/api/hwiha/posts")
    fun posts(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        guarded(userId) { reader.posts(generalId, it) }

    @GetMapping("/api/hwiha/policies")
    fun policies(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        guarded(userId) { reader.policies(generalId, it) }

    @GetMapping("/api/hwiha/works")
    fun works(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        guarded(userId) { reader.works(generalId, it) }

    private fun validUser(userId: Long?) = userId != null && userId > 0 && userId <= Int.MAX_VALUE.toLong()

    private fun guarded(userId: Long?, read: (Long) -> Any): ResponseEntity<Any> {
        if (!validUser(userId)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        return try { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(read(userId!!)) }
        catch (_: HwihaDomesticForbidden) { ResponseEntity.status(HttpStatus.FORBIDDEN).build() }
    }
}
