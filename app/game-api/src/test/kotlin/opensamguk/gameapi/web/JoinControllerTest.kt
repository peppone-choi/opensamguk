package opensamguk.gameapi.web

import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional

class JoinControllerTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val worldStates = mock(WorldStateReadRepository::class.java)
    private val reserve = mock(CommandReserveService::class.java)

    @AfterEach
    fun clearAuth() = SecurityContextHolder.clearContext()

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(JoinController(generals, worldStates, reserve))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun seedWorld(config: Map<String, Any?> = mapOf("maxgeneral" to 500)) {
        `when`(worldStates.findById(0)).thenReturn(
            Optional.of(
                WorldStateReadEntity(
                    id = 0,
                    scenarioCode = "che_1010",
                    currentYear = 200,
                    currentMonth = 1,
                    tickSeconds = 3600,
                    config = LinkedHashMap(config),
                ),
            ),
        )
        `when`(generals.countByNpcStateLessThan(2)).thenReturn(10L)
    }

    private fun joinJson(name: String = "조조") = """
        {
          "name": "$name",
          "leadership": 55,
          "strength": 55,
          "intel": 55,
          "character": "Random",
          "pic": true
        }
    """.trimIndent()

    @Test
    fun `blocks direct general creation when PHP block_general_create bit 1 is set`() {
        seedWorld(mapOf("block_general_create" to 1, "maxgeneral" to 500))

        mockMvc().perform(
            post("/api/join")
                .with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinJson()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("BLOCKED"))
            .andExpect(jsonPath("$.reason").value("장수 직접 생성이 불가능한 모드입니다."))

        verifyNoInteractions(reserve)
    }

    @Test
    fun `blocks registration when npc less-than two count reaches maxgeneral`() {
        seedWorld(mapOf("maxgeneral" to 10))
        `when`(generals.findByUserId("7")).thenReturn(null)
        `when`(generals.existsByName("조조")).thenReturn(false)

        mockMvc().perform(
            post("/api/join")
                .with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinJson()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("BLOCKED"))
            .andExpect(jsonPath("$.reason").value("더이상 등록할 수 없습니다!"))

        verifyNoInteractions(reserve)
    }

    @Test
    fun `blocks names wider than PHP mb_strwidth eighteen`() {
        seedWorld()
        `when`(generals.findByUserId("7")).thenReturn(null)
        `when`(generals.existsByName("가나다라마바사아자차")).thenReturn(false)

        mockMvc().perform(
            post("/api/join")
                .with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinJson("가나다라마바사아자차")),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("BLOCKED"))
            .andExpect(jsonPath("$.reason").value("이름이 유효하지 않습니다. 다시 가입해주세요!"))

        verifyNoInteractions(reserve)
    }
}
