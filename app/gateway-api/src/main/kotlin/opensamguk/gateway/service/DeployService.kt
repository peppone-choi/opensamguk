package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.dto.DeployResult
import opensamguk.gateway.dto.DeployStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/**
 * 버전 선택/다운그레이드 배포의 게이트키퍼 — 내부망 deployer 사이드카의 클라이언트.
 *
 * 보안 경계: gateway-api는 docker / docker.sock에 직접 닿지 않는다. 임의 버전(낮은 버전 포함)으로의
 * 재배포는 권한 격리된 deployer(`DEPLOYER_URL`, docker compose + socket-proxy, 토큰)에 위임한다.
 * deployer가 대상 서버의 compose 프로젝트를 목표 IMAGE_TAG로 재기동(스테이트리스 game-api/web-game만)한다.
 * game-engine은 deployer 대상에서 제외(진행 중 InMemoryTurnWorld desync 방지).
 *
 * 멀티서버: 요청은 serverId로 들어오고, 레지스트리에서 그 서버의 deployProject(compose 프로젝트명)로
 * 변환해 deployer에 넘긴다. deployer 토큰을 아는 곳은 여기 한 곳뿐(UI/브라우저 미노출). 두 env 중
 * 하나라도 비면(로컬/미배포) 비활성: `configured=false`로 사유를 돌려준다.
 */
@Service
class DeployService(
    @Value("\${DEPLOYER_URL:}") private val deployerUrl: String,
    @Value("\${DEPLOYER_TOKEN:}") private val deployerToken: String,
    private val registry: ServerRegistry,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(DeployService::class.java)
    private val rest = RestClient.create()

    private fun configured() = deployerUrl.isNotBlank() && deployerToken.isNotBlank()

    /** deployer 상태: 대상 서버의 현재 IMAGE_TAG + 배포 가능한 태그 목록. serverId 미지정 시 기본 서버. */
    fun status(serverId: String?): DeployStatus {
        val server = resolve(serverId)
            ?: return DeployStatus(false, serverId, null, emptyList(), "알 수 없는 서버입니다: ${serverId ?: "(없음)"}")
        if (!configured()) {
            return DeployStatus(
                configured = false,
                serverId = server.id,
                currentTag = null,
                availableTags = emptyList(),
                message = "배포 deployer가 설정되지 않았습니다 (DEPLOYER_URL/TOKEN 미설정 — 로컬/미배포 환경).",
            )
        }
        return try {
            val raw = rest.get()
                .uri("$deployerUrl/status?project={p}", server.deployProject)
                .header("Authorization", "Bearer $deployerToken")
                .retrieve()
                .body(String::class.java)
            val node = objectMapper.readTree(raw)
            DeployStatus(
                configured = true,
                serverId = server.id,
                currentTag = node.path("currentTag").asText(null),
                availableTags = node.path("availableTags").map { it.asText() },
            )
        } catch (e: Exception) {
            log.warn("deployer status 조회 실패 (server={})", server.id, e)
            DeployStatus(true, server.id, null, emptyList(), "deployer 조회 실패: ${e.message}")
        }
    }

    /** 대상 서버의 스테이트리스 서비스를 목표 [tag]로 재배포(선택/다운그레이드/특정버전). game-engine 제외. */
    fun deploy(serverId: String, tag: String, actor: String): DeployResult {
        val server = resolve(serverId)
            ?: return DeployResult(false, "알 수 없는 서버입니다: $serverId")
        if (!configured()) {
            return DeployResult(false, "배포 deployer가 설정되지 않았습니다 (DEPLOYER_URL/TOKEN 미설정).")
        }
        val safeTag = tag.trim()
        // 태그 화이트리스트(주입 방지): 영숫자 . _ - 만 허용.
        if (safeTag.isEmpty() || !safeTag.matches(Regex("^[A-Za-z0-9._-]+$"))) {
            return DeployResult(false, "올바르지 않은 버전 태그입니다.")
        }
        log.info("Admin '{}' triggered redeploy server='{}' to tag '{}'", actor, server.id, safeTag)
        return try {
            val body = rest.post()
                .uri("$deployerUrl/deploy")
                .header("Authorization", "Bearer $deployerToken")
                .header("Content-Type", "application/json")
                .body(objectMapper.writeValueAsString(mapOf("project" to server.deployProject, "tag" to safeTag)))
                .retrieve()
                .body(String::class.java)
            DeployResult(true, "'${server.name}' 서버를 버전 '$safeTag'(으)로 재배포 트리거했습니다.", body)
        } catch (e: Exception) {
            log.warn("deployer 재배포 트리거 실패 (server={})", server.id, e)
            DeployResult(false, "재배포 트리거 실패: ${e.message}")
        }
    }

    /** serverId 미지정이면 기본 서버, 아니면 레지스트리 조회. */
    private fun resolve(serverId: String?) =
        if (serverId.isNullOrBlank()) registry.default() else registry.find(serverId)
}
