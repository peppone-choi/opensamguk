package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.read.NationEnvReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.entity.NationEnvEntity
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
 * F2 Wave 1 slice test for [FrontInfoController] — the §3 GameInfo + identity envelope. Asserts the
 * anonymous (no character) header-only shape, the resolved-general gating surface, and the `?generalId=`
 * transition fallback.
 */
class FrontInfoControllerTest {

    private val owners = mock(GeneralOwnerRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    private val ranks = mock(opensamguk.gameapi.read.RankDataReadRepository::class.java)
    private val auctions = mock(opensamguk.gameapi.read.AuctionCountReadRepository::class.java)
    private val votePolls = mock(opensamguk.gameapi.read.VotePollReadRepository::class.java)
    private val votes = mock(opensamguk.gameapi.read.VoteReadRepository::class.java)
    private val troops = mock(opensamguk.gameapi.read.TroopReadRepository::class.java)
    private val generalTurns = mock(opensamguk.gameapi.read.GeneralTurnReadRepository::class.java)
    // nation_env(V3) read mock — 스텁 미설정 시 null → notice null(기존 BLOCKED 동작 보존).
    private val nationEnv = mock(NationEnvReadRepository::class.java)
    private val objectMapper = ObjectMapper()
    private val resolver = GeneralResolver(owners, generals, nations)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(
            FrontInfoController(resolver, world, generals, nations, cities, ranks, auctions, votePolls, votes, troops, generalTurns, nationEnv, objectMapper),
        )
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun seedWorld(config: Map<String, Any?> = emptyMap()) {
        `when`(world.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "che_1010",
                    currentYear = 200,
                    currentMonth = 3,
                    tickSeconds = 3600,
                    config = config,
                ),
            ),
        )
        `when`(generals.count()).thenReturn(174L)
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 1, name = "위", color = "#00f")))
        `when`(cities.count()).thenReturn(42L)
        `when`(generals.countByNpcState(2)).thenReturn(150L)
        `when`(generals.countByNpcState(1)).thenReturn(10L)
    }

    @Test
    fun `anonymous caller gets header-only front-info with hasGeneral false`() {
        seedWorld()

        mockMvc().perform(get("/api/front-info")) // no principal, no generalId
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.global.year").value(200))
            .andExpect(jsonPath("$.global.month").value(3))
            .andExpect(jsonPath("$.global.turnterm").value(60)) // 3600s / 60
            .andExpect(jsonPath("$.global.generalCount").value(174))
            .andExpect(jsonPath("$.general.hasGeneral").value(false))
            .andExpect(jsonPath("$.nation").doesNotExist())
            .andExpect(jsonPath("$.city").doesNotExist())
    }

    @Test
    fun `global autorunUser preserves legacy limit and option shape`() {
        seedWorld(
            mapOf(
                "autorun_user" to mapOf(
                    "limit_minutes" to 120,
                    "options" to linkedMapOf(
                        "develop" to 1,
                        "recruit" to 0,
                        "recruit_high" to 2,
                    ),
                ),
            ),
        )

        mockMvc().perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.autorunUser.limit_minutes").value(120))
            .andExpect(jsonPath("$.global.autorunUser.options.develop").value(1))
            .andExpect(jsonPath("$.global.autorunUser.options.recruit").value(0))
            .andExpect(jsonPath("$.global.autorunUser.options.recruit_high").value(2))
    }

    @Test
    fun `global exposes registration mode gates for unowned character UI`() {
        seedWorld(
            mapOf(
                "npcmode" to 2,
                "block_general_create" to 1,
                "maxgeneral" to 500,
            ),
        )

        mockMvc().perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.npcMode").value(2))
            .andExpect(jsonPath("$.global.blockGeneralCreate").value(1))
            .andExpect(jsonPath("$.global.generalCntLimit").value(500))
    }

    @Test
    fun `resolved general exposes gating surface (permission derived from officer level)`() {
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "순욱", nationId = 1, cityId = 5, officerLevel = 5)),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.general.hasGeneral").value(true))
            .andExpect(jsonPath("$.general.generalId").value(10))
            .andExpect(jsonPath("$.general.officerLevel").value(5))
            .andExpect(jsonPath("$.general.permission").value(2))
            .andExpect(jsonPath("$.general.showSecret").value(true))
            .andExpect(jsonPath("$.nation.id").value(1))
            .andExpect(jsonPath("$.nation.level").value(7))
            .andExpect(jsonPath("$.city.name").value("허창"))
    }

    @Test
    fun `nation notice surfaces from nation_env nationNotice on the main front-info`() {
        // W1-O 바퀴50 — 데몬이 nation_env에 쓴 nationNotice{msg}(국가방침)를 buildNation이 read → nation.notice 언블록
        // (PageFront.vue:32 v-html=notice.msg 등가). loop49 nation_env read 채널 재사용.
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "순욱", nationId = 1, cityId = 5, officerLevel = 5)),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))
        `when`(nationEnv.findByNamespaceAndKey(1, "nationNotice")).thenReturn(
            NationEnvEntity(namespace = 1, key = "nationNotice", value = """{"date":"200-3","msg":"천하통일을 위하여","author":"순욱","authorID":10}"""),
        )

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation.notice").value("천하통일을 위하여"))
    }

    @Test
    fun `nation notice is null when nation_env has no nationNotice`() {
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "순욱", nationId = 1, cityId = 5, officerLevel = 5)),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))
        // nationEnv 미스텁 → null → notice null(날조 금지).

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation.notice").doesNotExist())
    }

    @Test
    fun `generalId query param resolves the general as a transition fallback`() {
        seedWorld()
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "관우", nationId = 0, officerLevel = 1)),
        )

        mockMvc().perform(get("/api/front-info?generalId=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.general.hasGeneral").value(true))
            .andExpect(jsonPath("$.general.generalId").value(10))
            .andExpect(jsonPath("$.general.permission").value(0)) // officer_level 1 → 일반
    }

    // ── W0-2(P1-002) lastVote / lastVoteID ───────────────────────────────────────────────────────────

    @Test
    fun `global lastVote exposes the newest open poll in VoteInfo shape`() {
        seedWorld()
        `when`(votePolls.findFirstByOrderByIdDesc()).thenReturn(
            opensamguk.gameapi.read.VotePollReadEntity(
                id = 3,
                title = "차기 천하통일 예상 국가는?",
                multipleOptions = 1,
                openerName = "운영자",
                startAt = Instant.parse("2026-06-01T00:00:00Z"),
                endAt = Instant.now().plusSeconds(3600),
                options = linkedMapOf("0" to "위", "1" to "촉"),
            ),
        )

        mockMvc().perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.lastVoteID").value(3))
            .andExpect(jsonPath("$.global.lastVote.id").value(3))
            .andExpect(jsonPath("$.global.lastVote.title").value("차기 천하통일 예상 국가는?"))
            .andExpect(jsonPath("$.global.lastVote.opener").value("운영자"))
            .andExpect(jsonPath("$.global.lastVote.startDate").value("2026-06-01 00:00:00"))
            .andExpect(jsonPath("$.global.lastVote.options[0]").value("위"))
            .andExpect(jsonPath("$.global.lastVote.options[1]").value("촉"))
    }

    @Test
    fun `global lastVote is null for an expired poll but lastVoteID stays`() {
        // PHP GetFrontInfo.php:186-188 — endDate가 지났으면 lastVote=null, lastVoteID는 raw 유지.
        seedWorld()
        `when`(votePolls.findFirstByOrderByIdDesc()).thenReturn(
            opensamguk.gameapi.read.VotePollReadEntity(
                id = 2,
                title = "지난 설문",
                endAt = Instant.now().minusSeconds(3600),
            ),
        )

        mockMvc().perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.lastVoteID").value(2))
            .andExpect(jsonPath("$.global.lastVote").doesNotExist())
    }

    // ── W0-2(P1-001) onlineNations(방어적 config read) ───────────────────────────────────────────────

    @Test
    fun `global onlineNations reads the game_env online_nation CSV when present`() {
        seedWorld(mapOf("online_nation" to "위, 촉, 오"))

        mockMvc().perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.onlineNations").value("위, 촉, 오"))
    }

    @Test
    fun `global onlineNations is null when the daemon has not populated it`() {
        seedWorld()

        mockMvc().perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.onlineNations").doesNotExist())
    }

    // ── W0-2(P1-002) aux.myLastVote ──────────────────────────────────────────────────────────────────

    @Test
    fun `aux exposes the caller's last vote id`() {
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "순욱", nationId = 1, cityId = 5, officerLevel = 5)),
        )
        `when`(votes.findFirstByGeneralIdOrderByVoteIdDesc(10)).thenReturn(
            opensamguk.gameapi.read.VoteReadEntity(id = 99, voteId = 3, generalId = 10),
        )

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.aux.myLastVote").value(3))
    }

    @Test
    fun `aux myLastVote is null without a vote history and for anonymous callers`() {
        seedWorld()

        mockMvc().perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.aux.myLastVote").doesNotExist())
    }

    // ── W0-2(P1-005) troopInfo(부대 정보 합성) ───────────────────────────────────────────────────────

    @Test
    fun `general troopInfo synthesizes leader city and reserved 5 turns`() {
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "순욱", nationId = 1, cityId = 5, officerLevel = 5, troopId = 20)),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))
        `when`(troops.findById(20)).thenReturn(
            Optional.of(opensamguk.gameapi.read.TroopReadEntity(troopLeader = 20, nation = 1, name = "선봉대")),
        )
        `when`(generals.findById(20)).thenReturn(
            Optional.of(GeneralReadEntity(id = 20, name = "하후돈", nationId = 1, cityId = 8, officerLevel = 4)),
        )
        `when`(generalTurns.findByGeneralIdOrderByTurnIdxAsc(20)).thenReturn(
            listOf(
                opensamguk.gameapi.read.GeneralTurnReadEntity(
                    id = 1, generalId = 20, turnIdx = 0, actionCode = "che_출병",
                    arg = linkedMapOf("destCityID" to 3), brief = "출병",
                ),
                // turn_idx 5 이상은 PHP `turn_idx < 5` 게이트로 제외.
                opensamguk.gameapi.read.GeneralTurnReadEntity(
                    id = 2, generalId = 20, turnIdx = 5, actionCode = "휴식", brief = "휴식",
                ),
            ),
        )

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.general.troopInfo.name").value("선봉대"))
            .andExpect(jsonPath("$.general.troopInfo.leader.city").value(8))
            .andExpect(jsonPath("$.general.troopInfo.leader.reservedCommand.length()").value(1))
            .andExpect(jsonPath("$.general.troopInfo.leader.reservedCommand[0].action").value("che_출병"))
            .andExpect(jsonPath("$.general.troopInfo.leader.reservedCommand[0].brief").value("출병"))
            .andExpect(jsonPath("$.general.troopInfo.leader.reservedCommand[0].arg.destCityID").value(3))
    }

    @Test
    fun `general troopInfo is null when the leader has no reserved turns`() {
        // PHP GetFrontInfo.php:474-476 — 예약 0행이면 troopInfo 키 자체 미기재.
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "순욱", nationId = 1, cityId = 5, officerLevel = 5, troopId = 20)),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))
        `when`(troops.findById(20)).thenReturn(
            Optional.of(opensamguk.gameapi.read.TroopReadEntity(troopLeader = 20, nation = 1, name = "선봉대")),
        )
        `when`(generals.findById(20)).thenReturn(
            Optional.of(GeneralReadEntity(id = 20, name = "하후돈", nationId = 1, cityId = 8, officerLevel = 4)),
        )
        `when`(generalTurns.findByGeneralIdOrderByTurnIdxAsc(20)).thenReturn(emptyList())

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.general.troopInfo").doesNotExist())
    }
}
