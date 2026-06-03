package opensamguk.gateway.controller

import opensamguk.gateway.dto.DeployRequest
import opensamguk.gateway.dto.DeployResult
import opensamguk.gateway.dto.DeployStatus
import opensamguk.gateway.dto.ServiceVersion
import opensamguk.gateway.dto.VersionResponse
import opensamguk.gateway.security.CustomUserDetails
import opensamguk.gateway.service.DeployService
import opensamguk.gateway.service.VersionService
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.info.BuildProperties
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 어드민 "서버 제어" API — 현재 버전 표시 + 스테이트리스 서비스 업데이트 트리거.
 *
 * `admin` 경로 전체는 [opensamguk.gateway.security.SecurityConfig]에서 role=ADMIN으로 게이트된다.
 * 업데이트 트리거의 실제 권한(docker)은 게이트웨이가 아니라 내부망 Watchtower가 쥔다([DeployService]).
 */
@RestController
@RequestMapping("/admin")
class AdminController(
    private val deployService: DeployService,
    private val versionService: VersionService,
    buildPropertiesProvider: ObjectProvider<BuildProperties>,
) {
    // buildInfo가 없는 환경(테스트 등)에서는 null — 그때는 gateway 버전 필드가 null로 응답된다.
    private val buildProperties: BuildProperties? = buildPropertiesProvider.ifAvailable

    /** 전 서비스 실행 버전. gateway는 자기 BuildProperties, game-api/game-engine은 내부망 fan-out + skew 경고. */
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

    /** deployer 상태 — 현재 배포 태그 + 배포 가능한 버전 목록(다운그레이드용 더 낮은 태그 포함). */
    @GetMapping("/deploy/status")
    fun deployStatus(): ResponseEntity<DeployStatus> = ResponseEntity.ok(deployService.status())

    /**
     * 스테이트리스 서비스(게이트웨이/게임 API/웹×2/nginx)를 목표 버전으로 재배포한다 — 선택/다운그레이드/
     * 특정 버전 기동. game-engine은 deployer 대상에서 제외되어 영향받지 않는다(진행 중 턴 desync 방지).
     */
    @PostMapping("/deploy")
    fun deploy(
        @RequestBody request: DeployRequest,
        @AuthenticationPrincipal principal: CustomUserDetails?,
    ): ResponseEntity<DeployResult> {
        val actor = principal?.username ?: "unknown"
        return ResponseEntity.ok(deployService.deploy(request.tag, actor))
    }
}
