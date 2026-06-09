package opensamguk.gateway.controller

import opensamguk.gateway.dto.AdminUserListResponse
import opensamguk.gateway.dto.BanEmailRequest
import opensamguk.gateway.dto.BanEmailResult
import opensamguk.gateway.dto.DeployRequest
import opensamguk.gateway.dto.DeployResult
import opensamguk.gateway.dto.DeployStatus
import opensamguk.gateway.dto.ScrubResult
import opensamguk.gateway.dto.ScenarioListResponse
import opensamguk.gateway.dto.ServiceVersion
import opensamguk.gateway.dto.SystemFlagResponse
import opensamguk.gateway.dto.SystemToggleRequest
import opensamguk.gateway.dto.UserCommandRequest
import opensamguk.gateway.dto.UserCommandResult
import opensamguk.gateway.dto.VersionResponse
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.service.AdminMemberService
import opensamguk.gateway.service.DeployService
import opensamguk.gateway.service.ScenarioCatalogService
import opensamguk.gateway.service.VersionService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 어드민 "서버 제어" API — 서버별 현재 버전 표시 + 스테이트리스 서비스 업데이트 트리거.
 *
 * `admin` 경로 전체는 [opensamguk.gateway.security.SecurityConfig]에서 role=ADMIN으로 게이트된다.
 * 업데이트 트리거의 실제 권한(docker)은 게이트웨이가 아니라 내부망 deployer 사이드카가 쥔다([DeployService]).
 */
@RestController
@RequestMapping("/admin")
class AdminController(
    private val deployService: DeployService,
    private val versionService: VersionService,
    private val adminMemberService: AdminMemberService,
    private val scenarioCatalogService: ScenarioCatalogService,
    buildPropertiesProvider: ObjectProvider<BuildProperties>,
) {
    // buildInfo가 없는 환경(테스트 등)에서는 null — 그때는 gateway 버전 필드가 null로 응답된다.
    private val buildProperties: BuildProperties? = buildPropertiesProvider.ifAvailable

    /** 전 서비스 실행 버전. gateway는 자기 BuildProperties, 서버별 game-api/game-engine은 내부망 fan-out + skew 경고. */
    @GetMapping("/version")
    fun version(): ResponseEntity<VersionResponse> {
        val gateway = ServiceVersion(
            reachable = true,
            version = buildProperties?.version,
            imageTag = buildProperties?.get("image.tag"),
            buildTime = buildProperties?.time?.toString(),
        )
        return ResponseEntity.ok(versionService.collect(gateway))
    }

    /**
     * deployer 상태 — 대상 서버([serverId])의 현재 배포 태그 + 배포 가능한 버전 목록(다운그레이드용 더 낮은
     * 태그 포함). serverId 미지정 시 기본(첫) 서버.
     */
    @GetMapping("/deploy/status")
    fun deployStatus(@RequestParam(required = false) serverId: String?): ResponseEntity<DeployStatus> =
        ResponseEntity.ok(deployService.status(serverId))

    /**
     * 한 게임 서버([DeployRequest.serverId])의 스테이트리스 서비스(game-api/web-game)를 목표 버전으로
     * 재배포한다 — 선택/다운그레이드/특정 버전 기동. game-engine은 deployer 대상에서 제외되어 영향받지
     * 않는다(진행 중 턴 desync 방지).
     */
    @PostMapping("/deploy")
    fun deploy(
        @RequestBody request: DeployRequest,
        @AuthenticationPrincipal principal: CustomUserDetails?,
    ): ResponseEntity<DeployResult> {
        val actor = principal?.username ?: "unknown"
        return ResponseEntity.ok(deployService.deploy(request.serverId, request.tag, actor))
    }

    @PostMapping("/servers", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun createServer(@RequestBody body: String): ResponseEntity<String> =
        deployService.createServer(body).toResponse()

    @DeleteMapping("/servers/{serverId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun deleteServer(@PathVariable serverId: String): ResponseEntity<String> =
        deployService.deleteServer(serverId).toResponse()

    @PostMapping("/servers/{serverId}/reset", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun resetServer(
        @PathVariable serverId: String,
        @RequestBody(required = false) body: String?,
    ): ResponseEntity<String> =
        deployService.resetServer(serverId, body ?: "{}").toResponse()

    @GetMapping("/scenarios")
    fun scenarios(): ResponseEntity<ScenarioListResponse> =
        ResponseEntity.ok(scenarioCatalogService.list())

    @GetMapping("/env/shared", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun sharedEnv(): ResponseEntity<String> =
        deployService.sharedEnv().toResponse()

    @PatchMapping("/env/shared", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun patchSharedEnv(@RequestBody body: String): ResponseEntity<String> =
        deployService.patchSharedEnv(body).toResponse()

    @GetMapping("/env/servers/{serverId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun serverEnv(@PathVariable serverId: String): ResponseEntity<String> =
        deployService.serverEnv(serverId).toResponse()

    @PatchMapping("/env/servers/{serverId}", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun patchServerEnv(
        @PathVariable serverId: String,
        @RequestBody body: String,
    ): ResponseEntity<String> =
        deployService.patchServerEnv(serverId, body).toResponse()

    // ──────────────────────────────────────────────────────────────────────
    // B2 회원관리(루트DB) — legacy j_get_userlist / j_set_userlist / BanEmailAddress
    // 전 경로 ADMIN 게이트(SecurityConfig `/admin/**`). 회원 단일 명령은 self/peer 보호 적용.
    // ──────────────────────────────────────────────────────────────────────

    /** B2a 회원 목록 — legacy j_get_userlist. users + 실행 서버 + 가입/로그인 허용 플래그. */
    @GetMapping("/users")
    fun users(): ResponseEntity<AdminUserListResponse> =
        ResponseEntity.ok(adminMemberService.listUsers())

    /**
     * B2b 시스템 플래그 토글 — legacy j_set_userlist allow_login/allow_join.
     * scope ∈ {allow_login, allow_join}. value=true면 허용, false면 금지.
     */
    @PostMapping("/system/{scope}")
    fun systemToggle(
        @PathVariable scope: String,
        @RequestBody request: SystemToggleRequest,
    ): ResponseEntity<SystemFlagResponse> = when (scope) {
        "allow_login" -> ResponseEntity.ok(adminMemberService.setAllowLogin(request.value))
        "allow_join" -> ResponseEntity.ok(adminMemberService.setAllowJoin(request.value))
        else -> ResponseEntity.badRequest().build()
    }

    /**
     * B2c 계정 정리 — legacy j_set_userlist scrub_deleted/scrub_old_user.
     * scope ∈ {deleted, old}. `icon`(scrub_icon)은 CDN 운용이라 N/A → 미구현(아래 분기 참고).
     */
    @PostMapping("/users/scrub/{scope}")
    fun scrub(@PathVariable scope: String): ResponseEntity<ScrubResult> = when (scope) {
        "deleted" -> ResponseEntity.ok(adminMemberService.scrubDeleted())
        "old" -> ResponseEntity.ok(adminMemberService.scrubOldUsers())
        // legacy scrub_icon: 미사용 아이콘 FS glob 정리. opensamguk은 이미지 CDN이라 FS 정리 대상 없음 → N/A.
        // 의도적으로 미구현(백로그). 호출 시 400.
        else -> ResponseEntity.badRequest().build()
    }

    /**
     * B2d 회원 단일 명령 — legacy j_set_userlist delete/reset_pw/block/unblock/set_userlevel.
     * self/peer 보호([opensamguk.gateway.security.AdminMemberGuard]) 적용: 자기 자신/다른 ADMIN 거부.
     */
    @PostMapping("/users/{id}/{action}")
    fun userCommand(
        @PathVariable id: Long,
        @PathVariable action: String,
        @RequestBody(required = false) request: UserCommandRequest?,
        @AuthenticationPrincipal principal: CustomUserDetails,
    ): ResponseEntity<UserCommandResult> {
        val result = adminMemberService.runUserCommand(
            actorId = principal.id,
            actorRole = actorRole(principal),
            targetId = id,
            action = action,
            param = request?.param,
        )
        return ResponseEntity.ok(result)
    }

    /** B2e 이메일 영구차단 — legacy BanEmailAddress. sha512(salt|email|salt)로 banned_member 삽입. */
    @PostMapping("/ban-email")
    fun banEmail(@RequestBody request: BanEmailRequest): ResponseEntity<BanEmailResult> =
        ResponseEntity.ok(adminMemberService.banEmail(request.email))

    /** 인증 주체의 role 추출 — ROLE_ADMIN 권한 보유 시 "ADMIN", 아니면 "USER"(방어적). */
    private fun actorRole(principal: CustomUserDetails): String =
        if (principal.authorities.any { it.authority == "ROLE_ADMIN" }) "ADMIN" else "USER"

    private fun opensamguk.gateway.dto.EnvProxyResponse.toResponse(): ResponseEntity<String> =
        ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
}
