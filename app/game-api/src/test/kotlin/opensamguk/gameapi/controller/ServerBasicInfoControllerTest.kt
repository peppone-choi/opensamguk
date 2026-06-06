package opensamguk.gameapi.controller

import opensamguk.common.constants.GameConst
import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Optional

/**
 * K1 진입 — [ServerBasicInfoController] slice test (devsam j_server_basic_info 포팅). game{} 실쿼리 매핑,
 * me 해석(없으면 null), maxgeneral 부재 시 GameConst.defaultMaxGeneral 폴백을 검증한다.
 */
class ServerBasicInfoControllerTest {

    private val owners = mock(GeneralOwnerRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    private val resolver = GeneralResolver(owners, generals, nations)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(ServerBasicInfoController(resolver, world, generals, nations))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    /** world_state(id=0 싱글톤) + general/nation 카운트 시드. config 키는 인자로 주입. */
    private fun seedWorld(config: Map<String, Any?>) {
        `when`(world.findById(0)).thenReturn(
            Optional.of(
                WorldStateReadEntity(
                    id = 0, scenarioCode = "che_1010", currentYear = 200, currentMonth = 3,
                    tickSeconds = 3600, config = LinkedHashMap(config),
                ),
            ),
        )
        `when`(generals.count()).thenReturn(174L)
        `when`(generals.countByNpcStateGreaterThan(1)).thenReturn(150L) // 순수 NPC
        `when`(nations.findAll()).thenReturn(
            listOf(
                NationReadEntity(id = 0, name = "재야", color = "#000", level = 0),
                NationReadEntity(id = 1, name = "위", color = "#00f", level = 5),
                NationReadEntity(id = 2, name = "촉", color = "#0f0", level = 5),
            ),
        )
    }

    @Test
    fun `anonymous caller gets game block with real counts and me null`() {
        seedWorld(mapOf("maxgeneral" to 500, "npcmode" to 1, "isunited" to 0, "fiction" to false))

        mockMvc().perform(get("/api/server-basic-info")) // no principal
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.game.year").value(200))
            .andExpect(jsonPath("$.game.month").value(3))
            .andExpect(jsonPath("$.game.turnTerm").value(60)) // 3600 / 60
            .andExpect(jsonPath("$.game.userCnt").value(24)) // 174 - 150
            .andExpect(jsonPath("$.game.npcCnt").value(150))
            .andExpect(jsonPath("$.game.nationCnt").value(2)) // level>0 → 위·촉(재야 제외)
            .andExpect(jsonPath("$.game.maxUserCnt").value(500))
            .andExpect(jsonPath("$.game.npcMode").value(1))
            .andExpect(jsonPath("$.game.fictionMode").value("사실"))
            .andExpect(jsonPath("$.game.defaultStatTotal").value(GameConst.defaultStatTotal))
            .andExpect(jsonPath("$.me").doesNotExist())
    }

    @Test
    fun `resolved general fills me with name`() {
        seedWorld(mapOf("maxgeneral" to 500))
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "조조", nationId = 1, picture = "0001.jpg", imageServer = 0)),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 5)))

        mockMvc().perform(get("/api/server-basic-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.me.name").value("조조"))
            .andExpect(jsonPath("$.me.picture").value("0001.jpg"))
    }

    @Test
    fun `missing maxgeneral falls back to GameConst default cap (never zero)`() {
        seedWorld(emptyMap()) // config 부재

        mockMvc().perform(get("/api/server-basic-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.game.maxUserCnt").value(GameConst.defaultMaxGeneral))
            .andExpect(jsonPath("$.game.npcMode").value(0)) // 부재 → 불가
            .andExpect(jsonPath("$.game.otherTextInfo").value("표준"))
    }
}
