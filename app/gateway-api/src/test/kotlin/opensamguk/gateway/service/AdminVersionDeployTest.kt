package opensamguk.gateway.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
              {"id":"s1","name":"통일 서버","gameApiUrl":"http://s1-game-api:8081","gameEngineUrl":"http://s1-game-engine:8082","deployProject":"opensamguk-s1"},
              {"id":"s2","name":"군웅 서버","gameApiUrl":"http://s2-game-api:8081","gameEngineUrl":"http://s2-game-engine:8082","deployProject":"opensamguk-s2"}
            ]
        """.trimIndent()
        val reg = registry(json = json)
        assertEquals(listOf("s1", "s2"), reg.all().map { it.id })
        assertEquals("opensamguk-s2", reg.find("s2")?.deployProject)
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
}
