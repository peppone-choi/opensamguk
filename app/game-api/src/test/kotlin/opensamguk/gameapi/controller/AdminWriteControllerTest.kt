package opensamguk.gameapi.controller

import opensamguk.gameapi.admin.AdminGeneralModerationService
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.security.GameApiJwtVerifier
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import kotlin.test.assertEquals

/**
 * Admin write API slice tests — ADMIN gate + daemon command intake.
 */
class AdminWriteControllerTest {

    private val verifier = mock(GameApiJwtVerifier::class.java)
    private val commands = mock(CommandReserveService::class.java)
    private val generalResolver = mock(GeneralResolver::class.java)
    private val generalModeration = mock(AdminGeneralModerationService::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(AdminWriteController(verifier, commands, generalResolver, generalModeration)).build()

    private fun stubAdmin(token: String = "admintok") {
        `when`(verifier.isValid(token)).thenReturn(true)
        `when`(verifier.getRole(token)).thenReturn("ADMIN")
        `when`(verifier.getUserId(token)).thenReturn(7L)
        `when`(generalResolver.resolveGeneralId(7L)).thenReturn(77)
    }

    private fun bearer(token: String) = "Bearer $token"

    private fun anyCommand(): TurnDaemonCommand =
        any(TurnDaemonCommand::class.java) ?: TurnDaemonCommand.Pause()

    private fun captureCommand(captor: ArgumentCaptor<TurnDaemonCommand>): TurnDaemonCommand =
        captor.capture() ?: TurnDaemonCommand.Pause()

    @Test
    fun `patch game-settings updates whitelisted config keys`() {
        stubAdmin()

        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"npcmode":1,"block_general_create":2}}"""),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.updated[0]").exists())

        val command = captureWorldCommand()
        assertEquals(listOf("npcmode", "block_general_create"), command.settings.map { it.key })
        assertEquals(listOf(1, 2), command.settings.map { it.intValue })
    }

    @Test
    fun `patch game-settings rejects unknown key`() {
        stubAdmin()
        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"unknown_key":1}}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value(false))

        verify(commands, never()).publishImmediate(anyCommand())
    }

    @Test
    fun `patch game-settings rejects out of range value`() {
        stubAdmin()
        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"npcmode":99}}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value(false))
    }

    @Test
    fun `patch game-settings requires admin token`() {
        `when`(verifier.isValid("usertok")).thenReturn(true)
        `when`(verifier.getRole("usertok")).thenReturn("USER")

        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("usertok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"npcmode":1}}"""),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `patch game-settings without token is 401`() {
        mockMvc().perform(
            patch("/api/admin/game-settings")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"npcmode":1}}"""),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `patch game-settings rejects empty body`() {
        stubAdmin()
        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{}}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.reason").value("values is empty"))
    }

    @Test
    fun `patch game-settings rejects invalid token`() {
        `when`(verifier.isValid("bad")).thenReturn(false)

        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("bad"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"npcmode":1}}"""),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `patch game-settings preserves insertion order`() {
        stubAdmin()
        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"npcmode":1}}"""),
        )
            .andExpect(status().isAccepted)

        assertEquals(listOf("npcmode"), captureWorldCommand().settings.map { it.key })
    }

    @Test
    fun `patch game-settings rejects string value that is not numeric`() {
        stubAdmin()
        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"npcmode":"not_a_number"}}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value(false))
    }

    @Test
    fun `patch game-settings queues trimmed msg for daemon game-env write`() {
        stubAdmin()

        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"msg":"  안녕하세요  "}}"""),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.result").value(true))

        val setting = captureWorldCommand().settings.single()
        assertEquals("msg", setting.key)
        assertEquals("안녕하세요", setting.stringValue)
    }

    @Test
    fun `patch game-settings updates turnterm and tickSeconds and signals restart required`() {
        stubAdmin()
        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"turnterm":30}}"""),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.restartRequired").value(true))

        assertEquals(30, captureWorldCommand().settings.single().intValue)
    }

    @Test
    fun `patch game-settings validates maxgeneral range`() {
        stubAdmin()
        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"maxgeneral":0}}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value(false))
    }

    @Test
    fun `patch game-settings validates starttime format`() {
        stubAdmin()
        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"starttime":"2026-06-16 12:00"}}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value(false))
    }

    @Test
    fun `post general-moderation applies action with selected generals and actor general`() {
        stubAdmin()
        `when`(generalModeration.apply("block1", listOf(1, 2), null, 77))
            .thenReturn(AdminGeneralModerationService.Result("block1", 2, listOf("req-admin-1")))

        mockMvc().perform(
            post("/api/admin/general-moderation")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"action":"block1","generalIds":[1,2]}"""),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.action").value("block1"))
            .andExpect(jsonPath("$.affected").value(2))
            .andExpect(jsonPath("$.requestIds[0]").value("req-admin-1"))
    }

    @Test
    fun `post general-moderation surfaces validation failure`() {
        stubAdmin()
        `when`(generalModeration.apply("sendMessage", emptyList(), "안녕", 77))
            .thenThrow(IllegalArgumentException("대상 장수를 선택하세요."))

        mockMvc().perform(
            post("/api/admin/general-moderation")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"action":"sendMessage","generalIds":[],"message":"안녕"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.reason").value("대상 장수를 선택하세요."))
    }

    @Test
    fun `post server-status queues validated daemon command`() {
        stubAdmin()

        mockMvc().perform(
            post("/api/admin/server-status")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"PRE_OPEN"}"""),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("PRE_OPEN"))

        assertEquals("PRE_OPEN", captureWorldCommand().status)
    }

    private fun captureWorldCommand(): TurnDaemonCommand.AdminWorldSettings {
        val captor = ArgumentCaptor.forClass(TurnDaemonCommand::class.java)
        verify(commands).publishImmediate(captureCommand(captor))
        return captor.value as TurnDaemonCommand.AdminWorldSettings
    }
}
