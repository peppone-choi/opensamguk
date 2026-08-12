package opensamguk.gameapi.web

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.precheck.PrecheckResult
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.reserve.CommandQueueService
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.CommandReserveService.ReserveResult
import opensamguk.infra.persistence.CommandResultRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import kotlin.test.assertEquals

class CommandControllerHtmlSanitizerTest {

    private val objectMapper = ObjectMapper()
    private val precheck = mock(CommandPrecheckService::class.java)
    private val reserve = mock(CommandReserveService::class.java)
    private val controller = CommandController(
        precheck = precheck,
        reserve = reserve,
        resolver = mock(GeneralResolver::class.java),
        queue = mock(CommandQueueService::class.java),
        generals = mock(GeneralReadRepository::class.java),
        commandResults = mock(CommandResultRepository::class.java),
        redis = mock(StringRedisTemplate::class.java),
        objectMapper = objectMapper,
        profile = "che:scenario_2",
        processWorld = GameApiProcessWorld(1),
    )

    @Test
    fun `passes the sanitized body to precheck and reservation before the fingerprint boundary`() {
        val raw = """{"msg":"<p><strong>천하</strong><img src=x onerror=alert(1)></p>"}"""
        val expectedHtml = "<p><strong>천하</strong></p>"
        val expectedArgs = mapOf<String, Any?>("msg" to expectedHtml)
        val expectedBody = objectMapper.writeValueAsString(expectedArgs)
        `when`(precheck.precheck(10, "setNotice", expectedArgs)).thenReturn(PrecheckResult.Available)
        `when`(reserve.reserve(10, "setNotice", 0, expectedBody)).thenReturn(ReserveResult("req-safe", 0))

        val response = controller.command(userId = null, code = "setNotice", generalId = 10, turnIdx = 0, argJson = raw)

        assertEquals(202, response.statusCode.value())
        verify(precheck).precheck(10, "setNotice", expectedArgs)
        verify(reserve).reserve(10, "setNotice", 0, expectedBody)
    }

    @Test
    fun `rejects an over limit raw request before sanitization precheck and reservation`() {
        val raw = objectMapper.writeValueAsString(mapOf("msg" to "<script>${"😀".repeat(1001)}</script>"))

        val response = controller.command(userId = null, code = "setScoutMsg", generalId = 10, turnIdx = 0, argJson = raw)

        assertEquals(200, response.statusCode.value())
        assertEquals(
            CommandController.BlockedResponse(
                status = "BLOCKED",
                reason = "'msg' 항목의 길이는 최대 1000자 입니다.",
            ),
            response.body,
        )
        verifyNoInteractions(precheck, reserve)
    }

    @Test
    fun `accepts the setNotice raw 16384 code point boundary through reservation`() {
        val message = "😀".repeat(16384)
        val raw = objectMapper.writeValueAsString(mapOf("msg" to message))
        val expectedArgs = mapOf<String, Any?>("msg" to message)
        `when`(precheck.precheck(10, "setNotice", expectedArgs)).thenReturn(PrecheckResult.Available)
        `when`(reserve.reserve(10, "setNotice", 0, raw)).thenReturn(ReserveResult("req-notice-max", 0))

        val response = controller.command(userId = null, code = "setNotice", generalId = 10, turnIdx = 0, argJson = raw)

        assertEquals(202, response.statusCode.value())
        verify(precheck).precheck(10, "setNotice", expectedArgs)
        verify(reserve).reserve(10, "setNotice", 0, raw)
    }

    @Test
    fun `rejects the setNotice raw 16385 code point boundary before reservation`() {
        val raw = objectMapper.writeValueAsString(mapOf("msg" to "😀".repeat(16385)))

        val response = controller.command(userId = null, code = "setNotice", generalId = 10, turnIdx = 0, argJson = raw)

        assertEquals(200, response.statusCode.value())
        assertEquals(
            CommandController.BlockedResponse(
                status = "BLOCKED",
                reason = "'msg' 항목의 길이는 최대 16384자 입니다.",
            ),
            response.body,
        )
        verifyNoInteractions(precheck, reserve)
    }
}
