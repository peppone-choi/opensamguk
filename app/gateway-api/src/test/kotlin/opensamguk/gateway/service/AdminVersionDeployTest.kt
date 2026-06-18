package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 어드민 버전/배포 백엔드의 순수 게이트 — 네트워크에 닿지 않는 경로만 검증한다.
 * 레지스트리 파싱 + deploy 가드(미설정·알수없는서버·잘못된태그)는 전부 RestClient 호출 이전에
 * 결정되므로 deployer 없이도 결정적으로 테스트 가능하다.
 */
class AdminVersionDeployTest {

    private val mapper = ObjectMapper()

    /** 레지스트리 빌더 — @Value 인자를 직접 채워 생성. */
    private fun registry(
        json: String = "",
        gameApi: String = "http://game-api:8081",
        gameEngine: String = "http://game-engine:8082",
        project: String = "opensamguk",
    ) = ServerRegistry(json, gameApi, gameEngine, project, "통일 서버", mapper)

    @Test
    fun `빈 JSON이면 서버 목록을 비운다`() {
        val reg = registry(json = "")
        assertEquals(emptyList<ServerDef>(), reg.all())
        assertEquals(null, reg.default())
    }

    @Test
    fun `유효 JSON 배열은 삽입 순서대로 파싱된다`() {
        val json = """
            [
              {"id":"s1","name":"통일 서버","generation":1,"gameApiUrl":"http://s1-game-api:8081","gameEngineUrl":"http://s1-game-engine:8082","deployProject":"opensamguk-s1"},
              {"id":"s2","name":"군웅 서버","generation":7,"gameApiUrl":"http://s2-game-api:8081","gameEngineUrl":"http://s2-game-engine:8082","deployProject":"opensamguk-s2"}
            ]
        """.trimIndent()
        val reg = registry(json = json)
        assertEquals(listOf("s1", "s2"), reg.all().map { it.id })
        assertEquals("opensamguk-s2", reg.find("s2")?.deployProject)
        assertEquals(7, reg.find("s2")?.generation)
        assertEquals("http://s1-game-engine:8082", reg.find("s1")?.gameEngineUrl)
        assertEquals("s1", reg.default()?.id) // 첫 서버가 기본값
    }

    @Test
    fun `id 없는 항목은 걸러지고 deployProject 누락은 기본값으로 채워진다`() {
        val json = """[{"name":"빈ID"},{"id":"s9","name":"부분","gameApiUrl":"http://x:8081"}]"""
        val reg = registry(json = json, project = "opensamguk")
        assertEquals(listOf("s9"), reg.all().map { it.id })
        assertEquals("opensamguk", reg.find("s9")?.deployProject)
    }

    @Test
    fun `깨진 JSON이면 서버 목록을 비운다`() {
        val reg = registry(json = "{not json")
        assertEquals(emptyList<ServerDef>(), reg.all())
    }

    @Test
    fun `deployer 미설정이면 status는 configured=false`() {
        val svc = DeployService("", "", registry(), mapper)
        val status = svc.status(null)
        assertFalse(status.configured)
        assertEquals(null, status.serverId)
    }

    @Test
    fun `알 수 없는 서버는 deploy 거부`() {
        val svc = DeployService("http://deployer:9000", "tok", registry(), mapper)
        val result = svc.deploy("does-not-exist", "v1.0.0", "admin")
        assertFalse(result.ok)
    }

    @Test
    fun `잘못된 태그는 deploy 거부(네트워크 이전)`() {
        val svc = DeployService(
            "http://deployer:9000",
            "tok",
            registry(json = """[{"id":"main","name":"통일 서버"}]"""),
            mapper,
        )
        val result = svc.deploy("main", "bad tag!", "admin")
        assertFalse(result.ok)
        assertEquals("올바르지 않은 버전 태그입니다.", result.message)
    }

    @Test
    fun `deployer 미설정이면 shared env는 configured=false`() {
        val svc = DeployService("", "", registry(), mapper)
        val result = svc.sharedEnv()

        assertEquals(200, result.status)
        val body = mapper.readTree(result.body)
        assertFalse(body.path("configured").asBoolean())
        assertEquals("shared", body.path("scope").asText())
    }

    @Test
    fun `알 수 없는 서버는 deployer registry 확인 후 server env 조회 전에 거부`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)
            val result = svc.serverEnv("missing")

            assertEquals(400, result.status)
            assertEquals(listOf("/servers"), deployer.requests.map { it.path })
        }
    }

    @Test
    fun `shared env 조회는 secret-masked deployer 응답을 보존한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """{"ok":true,"configured":true,"scope":"shared","fields":{"ADMIN_PASSWORD":{"key":"ADMIN_PASSWORD","value":null,"configured":true,"writeOnly":true,"masked":true}}}""",
            )
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val result = svc.sharedEnv()

            assertEquals(200, result.status)
            assertEquals("/env/shared", deployer.requests.single().path)
            assertEquals("Bearer tok", deployer.requests.single().authorization)
            assertTrue(result.body.contains(""""masked":true"""))
            assertTrue(result.body.contains(""""value":null"""))
            assertFalse(result.body.contains("tok"))
        }
    }

    @Test
    fun `server env 변경은 레지스트리 서버 id로 deployer에 PATCH한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """{"ok":true,"scope":"server","id":"s1","fields":{"SCENARIO_SEED_ENABLED":{"key":"SCENARIO_SEED_ENABLED","value":"false","configured":true,"writeOnly":false,"masked":false}}}""",
            )
            val svc = DeployService(
                deployer.url(),
                "tok",
                registry(json = """[{"id":"s1","name":"통일 서버","deployProject":"opensamguk-s1"}]"""),
                mapper,
            )

            val webGameTag = "057ea7ff7242a84c426d9c8e958751f4029d2421"
            val body = """{"values":{"SCENARIO_SEED_ENABLED":"false","SERVER_GENERATION":"2","WEB_GAME_TAG":"$webGameTag"}}"""
            val result = svc.patchServerEnv("s1", body)

            val request = deployer.requests.single()
            assertEquals(200, result.status)
            assertEquals("/env/server?id=s1", request.path)
            assertEquals("PATCH", request.method)
            assertEquals(body, request.body)
        }
    }

    @Test
    fun `잘못된 env key는 deployer 호출 전에 거부한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)
            val result = svc.patchSharedEnv("""{"values":{"bad-key":"x"}}""")

            assertEquals(400, result.status)
            assertEquals(0, deployer.requests.size)
        }
    }

    @Test
    fun `서버 생성은 deployer create endpoint로 POST한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """{"ok":true,"id":"s1","name":"통일 서버","project":"opensamguk-s1"}""",
            )
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val result = svc.createServer("""{"id":"s1","name":"통일 서버","generation":"3","gameApiPort":"8101","webGamePort":"3101","imageTag":"v1"}""")

            val request = deployer.requests.single()
            assertEquals(200, result.status)
            assertEquals("/servers/create", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer tok", request.authorization)
            assertTrue(request.body.contains(""""id":"1""""))
            assertTrue(request.body.contains(""""generation":"3""""))
            assertFalse(result.body.contains("tok"))
        }
    }

    @Test
    fun `알파 서버 생성은 0기를 허용한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """{"ok":true,"id":"s0","name":"알파 서버","project":"opensamguk-s0"}""",
            )
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val result = svc.createServer("""{"id":"s0","name":"알파 서버","generation":"0","gameApiPort":"8102","webGamePort":"3102","imageTag":"v1"}""")

            val request = deployer.requests.single()
            assertEquals(200, result.status)
            assertEquals("/servers/create", request.path)
            assertTrue(request.body.contains(""""generation":"0""""))
        }
    }

    @Test
    fun `서버 삭제는 deployer close endpoint로 POST한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """{"ok":true,"id":"s1","name":"통일 서버","project":"opensamguk-s1"}""",
            )
            val svc = DeployService(
                deployer.url(),
                "tok",
                registry(json = """[{"id":"s1","name":"통일 서버","deployProject":"opensamguk-s1"}]"""),
                mapper,
            )

            val result = svc.deleteServer("s1")

            val request = deployer.requests.single()
            assertEquals(200, result.status)
            assertEquals("/servers/close", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer tok", request.authorization)
            assertTrue(request.body.contains(""""id":"s1""""))
        }
    }

    @Test
    fun `서버 리셋은 deployer reset endpoint로 POST한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """{"ok":true,"id":"s1","name":"통일 서버","project":"opensamguk-s1"}""",
            )
            val svc = DeployService(
                deployer.url(),
                "tok",
                registry(json = """[{"id":"s1","name":"통일 서버","deployProject":"opensamguk-s1"}]"""),
                mapper,
            )

            val result = svc.resetServer("s1", """{"confirm":"RESET s1","generation":"2","scenarioCode":"scenario_1002","turnTerm":"30","sync":"1","fiction":"0","extend":"1","blockGeneralCreate":"2","npcMode":"2","showImgLevel":"3","autorunUserOptions":["develop","battle"],"autorunUserMinutes":"1440","joinMode":"onlyRandom","tournamentTrig":"1","reserveOpen":"2026-06-10 20:00","preReserveOpen":"2026-06-10 19:00"}""")

            val request = deployer.requests.single()
            assertEquals(200, result.status)
            assertEquals("/servers/reset", request.path)
            assertEquals("POST", request.method)
            assertEquals("Bearer tok", request.authorization)
            assertTrue(request.body.contains(""""id":"s1""""))
            assertTrue(request.body.contains(""""confirm":"RESET s1""""))
            assertTrue(request.body.contains(""""generation":"2""""))
            assertTrue(request.body.contains(""""autorunUserOptions":["develop","battle"]"""))
            assertTrue(request.body.contains(""""preReserveOpen":"2026-06-10 19:00""""))
        }
    }

    @Test
    fun `알파 서버 리셋은 0기를 허용한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """{"ok":true,"id":"s1","name":"통일 서버","project":"opensamguk-s1"}""",
            )
            val svc = DeployService(
                deployer.url(),
                "tok",
                registry(json = """[{"id":"s1","name":"통일 서버","deployProject":"opensamguk-s1"}]"""),
                mapper,
            )

            val result = svc.resetServer("s1", """{"confirm":"RESET s1","generation":"0","scenarioCode":"scenario_1010"}""")

            val request = deployer.requests.single()
            assertEquals(200, result.status)
            assertEquals("/servers/reset", request.path)
            assertTrue(request.body.contains(""""generation":"0""""))
        }
    }

    @Test
    fun `deployer registry can resolve runtime-created servers without gateway restart`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """[{"id":"s9","name":"런타임 서버","generation":9,"gameApiUrl":"http://s9-api:8081","gameEngineUrl":"http://s9-engine:8082","deployProject":"opensamguk-s9"}]""",
            )
            deployer.enqueue(
                200,
                """{"currentTag":"v9","availableTags":["v9"]}""",
            )
            val svc = DeployService(deployer.url(), "tok", registry(json = ""), mapper)

            val status = svc.status("s9")

            assertTrue(status.configured)
            assertEquals("s9", status.serverId)
            assertEquals("v9", status.currentTag)
            assertEquals("/servers", deployer.requests[0].path)
            assertEquals("/status?project=opensamguk-s9", deployer.requests[1].path)
        }
    }

    @Test
    fun `서버 리셋 검증 실패는 deployer 호출 전에 거부한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            val svc = DeployService(
                deployer.url(),
                "tok",
                registry(json = """[{"id":"s1","name":"통일 서버","deployProject":"opensamguk-s1"}]"""),
                mapper,
            )

            val result = svc.resetServer("s1", """{"confirm":"RESET s1","turnTerm":"999","autorunUserOptions":["bad"]}""")

            assertEquals(400, result.status)
            assertEquals(0, deployer.requests.size)
        }
    }

    @Test
    fun `서버 생성 검증 실패는 deployer 호출 전에 거부한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val result = svc.createServer("""{"id":"../s1","name":"bad","gameApiPort":"8101","webGamePort":"3101"}""")

            assertEquals(400, result.status)
            assertEquals(0, deployer.requests.size)
        }
    }

    @Test
    fun `알 수 없는 env key는 deployer 호출 전에 거부한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val result = svc.patchSharedEnv("""{"values":{"UNKNOWN_KEY":"x"}}""")

            assertEquals(400, result.status)
            assertTrue(result.body.contains("UNKNOWN_KEY"))
            assertFalse(result.body.contains("tok"))
            assertEquals(0, deployer.requests.size)
        }
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String,
    )

    private class FakeDeployer : AutoCloseable {
        private val responses = ArrayDeque<Pair<Int, String>>()
        val requests = mutableListOf<RecordedRequest>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

        init {
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
        }

        fun url(): String = "http://127.0.0.1:${server.address.port}"

        fun enqueue(status: Int, body: String) {
            responses.add(status to body)
        }

        private fun handle(exchange: HttpExchange) {
            val response = responses.removeFirstOrNull() ?: (200 to "{}")
            val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            requests.add(
                RecordedRequest(
                    method = exchange.requestMethod,
                    path = exchange.requestURI.toString(),
                    authorization = exchange.requestHeaders.getFirst("Authorization"),
                    body = requestBody,
                ),
            )
            val bytes = response.second.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(response.first, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        override fun close() {
            server.stop(0)
        }
    }
}
