package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import opensamguk.gateway.dto.DeployResult
import opensamguk.gateway.dto.DeployStatus
import opensamguk.gateway.dto.EnvProxyResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException

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
    private val envKeyRegex = Regex("^[A-Z0-9_]+$")
    private val sharedEnvKeys = setOf(
        "IMAGE_TAG",
        "NGINX_HTTP_PORT",
        "NGINX_HTTPS_PORT",
        "NEXT_PUBLIC_GATEWAY_URL",
        "NEXT_PUBLIC_IMAGE_CDN",
        "COOKIE_SECURE",
        "JWT_SECRET",
        "ADMIN_PASSWORD",
        "GHCR_TOKEN",
    )
    private val serverEnvKeys = setOf(
        "IMAGE_TAG",
        "GAME_API_PORT",
        "WEB_GAME_PORT",
        "WEB_GAME_TAG",
        "TURN_PROFILE_NAME",
        "SCENARIO_SEED_ENABLED",
        "SCENARIO_CODE",
        "SERVER_NAME",
        "SERVER_GENERATION",
        "GAME_API_URL",
        "GATEWAY_API_URL",
        "JWT_SECRET",
        "RESET_TURNTERM",
        "RESET_SYNC",
        "RESET_FICTION",
        "RESET_EXTEND",
        "RESET_BLOCK_GENERAL_CREATE",
        "RESET_NPCMODE",
        "RESET_SHOW_IMG_LEVEL",
        "RESET_AUTORUN_USER_OPTIONS",
        "RESET_AUTORUN_USER_MINUTES",
        "RESET_JOIN_MODE",
        "RESET_TOURNAMENT_TRIG",
        "RESET_RESERVE_OPEN",
        "RESET_PRE_RESERVE_OPEN",
    )
    private val serverIdRegex = Regex("^[A-Za-z0-9]+$")
    private val maxPublicServerIdLength = 48
    private val portRegex = Regex("^[0-9]{1,5}$")
    private val reservedPublicServerIds = setOf(
        "all",
    )
    private val reservedGameRouteIds = setOf(
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
        "main",
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

    private fun configured() = deployerUrl.isNotBlank() && deployerToken.isNotBlank()
    private fun deployerBase() = deployerUrl.trimEnd('/')

    fun registeredServers(): List<ServerDef> = canonicalServerDefs(registry.all()).orEmpty()

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
                .uri("${deployerBase()}/status?project={p}", server.deployProject)
                .header("Authorization", "Bearer $deployerToken")
                .retrieve()
                .body(String::class.java)
            val node = objectMapper.readTree(raw)
            val currentTag = node.path("currentTag").asText(null)
            val availableTags = node.path("availableTags").map { it.asText() }
            val latestTag = availableTags.firstOrNull()
            DeployStatus(
                configured = true,
                serverId = server.id,
                currentTag = currentTag,
                availableTags = availableTags,
                latestTag = latestTag,
                promotionAvailable = latestTag != null && currentTag != latestTag,
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
                .uri("${deployerBase()}/deploy")
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

    fun sharedEnv(): EnvProxyResponse =
        proxyEnvGet(scope = "shared", path = "/env/shared")

    fun patchSharedEnv(body: String): EnvProxyResponse =
        validateEnvPatch(body, sharedEnvKeys) ?: proxyEnvPatch(path = "/env/shared", body = body)

    fun serverEnv(serverId: String): EnvProxyResponse {
        val server = resolve(serverId)
            ?: return json(400, """{"configured":false,"message":"알 수 없는 서버입니다: $serverId"}""")
        return proxyEnvGet(scope = "server", serverId = server.id, path = "/env/server?id={id}")
    }

    fun patchServerEnv(serverId: String, body: String): EnvProxyResponse {
        val server = resolve(serverId)
            ?: return json(400, """{"ok":false,"message":"알 수 없는 서버입니다: $serverId"}""")
        return validateEnvPatch(body, serverEnvKeys)
            ?: proxyEnvPatch(path = "/env/server?id={id}", serverId = server.id, body = body)
    }

    fun createServer(body: String): EnvProxyResponse {
        validateCreateServer(body)?.let { return it }
        val server = serverDefFromCreateRequest(body)
        val response = proxyCreateServer(body)
        if (!isConfirmedServerSuccess(response, server.id)) return response
        return persistRegistryChange("create", server.id, response) {
            registry.register(server)
        }
    }

    fun deleteServer(serverId: String): EnvProxyResponse {
        val server = resolve(serverId)
            ?: return json(400, """{"ok":false,"message":"알 수 없는 서버입니다: $serverId"}""")
        val response = proxyServerAction(
            method = "POST",
            path = "/servers/close",
            serverId = server.id,
            body = objectMapper.writeValueAsString(mapOf("id" to server.id)),
        )
        if (!isConfirmedServerSuccess(response, server.id)) return response
        return persistRegistryChange("close", server.id, response) {
            registry.unregister(server.id)
        }
    }

    fun resetServer(serverId: String, body: String): EnvProxyResponse {
        val server = resolve(serverId)
            ?: return json(400, """{"ok":false,"message":"알 수 없는 서버입니다: $serverId"}""")
        return validateResetServer(body, server.id)
            ?: proxyServerAction(method = "POST", path = "/servers/reset", serverId = server.id, body = withServerId(body, server.id))
    }

    // ──────────────────────────────────────────────────────────────────────
    // 턴 데몬 제어 프록시 — 대상 서버의 game-engine 내부 URL로 직접 hop.
    //
    // env/* 와 달리 데몬 상태/동결/해제는 deployer가 아니라 game-engine `StatusController`
    // (`@RequestMapping("/admin/turn-daemon")`)가 보유한다. 그래서 여기서는 VersionService가
    // `gameEngineUrl/actuator/info`를 읽는 것과 동일한 패턴으로, serverId로 레지스트리에서 서버를
    // 찾아(미지정 시 기본 서버) 그 서버의 game-engine 내부 URL로 RestClient 호출한다.
    //
    // game-engine은 내부망 전용(Spring Security 미적용)이라 `/admin/turn-daemon/*`에 인증이 없다.
    // 따라서 토큰 없이 그대로 forward한다(actuator/info fan-out과 동일). 게이트웨이단 ADMIN 게이트는
    // SecurityConfig `/admin/**` hasRole("ADMIN")에서 이미 강제된다.
    // ──────────────────────────────────────────────────────────────────────

    /** 턴 데몬 상태 조회 — 대상 서버 game-engine `GET /admin/turn-daemon/status`로 forward. */
    fun turnDaemonStatus(serverId: String?): EnvProxyResponse =
        proxyEngine(method = "GET", serverId = serverId, path = "/admin/turn-daemon/status")

    /** 턴 데몬 락걸기(동결) — 대상 서버 game-engine `POST /admin/turn-daemon/pause`로 forward. */
    fun turnDaemonPause(serverId: String?): EnvProxyResponse =
        proxyEngine(method = "POST", serverId = serverId, path = "/admin/turn-daemon/pause")

    /** 턴 데몬 락풀기(해제) — 대상 서버 game-engine `POST /admin/turn-daemon/resume`로 forward. */
    fun turnDaemonResume(serverId: String?): EnvProxyResponse =
        proxyEngine(method = "POST", serverId = serverId, path = "/admin/turn-daemon/resume")

    /**
     * 대상 서버의 game-engine 내부 URL로 raw forward. serverId 미지정 시 기본(첫) 서버.
     * 인증 헤더 없음(game-engine 내부망 전용). 응답 JSON은 EnvProxyResponse로 그대로 통과.
     */
    private fun proxyEngine(method: String, serverId: String?, path: String): EnvProxyResponse {
        val server = resolve(serverId)
            ?: return json(400, """{"ok":false,"message":"알 수 없는 서버입니다: ${serverId ?: "(없음)"}"}""")
        return try {
            val uri = "${server.gameEngineUrl.trimEnd('/')}$path"
            val raw = if (method == "GET") {
                rest.get().uri(uri).retrieve().body(String::class.java)
            } else {
                rest.post().uri(uri).retrieve().body(String::class.java)
            }
            json(200, raw ?: "{}")
        } catch (e: RestClientResponseException) {
            val responseBody = e.responseBodyAsString.takeIf { it.isNotBlank() }
                ?: """{"ok":false,"message":"턴 데몬 제어 요청 실패"}"""
            json(e.statusCode.value(), responseBody)
        } catch (e: Exception) {
            log.warn("턴 데몬 제어 요청 실패 (server={})", server.id, e)
            json(500, objectMapper.writeValueAsString(mapOf("ok" to false, "message" to "턴 데몬 제어 요청 실패: ${e.message}")))
        }
    }

    /** serverId 미지정이면 기본 서버, 아니면 레지스트리 조회. */
    private fun resolve(serverId: String?): ServerDef? {
        val servers = registeredServers()
        if (serverId.isNullOrBlank()) return servers.firstOrNull()
        val canonicalId = canonicalServerId(serverId) ?: return null
        return servers.firstOrNull { it.id == canonicalId }
    }

    private fun proxyEnvGet(scope: String, serverId: String? = null, path: String): EnvProxyResponse {
        if (!configured()) {
            val idField = serverId?.let { ""","serverId":${objectMapper.writeValueAsString(it)}""" } ?: ""
            return json(
                200,
                """{"ok":false,"configured":false,"scope":"$scope"$idField,"fields":{},"message":"배포 deployer가 설정되지 않았습니다 (DEPLOYER_URL/TOKEN 미설정)."}""",
            )
        }
        return proxyEnv("GET", path, serverId, null)
    }

    private fun proxyEnvPatch(path: String, serverId: String? = null, body: String): EnvProxyResponse {
        if (!configured()) {
            return json(200, """{"ok":false,"message":"배포 deployer가 설정되지 않았습니다 (DEPLOYER_URL/TOKEN 미설정)."}""")
        }
        return proxyEnv("PATCH", path, serverId, body)
    }

    private fun proxyCreateServer(body: String): EnvProxyResponse {
        if (!configured()) {
            return json(200, """{"ok":false,"message":"배포 deployer가 설정되지 않았습니다 (DEPLOYER_URL/TOKEN 미설정)."}""")
        }
        return try {
            val raw = rest.post()
                .uri("${deployerBase()}/servers/create")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer $deployerToken")
                .body(createServerBodyForDeployer(body))
                .retrieve()
                .body(String::class.java)
            json(200, raw ?: "{}")
        } catch (e: RestClientResponseException) {
            val responseBody = e.responseBodyAsString.takeIf { it.isNotBlank() }
                ?: """{"ok":false,"message":"deployer 서버 생성 요청 실패"}"""
            json(e.statusCode.value(), responseBody)
        } catch (e: Exception) {
            log.warn("deployer 서버 생성 요청 실패", e)
            json(500, objectMapper.writeValueAsString(mapOf("ok" to false, "message" to "deployer 서버 생성 요청 실패: ${e.message}")))
        }
    }

    private fun proxyServerAction(method: String, path: String, serverId: String, confirm: String? = null, body: String?): EnvProxyResponse {
        if (!configured()) {
            return json(200, """{"ok":false,"message":"배포 deployer가 설정되지 않았습니다 (DEPLOYER_URL/TOKEN 미설정)."}""")
        }
        return try {
            val uri = "${deployerBase()}$path"
            val raw = when (method) {
                "DELETE" -> rest.delete()
                    .uri(uri, serverId, confirm ?: "")
                    .header("Authorization", "Bearer $deployerToken")
                    .retrieve()
                    .body(String::class.java)
                else -> rest.post()
                    .uri(uri, serverId)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $deployerToken")
                    .body(body ?: "{}")
                    .retrieve()
                    .body(String::class.java)
            }
            json(200, raw ?: "{}")
        } catch (e: RestClientResponseException) {
            val responseBody = e.responseBodyAsString.takeIf { it.isNotBlank() }
                ?: """{"ok":false,"message":"deployer 서버 제어 요청 실패"}"""
            json(e.statusCode.value(), responseBody)
        } catch (e: Exception) {
            log.warn("deployer 서버 제어 요청 실패", e)
            json(500, objectMapper.writeValueAsString(mapOf("ok" to false, "message" to "deployer 서버 제어 요청 실패: ${e.message}")))
        }
    }

    private fun proxyEnv(method: String, path: String, serverId: String?, body: String?): EnvProxyResponse =
        try {
            val raw = if (method == "GET") {
                rest.get()
                    .uri("${deployerBase()}$path", serverId)
                    .header("Authorization", "Bearer $deployerToken")
                    .retrieve()
                    .body(String::class.java)
            } else {
                rest.patch()
                    .uri("${deployerBase()}$path", serverId)
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer $deployerToken")
                    .body(body ?: "{}")
                    .retrieve()
                    .body(String::class.java)
            }
            json(200, raw ?: "{}")
        } catch (e: RestClientResponseException) {
            val responseBody = e.responseBodyAsString.takeIf { it.isNotBlank() }
                ?: """{"ok":false,"message":"deployer env 요청 실패"}"""
            json(e.statusCode.value(), responseBody)
        } catch (e: Exception) {
            log.warn("deployer env 요청 실패", e)
            json(500, objectMapper.writeValueAsString(mapOf("ok" to false, "message" to "deployer env 요청 실패: ${e.message}")))
        }

    private fun validateEnvPatch(body: String, allowedKeys: Set<String>): EnvProxyResponse? =
        try {
            val values = objectMapper.readTree(body).path("values")
            if (!values.isObject || values.size() == 0) {
                json(400, """{"ok":false,"message":"values 객체가 필요합니다."}""")
            } else {
                val invalid = values.fields().asSequence().firstOrNull { (key, value) ->
                    !envKeyRegex.matches(key) ||
                        key !in allowedKeys ||
                        !value.isTextual ||
                        value.asText().contains('\n') ||
                        value.asText().contains('\r')
                }
                invalid?.let { json(400, """{"ok":false,"message":"허용되지 않은 env 값: ${it.key}"}""") }
            }
        } catch (e: Exception) {
            json(400, """{"ok":false,"message":"환경변수 변경 요청 JSON이 올바르지 않습니다."}""")
        }

    private fun validateCreateServer(body: String): EnvProxyResponse? =
        try {
            val node = objectMapper.readTree(body)
            val id = node.path("id").asText("")
            val name = node.path("name").asText("")
            val generation = node.path("generation").asText("")
            val gameApiPort = node.path("gameApiPort").asText("")
            val webGamePort = node.path("webGamePort").asText("")
            val imageTag = node.path("imageTag").asText("")
            val scenarioCode = node.path("scenarioCode").asText("")
            val jwtSecret = node.path("jwtSecret").asText("")
            val canonicalId = id.lowercase()
            when {
                id.isBlank() || !serverIdRegex.matches(id) ->
                    json(400, """{"ok":false,"message":"서버 id가 올바르지 않습니다."}""")
                id.length > maxPublicServerIdLength ->
                    json(400, """{"ok":false,"message":"서버 id는 최대 48자여야 합니다."}""")
                canonicalId in reservedGameRouteIds ->
                    json(400, """{"ok":false,"message":"서버 id ${canonicalId}은 게임 경로와 충돌해 사용할 수 없습니다."}""")
                canonicalId in reservedPublicServerIds ->
                    json(400, """{"ok":false,"message":"서버 id ${canonicalId}은 예약되어 사용할 수 없습니다."}""")
                name.isBlank() || name.contains('\n') || name.contains('\r') ->
                    json(400, """{"ok":false,"message":"서버 이름이 올바르지 않습니다."}""")
                generation.isNotBlank() && !validGeneration(generation) ->
                    json(400, """{"ok":false,"message":"기수는 0 이상의 숫자여야 합니다."}""")
                !validPort(gameApiPort) || !validPort(webGamePort) ->
                    json(400, """{"ok":false,"message":"포트는 1-65535 숫자여야 합니다."}""")
                imageTag.isNotBlank() && !imageTag.matches(Regex("^[A-Za-z0-9._-]+$")) ->
                    json(400, """{"ok":false,"message":"이미지 태그가 올바르지 않습니다."}""")
                scenarioCode.isNotBlank() && !scenarioCode.matches(Regex("^[A-Za-z0-9_.:-]+$")) ->
                    json(400, """{"ok":false,"message":"시나리오 코드가 올바르지 않습니다."}""")
                jwtSecret.contains('\n') || jwtSecret.contains('\r') ->
                    json(400, """{"ok":false,"message":"JWT_SECRET이 올바르지 않습니다."}""")
                else -> null
            }
        } catch (e: Exception) {
            json(400, """{"ok":false,"message":"서버 생성 요청 JSON이 올바르지 않습니다."}""")
        }

    private fun validateResetServer(body: String, serverId: String): EnvProxyResponse? {
        if (body.isBlank()) {
            return json(400, """{"ok":false,"message":"서버 리셋 요청 JSON이 필요합니다."}""")
        }
        return try {
            val node = objectMapper.readTree(body)
            val scenarioCode = node.path("scenarioCode").asText("")
            val expectedKeys = setOf(
                "confirm",
                "generation",
                "scenarioCode",
                "scenarioSeedEnabled",
                "turnTerm",
                "sync",
                "fiction",
                "extend",
                "blockGeneralCreate",
                "npcMode",
                "showImgLevel",
                "autorunUserOptions",
                "autorunUserMinutes",
                "joinMode",
                "tournamentTrig",
                "reserveOpen",
                "preReserveOpen",
            )
            val unknown = node.fieldNames().asSequence().firstOrNull { it !in expectedKeys }
            val validAutorunOptions = setOf("develop", "warp", "recruit", "recruit_high", "train", "battle", "chief")
            val validAutorunMinutes = setOf("0", "43200", "10", "20", "30", "60", "120", "180", "240", "360", "480", "600", "720", "1440", "2160", "2880", "3600", "4320")
            fun textIn(key: String, allowed: Set<String>) =
                !node.has(key) || node.path(key).isTextual && node.path(key).asText() in allowed
            fun cleanText(key: String) =
                !node.has(key) || node.path(key).isTextual && !node.path(key).asText().contains('\n') && !node.path(key).asText().contains('\r')
            fun reservation(key: String) =
                cleanText(key) && (node.path(key).asText("").isBlank() || node.path(key).asText("").matches(Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}$""")))
            when {
                !node.isObject ->
                    json(400, """{"ok":false,"message":"서버 리셋 요청 JSON이 올바르지 않습니다."}""")
                unknown != null ->
                    json(400, """{"ok":false,"message":"허용되지 않은 리셋 값: $unknown"}""")
                node.path("confirm").asText("") != "RESET $serverId" ->
                    json(400, """{"ok":false,"message":"리셋 확인 문구가 일치하지 않습니다."}""")
                node.has("generation") && !validGeneration(node.path("generation").asText("")) ->
                    json(400, """{"ok":false,"message":"기수는 0 이상의 숫자여야 합니다."}""")
                scenarioCode.isNotBlank() && !scenarioCode.matches(Regex("^[A-Za-z0-9_.:-]+$")) ->
                    json(400, """{"ok":false,"message":"시나리오 코드가 올바르지 않습니다."}""")
                node.has("scenarioSeedEnabled") && !node.path("scenarioSeedEnabled").isBoolean ->
                    json(400, """{"ok":false,"message":"시나리오 자동 시드 값이 올바르지 않습니다."}""")
                !textIn("turnTerm", setOf("120", "60", "30", "20", "10", "5", "2", "1")) ->
                    json(400, """{"ok":false,"message":"턴 시간이 올바르지 않습니다."}""")
                !textIn("sync", setOf("0", "1")) || !textIn("fiction", setOf("0", "1")) || !textIn("extend", setOf("0", "1")) ->
                    json(400, """{"ok":false,"message":"Y/N 리셋 값이 올바르지 않습니다."}""")
                !textIn("blockGeneralCreate", setOf("0", "1", "2")) || !textIn("npcMode", setOf("0", "1", "2")) ->
                    json(400, """{"ok":false,"message":"NPC/장수 생성 값이 올바르지 않습니다."}""")
                !textIn("showImgLevel", setOf("0", "1", "2", "3")) ->
                    json(400, """{"ok":false,"message":"이미지 표기 값이 올바르지 않습니다."}""")
                node.has("autorunUserOptions") &&
                    (!node.path("autorunUserOptions").isArray || node.path("autorunUserOptions").any { !it.isTextual || it.asText() !in validAutorunOptions }) ->
                    json(400, """{"ok":false,"message":"휴식 턴 장수 턴 값이 올바르지 않습니다."}""")
                !textIn("autorunUserMinutes", validAutorunMinutes) ->
                    json(400, """{"ok":false,"message":"자동 행동 유효 시간이 올바르지 않습니다."}""")
                !textIn("joinMode", setOf("onlyRandom", "full")) ->
                    json(400, """{"ok":false,"message":"임관 모드가 올바르지 않습니다."}""")
                !textIn("tournamentTrig", setOf("0", "1")) ->
                    json(400, """{"ok":false,"message":"토너먼트 자동 시작 값이 올바르지 않습니다."}""")
                !reservation("reserveOpen") || !reservation("preReserveOpen") ->
                    json(400, """{"ok":false,"message":"예약 시간 형식이 올바르지 않습니다."}""")
                else -> null
            }
        } catch (e: Exception) {
            json(400, """{"ok":false,"message":"서버 리셋 요청 JSON이 올바르지 않습니다."}""")
        }
    }

    private fun canonicalServerDefs(servers: List<ServerDef>): List<ServerDef>? {
        val canonical = ArrayList<ServerDef>(servers.size)
        val seenIds = HashSet<String>(servers.size)
        for (server in servers) {
            val id = canonicalServerId(server.id) ?: return null
            if (id in reservedPublicServerIds || id in reservedGameRouteIds || !seenIds.add(id)) return null

            val expectedProject = defaultDeployProject(id)
            val expectedGameApiUrl = defaultGameApiUrl(id)
            val expectedGameEngineUrl = defaultGameEngineUrl(id)
            if (server.deployProject != expectedProject ||
                server.gameApiUrl != expectedGameApiUrl ||
                server.gameEngineUrl != expectedGameEngineUrl
            ) {
                return null
            }
            canonical += server.copy(
                id = id,
                gameApiUrl = expectedGameApiUrl,
                gameEngineUrl = expectedGameEngineUrl,
                deployProject = expectedProject,
            )
        }
        return canonical
    }

    private fun canonicalServerId(rawId: String): String? =
        rawId.takeIf { serverIdRegex.matches(it) && it.length <= maxPublicServerIdLength }
            ?.lowercase()

    private fun withServerId(body: String, serverId: String): String {
        val node = objectMapper.readTree(body)
        val objectNode = if (node is ObjectNode) node.deepCopy() as ObjectNode else objectMapper.createObjectNode()
        objectNode.put("id", serverId)
        return objectMapper.writeValueAsString(objectNode)
    }

    private fun createServerBodyForDeployer(body: String): String {
        val node = objectMapper.readTree(body)
        val objectNode = if (node is ObjectNode) node.deepCopy() as ObjectNode else objectMapper.createObjectNode()
        objectNode.put("id", objectNode.path("id").asText("").lowercase())
        return objectMapper.writeValueAsString(objectNode)
    }

    private fun serverDefFromCreateRequest(body: String): ServerDef {
        val node = objectMapper.readTree(body)
        val id = node.path("id").asText().lowercase()
        return ServerDef(
            id = id,
            name = node.path("name").asText(),
            gameApiUrl = defaultGameApiUrl(id),
            gameEngineUrl = defaultGameEngineUrl(id),
            deployProject = defaultDeployProject(id),
            generation = node.path("generation").asText("").toIntOrNull(),
            scenarioCode = node.path("scenarioCode").asText("").ifBlank { null },
        )
    }

    private fun isConfirmedServerSuccess(response: EnvProxyResponse, expectedId: String): Boolean =
        response.status in 200..299 && runCatching {
            val node = objectMapper.readTree(response.body)
            node.isObject && node.path("ok").isBoolean && node.path("ok").asBoolean() &&
                node.path("id").isTextual && node.path("id").asText() == expectedId
        }.getOrDefault(false)

    private fun persistRegistryChange(
        action: String,
        serverId: String,
        response: EnvProxyResponse,
        change: () -> Unit,
    ): EnvProxyResponse =
        try {
            change()
            response
        } catch (e: Exception) {
            log.error("game_server persistence failed action={} server={}", action, serverId, e)
            json(500, objectMapper.writeValueAsString(mapOf("ok" to false, "message" to "Server registry update failed.")))
        }

    private fun defaultDeployProject(canonicalId: String): String =
        "opensamguk-s$canonicalId"

    private fun defaultGameApiUrl(canonicalId: String): String =
        "http://s$canonicalId-game-api:8081"

    private fun defaultGameEngineUrl(canonicalId: String): String =
        "http://s$canonicalId-game-engine:8082"

    private fun validPort(value: String): Boolean {
        if (!portRegex.matches(value)) return false
        val n = value.toIntOrNull() ?: return false
        return n in 1..65535
    }

    private fun validGeneration(value: String): Boolean {
        val n = value.toIntOrNull() ?: return false
        return n >= 0
    }

    private fun json(status: Int, body: String): EnvProxyResponse =
        EnvProxyResponse(status.coerceIn(100, 599).takeIf { HttpStatus.resolve(it) != null } ?: 500, body)
}
