package opensamguk.gameapi.controller

import opensamguk.infra.entity.MessageEntity
import opensamguk.infra.read.MessageRepository
import opensamguk.logic.message.MessageType
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Optional

/**
 * W3 — [MailboxController] 슬라이스 테스트(MockMvc standalone + mocked 레포).
 *
 * 검증: message body jsonb의 `{src, dest, text, option}`이 srcTarget/destTarget(MsgTarget) + text +
 * option으로 디코드되고, raw message 문자열도 보존되며, body 디코드 실패/누락 시 graceful(null).
 */
class MailboxControllerTest {

    private val messages = mock(MessageRepository::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(MailboxController(messages)).build()

    private fun message(id: Int, mailbox: Int, body: String) = MessageEntity(
        mailbox = mailbox,
        type = MessageType.PRIVATE,
        src = 42,
        dest = 7,
        time = Instant.parse("2026-06-01T00:00:00Z"),
        validUntil = Instant.parse("2026-06-01T00:05:00Z"),
        message = body,
        id = id,
    )

    @Test
    fun `mailbox decodes MsgTarget src dest, text and option from body jsonb`() {
        val body = """
            {"text":"안녕하시오",
             "src":{"id":42,"name":"조조","nation_id":1,"nation":"위","color":"#c62828","icon":"a.jpg"},
             "dest":{"id":7,"name":"손권","nation_id":2,"nation":"오","color":"#1565c0","icon":"b.jpg"},
             "option":{"deletable":true,"receiverMessageID":99}}
        """.trimIndent()
        `when`(messages.findByMailboxOrderById(100)).thenReturn(listOf(message(1, 100, body)))

        mockMvc().perform(get("/api/mailbox/100"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(1))
            .andExpect(jsonPath("$[0].src").value(42))   // int 라우팅 키 보존
            .andExpect(jsonPath("$[0].dest").value(7))
            .andExpect(jsonPath("$[0].text").value("안녕하시오"))
            // srcTarget MsgTarget
            .andExpect(jsonPath("$[0].srcTarget.id").value(42))
            .andExpect(jsonPath("$[0].srcTarget.name").value("조조"))
            .andExpect(jsonPath("$[0].srcTarget.nationId").value(1))
            .andExpect(jsonPath("$[0].srcTarget.nation").value("위"))
            .andExpect(jsonPath("$[0].srcTarget.color").value("#c62828"))
            .andExpect(jsonPath("$[0].srcTarget.icon").value("a.jpg"))
            // destTarget
            .andExpect(jsonPath("$[0].destTarget.name").value("손권"))
            .andExpect(jsonPath("$[0].destTarget.nationId").value(2))
            // option block
            .andExpect(jsonPath("$[0].option.deletable").value(true))
            .andExpect(jsonPath("$[0].option.receiverMessageID").value(99))
            // raw 보존
            .andExpect(jsonPath("$[0].message").exists())
    }

    @Test
    fun `message with empty body decodes to null targets gracefully`() {
        `when`(messages.findById(5)).thenReturn(Optional.of(message(5, 100, "{}")))

        mockMvc().perform(get("/api/messages/5"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(5))
            .andExpect(jsonPath("$.srcTarget").value(nullValue()))  // {} → src 키 없음 → JSON null
            .andExpect(jsonPath("$.text").value(nullValue()))
    }

    @Test
    fun `unknown message returns 404`() {
        `when`(messages.findById(999)).thenReturn(Optional.empty())
        mockMvc().perform(get("/api/messages/999"))
            .andExpect(status().isNotFound)
    }
}
