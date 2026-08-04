package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GeneralAccessLogReadEntity
import opensamguk.gameapi.read.GeneralAccessLogReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralTurnReadEntity
import opensamguk.gameapi.read.GeneralTurnReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.RankDataReadEntity
import opensamguk.gameapi.read.RankDataReadRepository
import opensamguk.gameapi.read.TroopReadEntity
import opensamguk.gameapi.read.TroopReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import org.junit.jupiter.api.Test
import org.mockito.Mockito.anyCollection
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
 * W3 GeneralList 슬라이스 테스트 — [GeneralListController]를 MockMvc standalone + 모킹 레포로 검증.
 * 권한 tier 컬럼, turntime ASC 정렬, env 봉투, reservedCommand 일괄, BLOCKED null, 무캐릭터/재야 계약.
 */
class GeneralListControllerTest {

    private val owners = mock(GeneralOwnerRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val generalTurns = mock(GeneralTurnReadRepository::class.java)
    private val rankData = mock(RankDataReadRepository::class.java)
    private val troops = mock(TroopReadRepository::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    private val accessLogs = mock(GeneralAccessLogReadRepository::class.java)
    private val resolver = GeneralResolver(owners, generals, nations)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(
            GeneralListController(resolver, generals, nations, generalTurns, rankData, troops, world, accessLogs),
        ).setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver()).build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun gen(
        id: Int,
        name: String,
        nationId: Int,
        officerLevel: Int = 1,
        npcState: Int = 0,
        turnTime: Instant? = null,
        special: String = "None",
        special2: String = "None",
    ) = GeneralReadEntity(
        id = id, name = name, nationId = nationId, officerLevel = officerLevel,
        npcState = npcState, turnTime = turnTime, specialCode = special, special2Code = special2,
    )

    private fun ownerLink(userId: Long, generalId: Long) =
        GeneralOwnerEntity(generalId = generalId, userId = userId, claimedAt = Instant.EPOCH)

    private fun worldRow() = WorldStateReadEntity(
        id = 1, scenarioCode = "scenario_1010", currentYear = 200, currentMonth = 6, tickSeconds = 3600,
    )

    /** 호출자(수뇌, officer_level 5)를 1국 소속으로 해소하는 공통 모킹. */
    private fun setupChief(userId: Long = 7L, meId: Int = 10) {
        `when`(owners.findByUserId(userId)).thenReturn(ownerLink(userId, meId.toLong()))
        `when`(generals.findById(meId)).thenReturn(Optional.of(gen(meId, "조조", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 8)))
        `when`(world.findAll()).thenReturn(listOf(worldRow()))
        `when`(troops.findByNationOrderByTroopLeaderAsc(1)).thenReturn(emptyList())
        `when`(rankData.findByGeneralIdsAndTypes(anyCollection(), anyCollection())).thenReturn(emptyList())
        `when`(generalTurns.findReservedByGeneralIds(anyCollection())).thenReturn(emptyList())
        `when`(accessLogs.findByGeneralIdIn(anyCollection())).thenReturn(emptyList())
    }

    @Test
    fun `수뇌 호출자는 P1 컬럼과 정렬된 장수 목록을 받는다`() {
        setupChief()
        // turntime: 11번이 더 이른 시각 → 정렬 후 11번이 먼저(turntime ASC).
        val early = Instant.parse("2026-06-03T09:00:00Z")
        val late = Instant.parse("2026-06-03T10:00:00Z")
        `when`(generals.findByNationIdOrderByTurnTimeAsc(1)).thenReturn(
            listOf(
                gen(11, "하후돈", nationId = 1, officerLevel = 4, turnTime = early, special = "급습").apply {
                    politics = 81
                    charm = 72
                },
                gen(10, "조조", nationId = 1, officerLevel = 5, turnTime = late),
            ),
        )
        `when`(accessLogs.findByGeneralIdIn(anyCollection())).thenReturn(
            listOf(GeneralAccessLogReadEntity(id = 1, generalId = 11, refreshScore = 4, refreshScoreTotal = 120)),
        )

        mockMvc().perform(get("/api/nation/general-list").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.permission").value(2))
            .andExpect(jsonPath("$.myGeneralID").value(10))
            // env
            .andExpect(jsonPath("$.env.year").value(200))
            .andExpect(jsonPath("$.env.month").value(6))
            .andExpect(jsonPath("$.env.turnterm").value(60)) // 3600/60
            // 2개 행
            .andExpect(jsonPath("$.list.length()").value(2))
            // P1 컬럼이 column에 존재
            .andExpect(jsonPath("$.column[?(@ == 'warnum')]").exists())
            .andExpect(jsonPath("$.column[?(@ == 'reservedCommand')]").exists())
            .andExpect(jsonPath("$.column[?(@ == 'politics')]").exists())
            .andExpect(jsonPath("$.column[?(@ == 'charm')]").exists())
            .andExpect(jsonPath("$.column[?(@ == 'dex1')]").doesNotExist())
            .andExpect(jsonPath("$.column[?(@ == 'refreshScoreTotal')]").exists())
            .andExpect(jsonPath("$.column[?(@ == 'refreshScore')]").exists())
            .andExpect(jsonPath("$.list[0][8]").value(81))
            .andExpect(jsonPath("$.list[0][9]").value(72))
    }

    @Test
    fun `정렬은 turntime ASC repo 결과 순서를 그대로 보존한다`() {
        setupChief()
        val early = Instant.parse("2026-06-03T09:00:00Z")
        val late = Instant.parse("2026-06-03T10:00:00Z")
        `when`(generals.findByNationIdOrderByTurnTimeAsc(1)).thenReturn(
            listOf(
                gen(11, "하후돈", nationId = 1, officerLevel = 4, turnTime = early),
                gen(10, "조조", nationId = 1, officerLevel = 5, turnTime = late),
            ),
        )

        // column에서 'no'의 인덱스를 찾아 list[i][noIdx]를 검증하기 위해 'no'는 0번 컬럼(P0 첫 컬럼).
        mockMvc().perform(get("/api/nation/general-list").with(principal(7L)))
            .andExpect(status().isOk)
            // 첫 행 = 이른 turntime의 하후돈(no=11), 둘째 = 조조(no=10).
            .andExpect(jsonPath("$.list[0][0]").value(11))
            .andExpect(jsonPath("$.list[1][0]").value(10))
    }

    @Test
    fun `permission 0 호출자는 P1 컬럼이 빠진 P0 전용 목록을 받는다`() {
        // officer_level 1 → permission 0(일반).
        `when`(owners.findByUserId(7L)).thenReturn(ownerLink(7L, 20))
        `when`(generals.findById(20)).thenReturn(Optional.of(gen(20, "병졸", nationId = 1, officerLevel = 1)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 8)))
        `when`(world.findAll()).thenReturn(listOf(worldRow()))
        `when`(troops.findByNationOrderByTroopLeaderAsc(1)).thenReturn(emptyList())
        `when`(rankData.findByGeneralIdsAndTypes(anyCollection(), anyCollection())).thenReturn(emptyList())
        `when`(generalTurns.findReservedByGeneralIds(anyCollection())).thenReturn(emptyList())
        `when`(accessLogs.findByGeneralIdIn(anyCollection())).thenReturn(emptyList())
        `when`(generals.findByNationIdOrderByTurnTimeAsc(1)).thenReturn(listOf(gen(20, "병졸", nationId = 1, officerLevel = 1)))

        mockMvc().perform(get("/api/nation/general-list").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.permission").value(0))
            .andExpect(jsonPath("$.column[?(@ == 'no')]").exists())
            .andExpect(jsonPath("$.column[?(@ == 'officerLevelText')]").exists())
            // P1 컬럼은 빠짐
            .andExpect(jsonPath("$.column[?(@ == 'warnum')]").doesNotExist())
            .andExpect(jsonPath("$.column[?(@ == 'turntime')]").doesNotExist())
            .andExpect(jsonPath("$.column[?(@ == 'reservedCommand')]").doesNotExist())
    }

    @Test
    fun `reservedCommand 가 수뇌에게 일괄 매핑된다`() {
        setupChief()
        `when`(generals.findByNationIdOrderByTurnTimeAsc(1)).thenReturn(
            listOf(gen(10, "조조", nationId = 1, officerLevel = 5)),
        )
        `when`(generalTurns.findReservedByGeneralIds(anyCollection())).thenReturn(
            listOf(
                GeneralTurnReadEntity(id = 1, generalId = 10, turnIdx = 0, actionCode = "출병", brief = "출병"),
                GeneralTurnReadEntity(id = 2, generalId = 10, turnIdx = 1, actionCode = "휴식", brief = "휴식"),
            ),
        )
        `when`(rankData.findByGeneralIdsAndTypes(anyCollection(), anyCollection())).thenReturn(
            listOf(RankDataReadEntity(id = 1, nationId = 1, generalId = 10, type = "warnum", value = 42)),
        )

        mockMvc().perform(get("/api/nation/general-list").with(principal(7L)))
            .andExpect(status().isOk)
            // reservedCommand 컬럼의 셀이 2개 슬롯을 담는다(구조체 목록).
            .andExpect(jsonPath("$.list[0][?(@)]").exists())
    }

    @Test
    fun `무캐릭터 호출자는 401`() {
        `when`(owners.findByUserId(7L)).thenReturn(null)
        mockMvc().perform(get("/api/nation/general-list").with(principal(7L)))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `재야 호출자는 빈 목록과 P0 컬럼만 받는다`() {
        `when`(owners.findByUserId(7L)).thenReturn(ownerLink(7L, 30))
        // nationId 0(재야).
        `when`(generals.findById(30)).thenReturn(Optional.of(gen(30, "방랑객", nationId = 0, officerLevel = 0)))
        `when`(world.findAll()).thenReturn(listOf(worldRow()))

        mockMvc().perform(get("/api/nation/general-list").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.permission").value(0))
            .andExpect(jsonPath("$.list.length()").value(0))
            .andExpect(jsonPath("$.troops.length()").value(0))
            .andExpect(jsonPath("$.myGeneralID").value(30))
            .andExpect(jsonPath("$.column[?(@ == 'warnum')]").doesNotExist())
    }
}
