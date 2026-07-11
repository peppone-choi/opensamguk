package opensamguk.gameapi.web

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.auth.GatewayProfileClaims
import opensamguk.common.constants.GameConst
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.CommandReserveService.ReserveResult
import opensamguk.gameapi.security.JwtVerifyFilter
import opensamguk.infra.entity.GameKvEntity
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional
import kotlin.test.assertEquals

class JoinControllerTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val worldStates = mock(WorldStateReadRepository::class.java)
    private val reserve = mock(CommandReserveService::class.java)
    private val gameKv = mock(GameKvReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)

    private fun anyCommand(): TurnDaemonCommand =
        any(TurnDaemonCommand::class.java) ?: TurnDaemonCommand.Pause()

    private fun captureCommand(captor: ArgumentCaptor<TurnDaemonCommand>): TurnDaemonCommand =
        captor.capture() ?: TurnDaemonCommand.Pause()

    @AfterEach
    fun clearAuth() = SecurityContextHolder.clearContext()

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(
            JoinController(generals, worldStates, reserve, gameKv, cities, ObjectMapper()),
        )
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(
        userId: Long,
        profile: GatewayProfileClaims? = GatewayProfileClaims(
            userId = userId,
            username = "owner",
            role = "USER",
            nickname = "계정주인",
            grade = 1,
            picture = "custom.jpg",
            imageServer = 1,
        ),
    ): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        profile?.let { req.setAttribute(JwtVerifyFilter.PROFILE_ATTRIBUTE, it) }
        req
    }

    private fun seedWorld(config: Map<String, Any?> = mapOf("maxgeneral" to 500)) {
        `when`(worldStates.findById(1)).thenReturn(Optional.empty())
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
          "politics": 55,
          "charm": 55,
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

    @Test
    fun `accepts five-stat total cap and publishes politics charm in makeGeneral command`() {
        seedWorld()
        `when`(generals.findByUserId("7")).thenReturn(null)
        `when`(generals.existsByName("조조")).thenReturn(false)
        `when`(reserve.publishImmediate(anyCommand())).thenReturn(ReserveResult("req-1", 0))

        mockMvc().perform(
            post("/api/join")
                .with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "조조",
                      "leadership": 55,
                      "strength": 55,
                      "intel": 55,
                      "politics": 60,
                      "charm": 50,
                      "character": "Random",
                      "pic": true
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))
            .andExpect(jsonPath("$.requestId").value("req-1"))

        val captor = ArgumentCaptor.forClass(TurnDaemonCommand::class.java)
        verify(reserve).publishImmediate(captureCommand(captor))
        val command = captor.value as TurnDaemonCommand.MakeGeneral
        assertEquals(60, command.politics)
        assertEquals(50, command.charm)
    }

    @Test
    fun `blocks five-stat totals above default cap`() {
        seedWorld()
        `when`(generals.findByUserId("7")).thenReturn(null)
        `when`(generals.existsByName("조조")).thenReturn(false)

        mockMvc().perform(
            post("/api/join")
                .with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "조조",
                      "leadership": 80,
                      "strength": 80,
                      "intel": 40,
                      "politics": 40,
                      "charm": 40,
                      "character": "Random",
                      "pic": true
                    }
                    """.trimIndent(),
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("BLOCKED"))
            .andExpect(jsonPath("$.reason").value("능력치가 ${GameConst.defaultStatTotal}을 넘어섰습니다. 다시 가입해주세요!"))

        verifyNoInteractions(reserve)
    }

    @Test
    fun `publishes all PHP join inheritance options instead of dropping them`() {
        seedWorld()
        `when`(generals.findByUserId("7")).thenReturn(null)
        `when`(generals.existsByName("조조")).thenReturn(false)
        `when`(cities.existsById(10)).thenReturn(true)
        `when`(
            gameKv.findByTableAndNamespaceAndKey("inheritance", "inheritance_7", "previous"),
        ).thenReturn(GameKvEntity("inheritance", "inheritance_7", "previous", "[20000,null]"))
        `when`(reserve.publishImmediate(anyCommand())).thenReturn(ReserveResult("req-inherit", 0))

        mockMvc().perform(
            post("/api/join")
                .with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "name": "조조",
                      "leadership": 55,
                      "strength": 55,
                      "intel": 55,
                      "politics": 50,
                      "charm": 50,
                      "character": "Random",
                      "pic": true,
                      "picture": "forged.jpg",
                      "imgsvr": 0,
                      "inheritSpecial": "che_귀병",
                      "inheritTurntimeZone": 12,
                      "inheritCity": 10,
                      "inheritBonusStat": [3, 1, 1]
                    }
                    """.trimIndent(),
                ),
        ).andExpect(status().isAccepted)

        val captor = ArgumentCaptor.forClass(TurnDaemonCommand::class.java)
        verify(reserve).publishImmediate(captureCommand(captor))
        val command = captor.value as TurnDaemonCommand.MakeGeneral
        assertEquals("che_귀병", command.inheritSpecial)
        assertEquals(12, command.inheritTurntimeZone)
        assertEquals(10, command.inheritCity)
        assertEquals(listOf(3, 1, 1), command.inheritBonusStat)
        assertEquals("custom.jpg", command.picture)
        assertEquals(1, command.imgsvr)
        assertEquals("계정주인", command.ownerName)
    }

    @Test
    fun `join form exposes live inheritance catalog points cities and resolved member portrait`() {
        seedWorld(mapOf("maxgeneral" to 500, "show_img_level" to 3))
        `when`(
            gameKv.findByTableAndNamespaceAndKey("inheritance", "inheritance_7", "previous"),
        ).thenReturn(GameKvEntity("inheritance", "inheritance_7", "previous", "[12345,null]"))
        `when`(cities.findAll()).thenReturn(
            listOf(CityReadEntity(id = 10, name = "낙양", region = 2)),
        )

        mockMvc().perform(get("/api/join").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.member.name").value("계정주인"))
            .andExpect(jsonPath("$.member.picture").value("custom.jpg"))
            .andExpect(jsonPath("$.member.imageServer").value(1))
            .andExpect(jsonPath("$.member.canUsePicture").value(true))
            .andExpect(jsonPath("$.inheritTotalPoint").value(12345))
            .andExpect(jsonPath("$.inheritCosts.special").value(6000))
            .andExpect(jsonPath("$.turnTermMinutes").value(60))
            .andExpect(jsonPath("$.cities[0].name").value("낙양"))
            .andExpect(jsonPath("$.cities[0].region").value("중원"))
            .andExpect(jsonPath("$.availableSpecialWar.che_귀병.title").value("귀병"))
    }

    @Test
    fun `join requires a verified gateway profile and never falls back to the game database`() {
        seedWorld()

        mockMvc().perform(get("/api/join").with(principal(7L, profile = null)))
            .andExpect(status().isUnauthorized)
    }
}
