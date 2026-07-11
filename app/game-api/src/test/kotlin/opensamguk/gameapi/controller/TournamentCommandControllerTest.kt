package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.infra.entity.GameKvEntity
import opensamguk.logic.tournament.TournamentEntry
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import kotlin.test.Test
import kotlin.test.assertEquals

class TournamentCommandControllerTest {
    private val gameKv = mock(GameKvReadRepository::class.java)
    private val reserve = mock(CommandReserveService::class.java)
    private val resolver = mock(GeneralResolver::class.java)
    private val objectMapper = ObjectMapper()
    private val controller = TournamentController(gameKv, objectMapper, reserve, resolver)
    private val mvc = MockMvcBuilders.standaloneSetup(controller)
        .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
        .build()

    @Test
    fun `start posts tournamentStart through command reserve service`() {
        `when`(resolver.resolve(7L)).thenReturn(resolvedGeneral(id = 10, officerLevel = 5))
        `when`(
            reserve.reserve(
                generalId = 10,
                actionCode = "tournamentStart",
                turnIdx = 0,
                argJson = """{"type":2}""",
            ),
        ).thenReturn(CommandReserveService.ReserveResult("req-start", 0))

        val response = controller.start(7L, 10, TournamentStartRequest(type = 2))

        assertEquals(202, response.statusCode.value())
        assertEquals("AVAILABLE", response.body!!.status)
        assertEquals("req-start", response.body!!.requestId)
        verify(reserve).reserve(10, "tournamentStart", 0, """{"type":2}""")
    }

    @Test
    fun `start accepts frontend empty body contract as default tournament type zero`() {
        `when`(resolver.resolve(7L)).thenReturn(resolvedGeneral(id = 10, officerLevel = 5))
        `when`(reserve.reserve(10, "tournamentStart", 0, """{"type":0}"""))
            .thenReturn(CommandReserveService.ReserveResult("req-start", 0))

        mvc.perform(
            post("/api/tournament/start")
                .with(principal(7L))
                .param("generalId", "10")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.requestId").value("req-start"))

        verify(reserve).reserve(10, "tournamentStart", 0, """{"type":0}""")
    }

    @Test
    fun `reset posts tournamentReset through command reserve service`() {
        `when`(resolver.resolve(7L)).thenReturn(resolvedGeneral(id = 10, officerLevel = 5))
        `when`(reserve.reserve(10, "tournamentReset", 0, null))
            .thenReturn(CommandReserveService.ReserveResult("req-reset", 0))

        val response = controller.reset(7L, 10)

        assertEquals(202, response.statusCode.value())
        assertEquals("AVAILABLE", response.body!!.status)
        assertEquals("req-reset", response.body!!.requestId)
        verify(reserve).reserve(10, "tournamentReset", 0, null)
    }

    @Test
    fun `start denies a requested general id not owned by authenticated principal`() {
        `when`(resolver.resolve(7L)).thenReturn(resolvedGeneral(id = 10, officerLevel = 5))

        val response = controller.start(7L, 11, TournamentStartRequest(type = 2))

        assertEquals(403, response.statusCode.value())
        verifyNoInteractions(reserve)
    }

    @Test
    fun `reset denies a requested general id not owned by authenticated principal`() {
        `when`(resolver.resolve(7L)).thenReturn(resolvedGeneral(id = 10, officerLevel = 5))

        val response = controller.reset(7L, 11)

        assertEquals(403, response.statusCode.value())
        verifyNoInteractions(reserve)
    }

    @Test
    fun `start denies non-chief officer with legacy permission message`() {
        `when`(resolver.resolve(7L)).thenReturn(resolvedGeneral(id = 10, officerLevel = 2))

        val response = controller.start(7L, 10, TournamentStartRequest(type = 2))

        assertEquals(403, response.statusCode.value())
        assertEquals("BLOCKED", response.body!!.status)
        assertEquals("권한이 부족합니다. 수뇌부가 아닙니다.", response.body!!.reason)
        verifyNoInteractions(reserve)
    }

    @Test
    fun `reset denies non-chief officer with legacy permission message`() {
        `when`(resolver.resolve(7L)).thenReturn(resolvedGeneral(id = 10, officerLevel = 2))

        val response = controller.reset(7L, 10)

        assertEquals(403, response.statusCode.value())
        assertEquals("BLOCKED", response.body!!.status)
        assertEquals("권한이 부족합니다. 수뇌부가 아닙니다.", response.body!!.reason)
        verifyNoInteractions(reserve)
    }

    @Test
    fun `tournament read includes admin page entries and matches aliases`() {
        val entries = listOf(
            TournamentEntry(
                id = 10,
                npc = 0,
                name = "유비",
                leadership = 80,
                strength = 70,
                intel = 60,
                level = 1,
                group = 20,
                groupNo = 0,
                win = 1,
                seq = 1,
            ),
            TournamentEntry(
                id = 11,
                npc = 0,
                name = "조조",
                leadership = 90,
                strength = 80,
                intel = 70,
                level = 1,
                group = 20,
                groupNo = 1,
                win = 0,
                seq = 2,
            ),
        )
        `when`(gameKv.findByTableAndNamespaceAndKey("game_env", "game_env", "tournament_entries"))
            .thenReturn(GameKvEntity("game_env", "game_env", "tournament_entries", objectMapper.writeValueAsString(entries)))

        mvc.perform(get("/api/tournament"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.entries[0].generalId").value(10))
            .andExpect(jsonPath("$.entries[0].generalName").value("유비"))
            .andExpect(jsonPath("$.matches[0].attackerId").value(10))
            .andExpect(jsonPath("$.matches[0].attackerName").value("유비"))
            .andExpect(jsonPath("$.matches[0].defenderId").value(11))
            .andExpect(jsonPath("$.matches[0].winnerId").value(10))
            .andExpect(jsonPath("$.matches[0].status").value("FINISHED"))
    }

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun resolvedGeneral(id: Int, officerLevel: Int): GeneralResolver.ResolvedGeneral {
        val general = GeneralReadEntity(id = id, name = "유비", nationId = 1, officerLevel = officerLevel)
        return GeneralResolver.ResolvedGeneral(
            general = general,
            officerLevel = officerLevel,
            permission = GeneralResolver.derivePermission(officerLevel),
            nationId = general.nationId,
            nationLevel = 1,
        )
    }
}
