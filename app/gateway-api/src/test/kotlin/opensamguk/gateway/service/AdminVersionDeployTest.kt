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

    private fun canonicalServerJson(id: String, name: String = "통일 서버"): String {
        val canonicalId = id.lowercase()
        return """{"id":"$id","name":"$name","gameApiUrl":"http://s$canonicalId-game-api:8081","gameEngineUrl":"http://s$canonicalId-game-engine:8082","deployProject":"opensamguk-s$canonicalId"}"""
    }

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
              {"id":"s2","name":"군웅 서버","generation":7,"scenarioCode":"scenario_1021","gameApiUrl":"http://s2-game-api:8081","gameEngineUrl":"http://s2-game-engine:8082","deployProject":"opensamguk-s2"}
            ]
        """.trimIndent()
        val reg = registry(json = json)
        assertEquals(listOf("s1", "s2"), reg.all().map { it.id })
        assertEquals("opensamguk-s2", reg.find("s2")?.deployProject)
        assertEquals(7, reg.find("s2")?.generation)
        assertEquals("scenario_1021", reg.find("s2")?.scenarioCode)
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
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(200, "[]")
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val result = svc.deploy("does-not-exist", "v1.0.0", "admin")

            assertFalse(result.ok)
            assertEquals(listOf("/servers"), deployer.requests.map { it.path })
        }
    }

    @Test
    fun `잘못된 태그는 deploy action 전에 거부한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(200, "[${canonicalServerJson("s1")}]")
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val result = svc.deploy("s1", "bad tag!", "admin")

            assertFalse(result.ok)
            assertEquals("올바르지 않은 버전 태그입니다.", result.message)
            assertEquals(listOf("/servers"), deployer.requests.map { it.path })
        }
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
            deployer.enqueue(200, "[${canonicalServerJson("s1")}]")
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

            val request = deployer.requests.last()
            assertEquals(200, result.status)
            assertEquals(listOf("/servers", "/env/server?id=s1"), deployer.requests.map { it.path })
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
            deployer.enqueue(200, "[${canonicalServerJson("s1")}]")
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
            assertTrue(request.body.contains(""""id":"s1""""))
            assertTrue(request.body.contains(""""generation":"3""""))
            assertFalse(result.body.contains("tok"))
        }
    }

    @Test
    fun `서버 생성은 public ID pep을 deployer에 그대로 전달한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """{"ok":true,"id":"pep","name":"페포네 서버","project":"opensamguk-pep"}""",
            )
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val result = svc.createServer(
                """{"id":"pep","name":"페포네 서버","generation":"3","gameApiPort":"8101","webGamePort":"3101","imageTag":"v1"}""",
            )

            val request = deployer.requests.single()
            assertEquals(200, result.status)
            assertEquals("/servers/create", request.path)
            assertTrue(request.body.contains(""""id":"pep""""))
        }
    }

    @Test
    fun `current public ID는 예약하지 않고 deployer에 전달한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """{"ok":true,"id":"current","name":"현재 서버","project":"opensamguk-scurrent"}""",
            )
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val result = svc.createServer(
                """{"id":"current","name":"현재 서버","generation":"3","gameApiPort":"8101","webGamePort":"3101","imageTag":"v1"}""",
            )

            val request = deployer.requests.single()
            assertEquals(200, result.status)
            assertEquals("/servers/create", request.path)
            assertTrue(request.body.contains(""""id":"current""""))
        }
    }

    @Test
    fun `서버 생성은 대문자 public ID를 소문자로 표준화해 deployer에 전달한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """{"ok":true,"id":"a1","name":"알파 서버","project":"opensamguk-a1"}""",
            )
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val result = svc.createServer(
                """{"id":"A1","name":"알파 서버","generation":"3","gameApiPort":"8101","webGamePort":"3101","imageTag":"v1"}""",
            )

            val request = deployer.requests.single()
            assertEquals(200, result.status)
            assertEquals("/servers/create", request.path)
            assertTrue(request.body.contains(""""id":"a1""""))
            assertFalse(request.body.contains(""""id":"A1""""))
        }
    }

    @Test
    fun `48자 mixed-case public ID는 deployer에 소문자로 표준화해 전달한다`() {
        val rawId = "Ab".repeat(24)
        val canonicalId = rawId.lowercase()
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """{"ok":true,"id":"$canonicalId","name":"긴 ID 서버","project":"opensamguk-s$canonicalId"}""",
            )
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val result = svc.createServer(
                """{"id":"$rawId","name":"긴 ID 서버","generation":"3","gameApiPort":"8101","webGamePort":"3101","imageTag":"v1"}""",
            )

            val request = deployer.requests.single()
            assertEquals(200, result.status)
            assertTrue(request.body.contains(""""id":"$canonicalId""""))
            assertFalse(request.body.contains(""""id":"$rawId""""))
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
            deployer.enqueue(200, "[${canonicalServerJson("s1")}]")
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

            val request = deployer.requests.last()
            assertEquals(200, result.status)
            assertEquals(listOf("/servers", "/servers/close"), deployer.requests.map { it.path })
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
            deployer.enqueue(200, "[${canonicalServerJson("s1")}]")
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

            val request = deployer.requests.last()
            assertEquals(200, result.status)
            assertEquals(listOf("/servers", "/servers/reset"), deployer.requests.map { it.path })
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
            deployer.enqueue(200, "[${canonicalServerJson("s1")}]")
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

            val request = deployer.requests.last()
            assertEquals(200, result.status)
            assertEquals(listOf("/servers", "/servers/reset"), deployer.requests.map { it.path })
            assertEquals("/servers/reset", request.path)
            assertTrue(request.body.contains(""""generation":"0""""))
        }
    }

    @Test
    fun `deployer registry canonicalizes runtime-created mixed-case IDs before default Docker names`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """[{"id":"A1","name":"런타임 서버","generation":9}]""",
            )
            deployer.enqueue(
                200,
                """{"currentTag":"v8","availableTags":["v9","v8"]}""",
            )
            val svc = DeployService(deployer.url(), "tok", registry(json = ""), mapper)

            val status = svc.status("A1")

            assertTrue(status.configured)
            assertEquals("a1", status.serverId)
            assertEquals("v8", status.currentTag)
            assertEquals("v9", status.latestTag)
            assertTrue(status.promotionAvailable)
            assertEquals("/servers", deployer.requests[0].path)
            assertEquals("/status?project=opensamguk-sa1", deployer.requests[1].path)
        }
    }

    @Test
    fun `deployer registry fallback은 public s1에 내부 s를 한 번 더 붙인다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """[{"id":"s1","name":"통일 서버"}]""",
            )
            val svc = DeployService(deployer.url(), "tok", registry(json = ""), mapper)

            val server = svc.registeredServers().single()

            assertEquals("s1", server.id)
            assertEquals("opensamguk-ss1", server.deployProject)
            assertEquals("http://ss1-game-api:8081", server.gameApiUrl)
            assertEquals("http://ss1-game-engine:8082", server.gameEngineUrl)
            assertEquals("/servers", deployer.requests.single().path)
        }
    }

    @Test
    fun `fallback registry는 canonical ID collision과 invalid entry를 collection 단위로 거부한다`() {
        val longId = "a".repeat(49)
        val invalidCollections = listOf(
            """
                [
                  {"id":"A1","name":"알파","gameApiUrl":"http://sa1-game-api:8081","gameEngineUrl":"http://sa1-game-engine:8082","deployProject":"opensamguk-sa1"},
                  {"id":"a1","name":"알파","gameApiUrl":"http://sa1-game-api:8081","gameEngineUrl":"http://sa1-game-engine:8082","deployProject":"opensamguk-sa1"}
                ]
            """.trimIndent(),
            """
                [
                  {"id":"s1","name":"통일","gameApiUrl":"http://ss1-game-api:8081","gameEngineUrl":"http://ss1-game-engine:8082","deployProject":"opensamguk-ss1"},
                  {"id":"all","name":"예약","gameApiUrl":"http://sall-game-api:8081","gameEngineUrl":"http://sall-game-engine:8082","deployProject":"opensamguk-sall"}
                ]
            """.trimIndent(),
            """[{"id":"s1","name":"통일","gameApiUrl":"http://ss1-game-api:8081","gameEngineUrl":"http://ss1-game-engine:8082","deployProject":"opensamguk-ss1"},{"id":"a1","gameApiUrl":"http://sA1-game-api:8081","gameEngineUrl":"http://sa1-game-engine:8082","deployProject":"opensamguk-sa1"}]""",
            """[{"id":"s1","name":"통일","gameApiUrl":"http://ss1-game-api:8081","gameEngineUrl":"http://ss1-game-engine:8082","deployProject":"opensamguk-ss1"},{"id":"bad-id"}]""",
            """[{"id":"s1","name":"통일","gameApiUrl":"http://ss1-game-api:8081","gameEngineUrl":"http://ss1-game-engine:8082","deployProject":"opensamguk-ss1"},{"id":"$longId"}]""",
        )

        invalidCollections.forEach { json ->
            val svc = DeployService("", "", registry(json = json), mapper)

            assertTrue(svc.registeredServers().isEmpty(), "registry=$json")
        }
    }

    @Test
    fun `canonical fallback registry keeps canonical public and internal IDs`() {
        val svc = DeployService("", "", registry(json = "[${canonicalServerJson("A1", "알파")}]"), mapper)

        val server = svc.registeredServers().single()

        assertEquals("a1", server.id)
        assertEquals("opensamguk-sa1", server.deployProject)
        assertEquals("http://sa1-game-api:8081", server.gameApiUrl)
        assertEquals("http://sa1-game-engine:8082", server.gameEngineUrl)
    }

    @Test
    fun `deployer registry rejects the whole collection for collisions reserved IDs and noncanonical coordinates`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            val canonicalS1 = """{"id":"s1","name":"통일","gameApiUrl":"http://ss1-game-api:8081","gameEngineUrl":"http://ss1-game-engine:8082","deployProject":"opensamguk-ss1"}"""
            val longId = "a".repeat(49)
            val invalidCollections = listOf(
                """[$canonicalS1,{"id":"A1"},{"id":"a1"}]""",
                """[$canonicalS1,{"id":"main","gameApiUrl":"http://smain-game-api:8081","gameEngineUrl":"http://smain-game-engine:8082","deployProject":"opensamguk-smain"}]""",
                """[$canonicalS1,{"id":"bad-id"}]""",
                """[$canonicalS1,{"id":"$longId"}]""",
                """[$canonicalS1,{"id":"a1","gameApiUrl":"http://sa1-game-api:8081","gameEngineUrl":"http://sa1-game-engine:8082","deployProject":"opensamguk-sA1"}]""",
                """[$canonicalS1,{"id":"a1","gameApiUrl":"http://sA1-game-api:8081","gameEngineUrl":"http://sa1-game-engine:8082","deployProject":"opensamguk-sa1"}]""",
                """[$canonicalS1,{"id":"a1","gameApiUrl":"http://sa1-game-api:8081","gameEngineUrl":"http://sA1-game-engine:8082","project":"opensamguk-sa1"}]""",
            )

            invalidCollections.forEach { body ->
                deployer.enqueue(200, body)
                val svc = DeployService(deployer.url(), "tok", registry(), mapper)

                assertTrue(svc.registeredServers().isEmpty(), "deployer registry=$body")
            }

            assertEquals(invalidCollections.size, deployer.requests.size)
            assertTrue(deployer.requests.all { it.path == "/servers" })
        }
    }

    @Test
    fun `canonical deployer coordinates are accepted and uppercase public ID has canonical internal names`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(
                200,
                """[{"id":"A1","name":"알파","gameApiUrl":"http://sa1-game-api:8081","gameEngineUrl":"http://sa1-game-engine:8082","deployProject":"opensamguk-sa1"}]""",
            )
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val server = svc.registeredServers().single()

            assertEquals("a1", server.id)
            assertEquals("opensamguk-sa1", server.deployProject)
            assertEquals("http://sa1-game-api:8081", server.gameApiUrl)
            assertEquals("http://sa1-game-engine:8082", server.gameEngineUrl)
        }
    }

    @Test
    fun `invalid deployer registry fails closed through the existing unknown server response`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(200, """[{"id":"A1"},{"id":"a1"}]""")
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val status = svc.status("a1")

            assertFalse(status.configured)
            assertEquals("a1", status.serverId)
            assertEquals(listOf("/servers"), deployer.requests.map { it.path })
        }
    }

    @Test
    fun `서버 리셋 검증 실패는 deployer reset action 전에 거부한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            deployer.enqueue(200, "[${canonicalServerJson("s1")}]")
            val svc = DeployService(
                deployer.url(),
                "tok",
                registry(json = """[{"id":"s1","name":"통일 서버","deployProject":"opensamguk-s1"}]"""),
                mapper,
            )

            val result = svc.resetServer("s1", """{"confirm":"RESET s1","turnTerm":"999","autorunUserOptions":["bad"]}""")

            assertEquals(400, result.status)
            assertEquals(listOf("/servers"), deployer.requests.map { it.path })
        }
    }

    @Test
    fun `영숫자가 아닌 public 서버 ID는 deployer 호출 전에 거부한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            listOf("", "pep-1", "pep_1", "pep/1", "한글", "../s1").forEach { id ->
                val result = svc.createServer(
                    """{"id":${mapper.writeValueAsString(id)},"name":"bad","gameApiPort":"8101","webGamePort":"3101"}""",
                )

                assertEquals(400, result.status, "id=$id")
                assertTrue(result.body.contains("서버 id가 올바르지 않습니다."), "id=$id")
            }
            assertEquals(0, deployer.requests.size)
        }
    }

    @Test
    fun `49자 public 서버 ID는 deployer 호출 전에 명확히 거부한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)
            val id = "a".repeat(49)

            val result = svc.createServer(
                """{"id":"$id","name":"too long","gameApiPort":"8101","webGamePort":"3101"}""",
            )

            assertEquals(400, result.status)
            assertTrue(result.body.contains("서버 id는 최대 48자여야 합니다."))
            assertEquals(0, deployer.requests.size)
        }
    }

    @Test
    fun `fallback registry는 invalid 또는 49자 ID가 섞이면 전체를 fail closed한다`() {
        val longId = "a".repeat(49)
        val svc = DeployService(
            "",
            "",
            registry(json = """[{"id":"A1","name":"알파"},{"id":"bad-id"},{"id":"$longId"}]"""),
            mapper,
        )

        assertTrue(svc.registeredServers().isEmpty())
    }

    @Test
    fun `deployer 예약 public 서버 ID는 raw와 canonical 대소문자 모두 deployer 호출 전에 거부한다`() {
        val fake = FakeDeployer()
        fake.use { deployer ->
            val svc = DeployService(deployer.url(), "tok", registry(), mapper)

            val reservedPublicIds = listOf(
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

            reservedPublicIds.flatMap { canonicalId ->
                listOf(canonicalId, canonicalId.uppercase())
            }.forEach { id ->
                val result = svc.createServer(
                    """{"id":${mapper.writeValueAsString(id)},"name":"bad","gameApiPort":"8101","webGamePort":"3101"}""",
                )

                assertEquals(400, result.status, "id=$id")
            }
            listOf("all", "ALL", "aLl").forEach { id ->
                val result = svc.createServer(
                    """{"id":${mapper.writeValueAsString(id)},"name":"bad","gameApiPort":"8101","webGamePort":"3101"}""",
                )

                val canonicalId = id.lowercase()
                assertTrue(result.body.contains("서버 id ${canonicalId}은 예약되어 사용할 수 없습니다."), "id=$id")
            }
            listOf("main", "MAIN", "mAiN", "join", "JOIN", "jOiN").forEach { id ->
                val result = svc.createServer(
                    """{"id":${mapper.writeValueAsString(id)},"name":"bad","gameApiPort":"8101","webGamePort":"3101"}""",
                )

                val canonicalId = id.lowercase()
                assertTrue(result.body.contains("서버 id ${canonicalId}은 게임 경로와 충돌해 사용할 수 없습니다."), "id=$id")
            }
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
