package opensamguk.gameapi.controller

import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.security.GameApiJwtVerifier
import org.junit.jupiter.api.Test
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
 * Admin write API slice tests — ADMIN gate + config patch persistence.
 */
class AdminWriteControllerTest {

    private val verifier = mock(GameApiJwtVerifier::class.java)
    private val world = mock(WorldStateReadRepository::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(AdminWriteController(verifier, world)).build()

    private fun stubAdmin(token: String = "admintok") {
        `when`(verifier.isValid(token)).thenReturn(true)
        `when`(verifier.getRole(token)).thenReturn("ADMIN")
    }

    private fun bearer(token: String) = "Bearer $token"

    @Test
    fun `patch game-settings updates whitelisted config keys`() {
        stubAdmin()
        val entity = WorldStateReadEntity(
            id = 1,
            config = linkedMapOf("startyear" to 180, "npcmode" to 0, "block_general_create" to 0),
        )
        `when`(world.findById(1)).thenReturn(java.util.Optional.of(entity))
        `when`(world.save(any(WorldStateReadEntity::class.java))).thenReturn(entity)

        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"npcmode":1,"block_general_create":2}}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.updated[0]").exists())

        verify(world).save(any(WorldStateReadEntity::class.java))
        assertEquals(1, entity.config["npcmode"])
        assertEquals(2, entity.config["block_general_create"])
        assertEquals(180, entity.config["startyear"])
    }

    @Test
    fun `patch game-settings rejects unknown key`() {
        stubAdmin()
        val entity = WorldStateReadEntity(id = 1, config = linkedMapOf("npcmode" to 0))
        `when`(world.findById(1)).thenReturn(java.util.Optional.of(entity))

        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"unknown_key":1}}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.result").value(false))

        verify(world, never()).save(any())
    }

    @Test
    fun `patch game-settings rejects out of range value`() {
        stubAdmin()
        val entity = WorldStateReadEntity(id = 1, config = linkedMapOf("npcmode" to 0))
        `when`(world.findById(1)).thenReturn(java.util.Optional.of(entity))

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
        val entity = WorldStateReadEntity(id = 1, config = linkedMapOf("npcmode" to 0))
        `when`(world.findById(1)).thenReturn(java.util.Optional.of(entity))

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
        val entity = WorldStateReadEntity(
            id = 1,
            config = linkedMapOf("a" to 1, "b" to 2, "c" to 3),
        )
        `when`(world.findById(1)).thenReturn(java.util.Optional.of(entity))
        `when`(world.save(any(WorldStateReadEntity::class.java))).thenReturn(entity)

        mockMvc().perform(
            patch("/api/admin/game-settings")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"values":{"npcmode":1}}"""),
        )
            .andExpect(status().isOk)

        // LinkedHashMap preserves insertion order: a, b, c, npcmode
        val keys = entity.config.keys.toList()
        assertEquals(listOf("a", "b", "c", "npcmode"), keys)
    }

    @Test
    fun `patch game-settings rejects string value that is not numeric`() {
        stubAdmin()
        val entity = WorldStateReadEntity(id = 1, config = linkedMapOf("npcmode" to 0))
        `when`(world.findById(1)).thenReturn(java.util.Optional.of(entity))

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
    fun `server-status updates status`() {
        stubAdmin()
        val entity = WorldStateReadEntity(id = 1, status = "OPEN")
        `when`(world.findById(1)).thenReturn(java.util.Optional.of(entity))
        `when`(world.save(any(WorldStateReadEntity::class.java))).thenReturn(entity)

        mockMvc().perform(
            post("/api/admin/server-status")
                .header("Authorization", bearer("admintok"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"status":"CLOSED"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.status").value("CLOSED"))
    }
}
