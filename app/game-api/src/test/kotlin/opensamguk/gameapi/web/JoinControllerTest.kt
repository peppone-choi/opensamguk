package opensamguk.gameapi.web

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.common.auth.GatewayPrincipal
import opensamguk.common.constants.GameConst
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.world.WorldId
import opensamguk.gameapi.config.GameApiProcessWorld
import opensamguk.gameapi.member.MemberProfile
import opensamguk.gameapi.member.MemberProfileClient
import opensamguk.gameapi.member.MemberProfileUnavailableException
import opensamguk.gameapi.owner.CommandResultClaimNpcRequestStatusReader
import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralOwnershipClassifier
import opensamguk.gameapi.owner.SelectNpcTokenRepository
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.reserve.CommandReserveService.ReserveResult
import opensamguk.gameapi.security.JwtVerifyFilter
import opensamguk.infra.entity.GameKvEntity
import opensamguk.infra.persistence.CommandResultRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
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
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class JoinControllerTest {
    private val generals = mock(GeneralReadRepository::class.java)
    private val worldStates = mock(WorldStateReadRepository::class.java)
    private val reserve = mock(CommandReserveService::class.java)
    private val gameKv = mock(GameKvReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val owners = mock(GeneralOwnerRepository::class.java)
    private val npcTokens = mock(SelectNpcTokenRepository::class.java)
    private val commandResults = mock(CommandResultRepository::class.java)
    private val memberProfiles = mock(MemberProfileClient::class.java)
    private val ownership = GeneralOwnershipClassifier(
        owners,
        generals,
        CommandResultClaimNpcRequestStatusReader(commandResults, GameApiProcessWorld(1)),
    )

    private fun anyCommand(): TurnDaemonCommand =
        any(TurnDaemonCommand::class.java) ?: TurnDaemonCommand.Pause()

    private fun captureCommand(captor: ArgumentCaptor<TurnDaemonCommand>): TurnDaemonCommand =
        captor.capture() ?: TurnDaemonCommand.Pause()

    @AfterEach
    fun clearAuth() = SecurityContextHolder.clearContext()

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(
            JoinController(generals, worldStates, reserve, gameKv, cities, ObjectMapper(), ownership, memberProfiles),
        )
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun memberProfile() = MemberProfile("계정주인", 1, "custom.jpg", 1)

    private fun principal(
        userId: Long,
        profile: GatewayPrincipal? = GatewayPrincipal(userId = userId, role = "USER"),
        memberRow: MemberProfile? = memberProfile(),
        memberUnavailable: Boolean = false,
    ): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        if (memberUnavailable) {
            `when`(memberProfiles.get(userId)).thenThrow(MemberProfileUnavailableException())
        } else {
            `when`(memberProfiles.get(userId)).thenReturn(memberRow)
        }
        profile?.let { req.setAttribute(JwtVerifyFilter.PRINCIPAL_ATTRIBUTE, it) }
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
    fun `blocks direct creation while a correlated NPC claim is still pending`() {
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(
            GeneralOwnerEntity(generalId = 10L, userId = 7L, claimRequestId = "req-claim-10"),
        )
        `when`(generals.findById(10)).thenReturn(Optional.of(GeneralReadEntity(id = 10, npcState = 2, userId = null)))
        `when`(commandResults.findResultPayload(WorldId(1), "req-claim-10")).thenReturn(null)

        mockMvc().perform(
            post("/api/join")
                .with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinJson()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("BLOCKED"))
            .andExpect(jsonPath("$.reason").value("이미 등록하셨습니다!"))

        verifyNoInteractions(reserve)
    }

    @Test
    fun `repairs only the observed released legacy owner before direct creation`() {
        seedWorld()
        val stale = GeneralOwnerEntity(
            generalId = 10L,
            userId = 7L,
            claimedAt = Instant.parse("2026-06-01T00:00:00Z"),
        )
        `when`(owners.findByUserId(7L)).thenReturn(stale, null)
        `when`(generals.findById(10)).thenReturn(Optional.of(GeneralReadEntity(id = 10, npcState = 3, userId = null)))
        `when`(owners.deleteIfUnchanged(stale)).thenReturn(1)
        `when`(generals.existsByName("조조")).thenReturn(false)
        `when`(reserve.publishImmediate(anyCommand(), eq(7))).thenReturn(ReserveResult("req-1", 0))

        mockMvc().perform(
            post("/api/join")
                .with(principal(7L))
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinJson()),
        )
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.status").value("AVAILABLE"))

        verify(owners).deleteIfUnchanged(stale)
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
        `when`(reserve.publishImmediate(anyCommand(), eq(7))).thenReturn(ReserveResult("req-1", 0))

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
        verify(reserve).publishImmediate(captureCommand(captor), eq(7))
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
        `when`(reserve.publishImmediate(anyCommand(), eq(7))).thenReturn(ReserveResult("req-inherit", 0))

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
        verify(reserve).publishImmediate(captureCommand(captor), eq(7))
        val command = captor.value as TurnDaemonCommand.MakeGeneral
        assertEquals("che_귀병", command.inheritSpecial)
        assertEquals(12, command.inheritTurntimeZone)
        assertEquals(10, command.inheritCity)
        assertEquals(listOf(3, 1, 1), command.inheritBonusStat)
        assertEquals("custom.jpg", command.picture)
        assertEquals(1, command.imgsvr)
        assertEquals("계정주인", command.ownerName)
    }

    /** 삭제된 계정 등 `users` 행이 없으면 표시 정보를 지어내지 않고 401 로 끊는다. */
    @Test
    fun `join form is rejected when the member row is gone`() {
        seedWorld(mapOf("maxgeneral" to 500, "show_img_level" to 3))
        mockMvc().perform(get("/api/join").with(principal(7L, memberRow = null)))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `join form returns 503 when gateway profile is unavailable on a cache miss`() {
        seedWorld(mapOf("maxgeneral" to 500, "show_img_level" to 3))
        mockMvc().perform(get("/api/join").with(principal(7L, memberUnavailable = true)))
            .andExpect(status().isServiceUnavailable)
    }

    @Test
    fun `join submission returns 503 without publishing when gateway profile is unavailable on a cache miss`() {
        seedWorld()
        `when`(generals.findByUserId("7")).thenReturn(null)
        `when`(generals.existsByName("조조")).thenReturn(false)

        mockMvc().perform(
            post("/api/join")
                .with(principal(7L, memberUnavailable = true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(joinJson()),
        ).andExpect(status().isServiceUnavailable)

        verifyNoInteractions(reserve)
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
