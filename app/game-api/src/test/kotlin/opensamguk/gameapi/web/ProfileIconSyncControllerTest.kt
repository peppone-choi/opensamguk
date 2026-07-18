package opensamguk.gameapi.web

import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.CommandReserveService.ReserveResult
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals

/**
 * OPENSAM-94 — game-api 인테이크 컨트롤러 인증·shape 검증·발행 테스트.
 */
class ProfileIconSyncControllerTest {
    private val reserve = mock(CommandReserveService::class.java)

    private fun anyCommand(): TurnDaemonCommand =
        any(TurnDaemonCommand::class.java) ?: TurnDaemonCommand.Pause()

    private fun mockMvc(token: String = ""): MockMvc =
        MockMvcBuilders.standaloneSetup(ProfileIconSyncController(reserve, token)).build()

    private val validBody = """{"userId":7,"picture":"abcd1234.jpg","imgsvr":1,"grade":5}"""

    @Test
    fun `valid payload with no token configured publishes typed command and returns 202`() {
        `when`(reserve.publishImmediate(anyCommand())).thenReturn(ReserveResult(requestId = "req-1", turnIdx = 0))

        mockMvc().perform(
            post("/api/internal/profile-icon-sync").contentType(MediaType.APPLICATION_JSON).content(validBody),
        ).andExpect(status().isAccepted)

        val captor = ArgumentCaptor.forClass(TurnDaemonCommand::class.java)
        verify(reserve).publishImmediate(captor.capture() ?: TurnDaemonCommand.Pause())
        val cmd = captor.value as TurnDaemonCommand.ProfileIconSync
        assertEquals(7L, cmd.userId)
        assertEquals("abcd1234.jpg", cmd.picture)
        assertEquals(1, cmd.imgsvr)
        assertEquals(5, cmd.grade)
    }

    @Test
    fun `configured token rejects mismatched header with 401 and no publish`() {
        mockMvc(token = "s3cret").perform(
            post("/api/internal/profile-icon-sync")
                .header("X-Profile-Sync-Token", "wrong")
                .contentType(MediaType.APPLICATION_JSON).content(validBody),
        ).andExpect(status().isUnauthorized)

        verifyNoInteractions(reserve)
    }

    @Test
    fun `configured token accepts matching header`() {
        `when`(reserve.publishImmediate(anyCommand())).thenReturn(ReserveResult(requestId = "req-2", turnIdx = 0))

        mockMvc(token = "s3cret").perform(
            post("/api/internal/profile-icon-sync")
                .header("X-Profile-Sync-Token", "s3cret")
                .contentType(MediaType.APPLICATION_JSON).content(validBody),
        ).andExpect(status().isAccepted)

        verify(reserve).publishImmediate(anyCommand())
    }

    @Test
    fun `invalid shapes are rejected with 400 and no publish`() {
        val badBodies = listOf(
            """{"userId":0,"picture":"x.jpg","imgsvr":1,"grade":5}""",       // userId <= 0
            """{"userId":7,"picture":"","imgsvr":1,"grade":5}""",            // blank picture
            """{"userId":7,"picture":"../escape.jpg","imgsvr":1,"grade":5}""", // traversal
            """{"userId":7,"picture":"sub/dir.jpg","imgsvr":1,"grade":5}""",  // path separator
            """{"userId":7,"picture":"x.jpg","imgsvr":2,"grade":5}""",        // imgsvr not 0/1
            """{"userId":7,"picture":"x.jpg","imgsvr":1,"grade":-1}""",       // negative grade
        )
        for (body in badBodies) {
            mockMvc().perform(
                post("/api/internal/profile-icon-sync").contentType(MediaType.APPLICATION_JSON).content(body),
            ).andExpect(status().isBadRequest)
        }
        verifyNoInteractions(reserve)
    }
}
