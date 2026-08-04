package opensamguk.gateway.service

import com.fasterxml.jackson.databind.JsonNode
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
    val generation: Int? = null,
    val scenarioCode: String? = null,
)

/**
 * 멀티서버 레지스트리 — 어드민 "서버 제어"가 버전을 모으고 배포를 트리거할 게임 서버 목록.
 *
 * 레지스트리는 모든 gateway consumer의 단일 검증 소유자다. 하나라도 public-id, route reservation,
 * canonical coordinate, 또는 duplicate contract를 어기면 전체 collection을 비워 fail-closed 한다.
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

    private val servers: List<ServerDef> = parse()

    /** 등록된 모든 검증 완료 서버(삽입 순서 유지). */
    fun all(): List<ServerDef> = servers

    /** id로 서버 조회 — 없으면 null. */
    fun find(id: String): ServerDef? = servers.firstOrNull { it.id == id }

    /** serverId 미지정 요청의 기본값(첫 서버). 등록 0개면 null. */
    fun default(): ServerDef? = servers.firstOrNull()

    private fun parse(): List<ServerDef> {
        if (registryJson.isBlank()) return emptyList()
        return try {
            parseCollection(objectMapper.readTree(registryJson)) ?: invalidRegistry()
        } catch (e: Exception) {
            log.error("SERVER_REGISTRY_JSON 파싱 실패 — 서버 목록을 비웁니다. {}", e.message)
            emptyList()
        }
    }

    private fun parseCollection(root: JsonNode): List<ServerDef>? =
        if (root.isArray) parseArray(root) else null

    private fun parseArray(root: JsonNode): List<ServerDef>? {
        val parsed = ArrayList<ServerDef>(root.size())
        val seenIds = HashSet<String>(root.size())
        for (node in root) {
            if (!node.isObject) return null
            val id = text(node, "id") ?: return null
            if (!isPublicServerId(id) || !seenIds.add(id)) return null
            parsed += parseObjectEntry(id, node) ?: return null
        }
        return parsed
    }

    private fun parseObjectEntry(id: String, node: JsonNode): ServerDef? {
        if (!hasExpectedCoordinate(node, defaultGameApiUrl(id), "gameApiUrl") ||
            !hasExpectedCoordinate(node, defaultGameEngineUrl(id), "gameEngineUrl") ||
            !hasExpectedCoordinate(node, defaultDeployProject(id), "deployProject", "project")
        ) {
            return null
        }
        val name = when {
            !node.has("name") -> id
            !node.path("name").isTextual -> return null
            else -> node.path("name").asText().trim().ifBlank { id }
        }
        val generation = intOrNull(node.path("generation"))
        if (node.has("generation") && generation == null) return null
        val scenarioCode = textOrNull(node, "scenarioCode", "scenario")
        return defaultServer(id).copy(name = name, generation = generation, scenarioCode = scenarioCode)
    }

    private fun invalidRegistry(): List<ServerDef> {
        log.error("SERVER_REGISTRY_JSON가 canonical server contract를 위반해 전체 서버 목록을 비웁니다.")
        return emptyList()
    }

    private fun isPublicServerId(id: String): Boolean =
        PUBLIC_SERVER_ID.matches(id) && id !in RESERVED_SERVER_IDS

    private fun defaultServer(id: String): ServerDef =
        ServerDef(
            id = id,
            name = id,
            gameApiUrl = defaultGameApiUrl(id),
            gameEngineUrl = defaultGameEngineUrl(id),
            deployProject = defaultDeployProject(id),
        )

    private fun hasExpectedCoordinate(node: JsonNode, expected: String, vararg fields: String): Boolean =
        fields.all { field ->
            !node.has(field) || (node.path(field).isTextual && node.path(field).asText() == expected)
        }

    private fun defaultGameApiUrl(id: String): String = "http://s$id-game-api:8081"

    private fun defaultGameEngineUrl(id: String): String = "http://s$id-game-engine:8082"

    private fun defaultDeployProject(id: String): String = "opensamguk-s$id"

    private fun intOrNull(node: JsonNode): Int? =
        when {
            node.isInt || node.isLong -> node.asInt()
            node.isTextual -> node.asText().toIntOrNull()
            else -> null
        }

    private fun text(node: JsonNode, field: String): String? =
        node.path(field).takeIf { it.isTextual }?.asText()

    private fun textOrNull(node: JsonNode, vararg fields: String): String? =
        fields.asSequence()
            .mapNotNull { field ->
                if (!node.has(field)) {
                    null
                } else {
                    node.path(field).takeIf { it.isTextual }?.asText()
                }
            }
            .firstOrNull { it.isNotBlank() }

    private companion object {
        val PUBLIC_SERVER_ID = Regex("^[a-z0-9]{1,48}$")

        val RESERVED_SERVER_IDS = setOf(
            "all",
            "main",
            "admin1",
            "admin2",
            "admin5",
            "admin7",
            "admin8",
            "auction",
            "battle-center",
            "betting",
            "board",
            "chief-center",
            "city",
            "coming-soon",
            "diplomacy",
            "generals",
            "global-diplomacy",
            "history",
            "inherit",
            "join",
            "mailbox",
            "map",
            "my",
            "my-boss",
            "my-cities",
            "my-generals",
            "my-nation",
            "nation",
            "nation-betting",
            "nation-finance",
            "npc-control",
            "rankings",
            "register",
            "select-pool",
            "simulator",
            "tournament",
            "tournament-admin",
            "troop",
            "vote",
            "world-log",
        )
    }
}
