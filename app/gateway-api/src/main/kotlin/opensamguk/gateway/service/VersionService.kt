package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gateway.dto.ServerVersion
import opensamguk.gateway.dto.ServiceVersion
import opensamguk.gateway.dto.VersionResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient

/**
 * 전 서비스 실행 버전 수집기 — gateway 자신(BuildProperties) + 레지스트리의 각 게임 서버
 * game-api/game-engine `/actuator/info` fan-out.
 *
 * "엔진이 이 버전인지 어떻게 아는가"에 답한다: game-engine은 deployer 자동 재배포 대상에서 제외되므로,
 * 스테이트리스만 갱신하면 엔진 버전이 뒤처질 수 있다. 그래서 엔진의 실제 버전을 내부망으로 직접 읽어
 * 표시하고, gateway와 다르면 skew로 경고한다. 내부망 조회 실패 시 해당 서비스는 reachable=false.
 */
@Service
class VersionService(
    private val deployService: DeployService,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(VersionService::class.java)
    private val rest = RestClient.create()

    /** gateway 자신의 버전을 받아 레지스트리의 모든 서버 버전을 합쳐 반환한다. */
    fun collect(gateway: ServiceVersion): VersionResponse {
        val servers = deployService.registeredServers().map { def ->
            val gameApi = fetch(def.gameApiUrl)
            val gameEngine = fetch(def.gameEngineUrl)
            val skew = listOf(gameApi, gameEngine).any { it.reachable && it.version != gateway.version }
            ServerVersion(
                id = def.id,
                name = def.name,
                generation = def.generation,
                gameApi = gameApi,
                gameEngine = gameEngine,
                skew = skew,
            )
        }
        return VersionResponse(gateway = gateway, servers = servers, skew = servers.any { it.skew })
    }

    /** 한 서비스의 `/actuator/info`를 읽어 build.version / build.image.tag / build.time을 추출. */
    private fun fetch(baseUrl: String): ServiceVersion {
        return try {
            val raw = rest.get().uri("$baseUrl/actuator/info").retrieve().body(String::class.java)
            val build = objectMapper.readTree(raw).path("build")
            ServiceVersion(
                reachable = true,
                version = build.path("version").asText(null),
                imageTag = build.path("image").path("tag").asText(null),
                buildTime = build.path("time").asText(null),
            )
        } catch (e: Exception) {
            log.debug("버전 조회 실패 {} — {}", baseUrl, e.message)
            ServiceVersion(reachable = false, version = null, imageTag = null, buildTime = null)
        }
    }
}
