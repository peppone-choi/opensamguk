package opensamguk.gameapi.web

import opensamguk.gameapi.read.CampForbidden
import opensamguk.gameapi.read.CampReader
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 휘하 화면 조회(읽기 전용). 경로는 `GameApiSecurityConfig` 에서 permitAll 로 두고 여기서 가른다:
 * principal 없음·범위 밖이면 401, `?generalId=` 가 본인 장수가 아니면 403, 그 밖의 상태는 200 + `status`.
 */
@RestController
class CampController(private val reader: CampReader) {
    @GetMapping("/api/hwiha/yuedan")
    fun yuedan(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        guarded(userId) { reader.yuedan(generalId, it) }

    @GetMapping("/api/hwiha/warehouses")
    fun warehouses(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        guarded(userId) { reader.warehouses(generalId, it) }

    @GetMapping("/api/hwiha/county/{cityId}")
    fun county(@AuthenticationPrincipal userId: Long?, @PathVariable cityId: Int, @RequestParam generalId: Int): ResponseEntity<Any> =
        guarded(userId) { reader.county(cityId, generalId, it) }

    @GetMapping("/api/hwiha/retinue")
    fun retinue(@AuthenticationPrincipal userId: Long?, @RequestParam generalId: Int): ResponseEntity<Any> =
        guarded(userId) { reader.retinue(generalId, it) }

    private fun guarded(userId: Long?, read: (Long) -> Any?): ResponseEntity<Any> = hwihaGuarded(userId, read)
}

/**
 * 휘하 조회 공통 응답 규칙: principal 없음·범위 밖 401, 남의 장수 403([CampForbidden]), 없는 대상 404,
 * 그 밖은 200 — 모두 `Cache-Control: no-store`(200·404). 부드러운 상태는 본문 `status` 로 알린다.
 */
internal fun hwihaGuarded(userId: Long?, read: (Long) -> Any?): ResponseEntity<Any> {
    if (userId == null || userId <= 0 || userId > Int.MAX_VALUE.toLong())
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
    return try {
        val body = read(userId) ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).cacheControl(CacheControl.noStore()).build()
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
    } catch (_: CampForbidden) {
        ResponseEntity.status(HttpStatus.FORBIDDEN).build()
    }
}
