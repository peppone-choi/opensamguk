package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** 한 게임 서버의 백엔드 좌표 — 버전 fan-out 대상(game-api/game-engine 내부 URL) + 배포 프로젝트명. */
data class ServerDef(
    val id: String,
    val name: String,
    val gameApiUrl: String,
    val gameEngineUrl: String,
    val deployProject: String,
)

/**
 * 멀티서버 레지스트리 — 어드민 "서버 제어"가 버전을 모으고 배포를 트리거할 게임 서버 목록.
 *
 * 아키텍처: 게임 서버마다 독립 스택(game-engine+game-api+db+redis, 자기 IMAGE_TAG)이고 gateway는 공유다.
 * 그래서 gateway는 "어떤 서버들이 있고 각자의 game-api/game-engine 내부 URL은 무엇인가"를 알아야 fan-out 한다.
 *
 * 설정 소스: env [SERVER_REGISTRY_JSON] (compose가 주입하는 JSON 배열). 비어 있으면(로컬/단일서버)
 * GAME_API_URL/GAME_ENGINE_URL 한 개로 합성한 단일 서버로 폴백한다 — 로컬 개발 무중단.
 */
@Component
class ServerRegistry(
    @Value("\${SERVER_REGISTRY_JSON:}") private val registryJson: String,
    @Value("\${GAME_API_URL:http://game-api:8081}") private val gameApiUrl: String,
    @Value("\${GAME_ENGINE_URL:http://game-engine:8082}") private val gameEngineUrl: String,
    @Value("\${DEPLOY_PROJECT:opensamguk}") private val deployProject: String,
    @Value("\${DEFAULT_SERVER_NAME:통일 서버}") private val defaultServerName: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ServerRegistry::class.java)

    // 부팅 시 1회 파싱 — 삽입 순서 유지(레지스트리 = 데이터 주도, 재키잉 금지).
    private val servers: List<ServerDef> = parse()

    /** 등록된 모든 서버(삽입 순서 유지). */
    fun all(): List<ServerDef> = servers

    /** id로 서버 조회 — 없으면 null. */
    fun find(id: String): ServerDef? = servers.firstOrNull { it.id == id }

    /** serverId 미지정 요청의 기본값(첫 서버 = 단일서버 폴백 시 그 서버). 등록 0개면 null. */
    fun default(): ServerDef? = servers.firstOrNull()

    private fun parse(): List<ServerDef> {
        if (registryJson.isBlank()) return listOf(fallback())
        return try {
            val parsed = objectMapper.readTree(registryJson).mapNotNull { n ->
                val id = n.path("id").asText("")
                if (id.isBlank()) return@mapNotNull null
                ServerDef(
                    id = id,
                    name = n.path("name").asText(id),
                    gameApiUrl = n.path("gameApiUrl").asText(gameApiUrl),
                    gameEngineUrl = n.path("gameEngineUrl").asText(gameEngineUrl),
                    deployProject = n.path("deployProject").asText(deployProject),
                )
            }
            // 파싱은 됐으나 유효 서버 0개면 폴백(빈 어드민 방지).
            parsed.ifEmpty { listOf(fallback()) }
        } catch (e: Exception) {
            log.error("SERVER_REGISTRY_JSON 파싱 실패 — 단일서버 폴백. {}", e.message)
            listOf(fallback())
        }
    }

    private fun fallback() = ServerDef("main", defaultServerName, gameApiUrl, gameEngineUrl, deployProject)
}
