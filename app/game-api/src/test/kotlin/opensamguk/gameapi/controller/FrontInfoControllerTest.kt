package opensamguk.gameapi.controller

import jakarta.servlet.http.Cookie
import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralAccessLogReadEntity
import opensamguk.gameapi.read.GeneralAccessLogReadRepository
import opensamguk.gameapi.read.LogFeedReadRepository
import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.read.NationEnvReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.ScenarioTitleResolver
import opensamguk.gameapi.read.WorldLogReadEntity
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import org.mockito.ArgumentMatchers.anyInt
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
    private val accessLogs = mock(GeneralAccessLogReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    private val ranks = mock(opensamguk.gameapi.read.RankDataReadRepository::class.java)
    private val auctions = mock(opensamguk.gameapi.read.AuctionCountReadRepository::class.java)
    private val votePolls = mock(opensamguk.gameapi.read.VotePollReadRepository::class.java)
    private val votes = mock(opensamguk.gameapi.read.VoteReadRepository::class.java)
    private val troops = mock(opensamguk.gameapi.read.TroopReadRepository::class.java)
    private val generalTurns = mock(opensamguk.gameapi.read.GeneralTurnReadRepository::class.java)
    private val logFeeds = mock(LogFeedReadRepository::class.java)
    // nation_env(V3) read mock — 스텁 미설정 시 null → notice null(기존 BLOCKED 동작 보존).
    private val nationEnv = mock(NationEnvReadRepository::class.java)
    private val objectMapper = ObjectMapper()
    private val resolver = GeneralResolver(owners, generals, nations)

    private fun mockMvc(
        serverName: String = "",
        serverGeneration: String = "",
        serverId: String = "",
    ): MockMvc =
        MockMvcBuilders.standaloneSetup(
            FrontInfoController(
                resolver,
                world,
                generals,
                nations,
                cities,
                ranks,
                auctions,
                votePolls,
                votes,
                troops,
                generalTurns,
                logFeeds,
                nationEnv,
                objectMapper,
                ScenarioTitleResolver(),
                serverName,
                serverGeneration,
                serverId,
                accessLogs,
            ),
        )
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun seedWorld(
        config: Map<String, Any?> = emptyMap(),
        startTime: Instant? = null,
        scenarioCode: String = "che_1010",
        phase: Int = 1,
    ) {
        `when`(world.findAll()).thenReturn(
            listOf(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = scenarioCode,
                    currentYear = 200,
                    currentMonth = 3,
                    currentPhase = phase,
                    tickSeconds = 3600,
                    startTime = startTime,
                    config = config,
                ),
            ),
        )
        `when`(generals.count()).thenReturn(174L)
        `when`(nations.findAll()).thenReturn(listOf(NationReadEntity(id = 1, name = "위", color = "#00f")))
        `when`(cities.count()).thenReturn(42L)
        `when`(generals.countByNpcState(2)).thenReturn(150L)
        `when`(generals.countByNpcState(1)).thenReturn(10L)
        `when`(generals.countByNpcStateGreaterThan(0)).thenReturn(160L)
        `when`(logFeeds.findGlobalHistorySince(anyInt(), anyInt())).thenReturn(emptyList())
        `when`(logFeeds.findGlobalActionSince(anyInt(), anyInt())).thenReturn(emptyList())
        `when`(logFeeds.findGeneralActionSince(anyInt(), anyInt(), anyInt())).thenReturn(emptyList())
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
            .andExpect(jsonPath("$.global.tournamentTermMinutes").value(60))
            .andExpect(jsonPath("$.global.generalCount").value(174))
            .andExpect(jsonPath("$.global.npcCount").value(160))
            .andExpect(jsonPath("$.global.npcModeText").value("불가능"))
            .andExpect(jsonPath("$.global.npcSummaryText").value("NPC 160명, 상성: 표준 사실"))
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
            .andExpect(jsonPath("$.global.otherSettingText").value("자율행동"))
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
            .andExpect(jsonPath("$.global.npcModeText").value("선택 생성"))
            .andExpect(jsonPath("$.global.blockGeneralCreate").value(1))
            .andExpect(jsonPath("$.global.generalCntLimit").value(500))
    }

    @Test
    fun `global preserves canonical public server ID s1 from environment`() {
        seedWorld()

        mockMvc(serverName = "통일 서버", serverGeneration = "7", serverId = "s1")
            .perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.serverName").value("통일 서버"))
            .andExpect(jsonPath("$.global.generation").value(7))
            .andExpect(jsonPath("$.global.serverCnt").value(7))
            .andExpect(jsonPath("$.global.serverId").value("s1"))
    }

    @Test
    fun `global preserves canonical public server ID pep from environment`() {
        seedWorld()

        mockMvc(serverId = "pep")
            .perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.serverId").value("pep"))
    }

    @Test
    fun `global omits reserved and noncanonical server ID configuration`() {
        seedWorld()

        listOf("Pep", "all", "main", "join", "a".repeat(49)).forEach { serverId ->
            mockMvc(serverId = serverId)
                .perform(get("/api/front-info"))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.global.serverId").doesNotExist())
        }
    }

    @Test
    fun `global resolves committed scenario resource title before falling back to raw code`() {
        seedWorld(scenarioCode = "scenario_1021")

        mockMvc().perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.scenario").value("scenario_1021"))
            .andExpect(jsonPath("$.global.scenarioText").value("【역사모드2-2】 반동탁연합 결성(정사)"))
    }

    @Test
    fun `global exposes the current ten-day phase from world state`() {
        seedWorld(phase = 2)

        mockMvc().perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.turnPhase").value(2))
            .andExpect(jsonPath("$.global.turnPhaseText").value("중순"))
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
        `when`(accessLogs.findByGeneralId(10)).thenReturn(
            GeneralAccessLogReadEntity(id = 1, generalId = 10, refreshScore = 6, refreshScoreTotal = 142),
        )

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.general.hasGeneral").value(true))
            .andExpect(jsonPath("$.general.generalId").value(10))
            .andExpect(jsonPath("$.general.officerLevel").value(5))
            .andExpect(jsonPath("$.general.permission").value(2))
            .andExpect(jsonPath("$.general.showSecret").value(true))
            .andExpect(jsonPath("$.general.refreshScore").value(6))
            .andExpect(jsonPath("$.general.refreshScoreTotal").value(142))
            .andExpect(jsonPath("$.nation.id").value(1))
            .andExpect(jsonPath("$.nation.level").value(7))
            .andExpect(jsonPath("$.city.name").value("허창"))
            .andExpect(jsonPath("$.city.nationName").value("위"))
            .andExpect(jsonPath("$.city.nationColor").value("#00f"))
    }

    @Test
    fun `resolved general exposes stat exp bars and signed display bonuses`() {
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(
                GeneralReadEntity(
                    id = 10,
                    name = "순욱",
                    nationId = 1,
                    cityId = 5,
                    officerLevel = 5,
                    leadership = 70,
                    strength = 60,
                    intel = 80,
                    politics = 55,
                    charm = 45,
                    horseCode = "che_명마_02_절영",
                    weaponCode = "che_무기_03_청강검",
                    bookCode = "che_서적_04_맹덕신서",
                    meta = linkedMapOf(
                        "leadership_exp" to 15.5,
                        "strength_exp" to 2,
                        "intel_exp" to "29.25",
                        "politics_exp" to 8,
                        "charm_exp" to "13.5",
                    ),
                ),
            ),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.general.leadershipExp").value(15.5))
            .andExpect(jsonPath("$.general.strengthExp").value(2.0))
            .andExpect(jsonPath("$.general.intelExp").value(29.25))
            .andExpect(jsonPath("$.general.politicsExp").value(8.0))
            .andExpect(jsonPath("$.general.charmExp").value(13.5))
            .andExpect(jsonPath("$.general.leadershipBonus").value(9))
            .andExpect(jsonPath("$.general.strengthBonus").value(3))
            .andExpect(jsonPath("$.general.intelBonus").value(4))
            .andExpect(jsonPath("$.general.politicsBonus").value(0))
            .andExpect(jsonPath("$.general.charmBonus").value(0))
    }

    @Test
    fun `front-info returns legacy recent record feeds`() {
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "순욱", nationId = 1, cityId = 5, officerLevel = 5)),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))
        `when`(logFeeds.findGlobalHistorySince(4, 16)).thenReturn(
            listOf(
                WorldLogReadEntity(id = 9, year = 200, month = 3, text = "<C>중원 새 기록</>"),
                WorldLogReadEntity(id = 4, year = 200, month = 3, text = "<C>중원 경계</>"),
            ),
        )
        `when`(logFeeds.findGlobalActionSince(5, 16)).thenReturn(
            listOf(
                WorldLogReadEntity(id = 11, year = 200, month = 3, text = "장수 동향 새 기록"),
                WorldLogReadEntity(id = 5, year = 200, month = 3, text = "장수 동향 경계"),
            ),
        )
        `when`(logFeeds.findGeneralActionSince(10, 5, 16)).thenReturn(
            listOf(
                WorldLogReadEntity(id = 12, year = 200, month = 3, text = "개인 새 기록"),
                WorldLogReadEntity(id = 5, year = 200, month = 3, text = "개인 경계"),
            ),
        )

        mockMvc()
            .perform(
                get("/api/front-info?lastGeneralRecordID=5&lastWorldHistoryID=4").with(principal(7L)),
            )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.recentRecord.history[0][0]").value(9))
            .andExpect(jsonPath("$.recentRecord.history[0][1]").value("<C>중원 새 기록</>"))
            .andExpect(jsonPath("$.recentRecord.history[1]").doesNotExist())
            .andExpect(jsonPath("$.recentRecord.global[0][0]").value(11))
            .andExpect(jsonPath("$.recentRecord.global[1]").doesNotExist())
            .andExpect(jsonPath("$.recentRecord.general[0][0]").value(12))
            .andExpect(jsonPath("$.recentRecord.general[1]").doesNotExist())
            .andExpect(jsonPath("$.recentRecord.flushHistory").value(0))
            .andExpect(jsonPath("$.recentRecord.flushGlobal").value(0))
            .andExpect(jsonPath("$.recentRecord.flushGeneral").value(0))
    }

    @Test
    fun `front-info trims overflow feeds while keeping legacy flush flags unset`() {
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "순욱", nationId = 1, cityId = 5, officerLevel = 5)),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))
        val historyRows = (30 downTo 15).map { WorldLogReadEntity(id = it, year = 200, month = 3, text = "history-$it") }
        val globalRows = (50 downTo 35).map { WorldLogReadEntity(id = it, year = 200, month = 3, text = "global-$it") }
        val generalRows = (70 downTo 55).map { WorldLogReadEntity(id = it, year = 200, month = 3, text = "general-$it") }
        `when`(logFeeds.findGlobalHistorySince(0, 16)).thenReturn(historyRows)
        `when`(logFeeds.findGlobalActionSince(0, 16)).thenReturn(globalRows)
        `when`(logFeeds.findGeneralActionSince(10, 0, 16)).thenReturn(generalRows)

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.recentRecord.history[0][0]").value(30))
            .andExpect(jsonPath("$.recentRecord.history[14][0]").value(16))
            .andExpect(jsonPath("$.recentRecord.history[15]").doesNotExist())
            .andExpect(jsonPath("$.recentRecord.global[0][1]").value("global-50"))
            .andExpect(jsonPath("$.recentRecord.global[14][1]").value("global-36"))
            .andExpect(jsonPath("$.recentRecord.global[15]").doesNotExist())
            .andExpect(jsonPath("$.recentRecord.general[0][1]").value("general-70"))
            .andExpect(jsonPath("$.recentRecord.general[14][1]").value("general-56"))
            .andExpect(jsonPath("$.recentRecord.general[15]").doesNotExist())
            .andExpect(jsonPath("$.recentRecord.flushHistory").value(0))
            .andExpect(jsonPath("$.recentRecord.flushGlobal").value(0))
            .andExpect(jsonPath("$.recentRecord.flushGeneral").value(0))
    }

    @Test
    fun `city info uses occupying nation rather than player nation`() {
        seedWorld()
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(GeneralReadEntity(id = 10, name = "순욱", nationId = 1, cityId = 5, officerLevel = 5)),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(nations.findById(2)).thenReturn(Optional.of(NationReadEntity(id = 2, name = "촉", color = "#0f0", level = 5)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 2)))

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nation.name").value("위"))
            .andExpect(jsonPath("$.city.nationId").value(2))
            .andExpect(jsonPath("$.city.nationName").value("촉"))
            .andExpect(jsonPath("$.city.nationColor").value("#0f0"))
    }

    @Test
    fun `created general resolves from legacy owner column for game entry`() {
        seedWorld()
        val created = GeneralReadEntity(id = 21, name = "신규장수", nationId = 0, cityId = 0, officerLevel = 1)
        `when`(owners.findByUserId(7L)).thenReturn(null)
        `when`(generals.findByUserId("7")).thenReturn(created)
        `when`(generals.findById(21)).thenReturn(Optional.of(created))

        mockMvc().perform(get("/api/front-info").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.general.hasGeneral").value(true))
            .andExpect(jsonPath("$.general.generalId").value(21))
            .andExpect(jsonPath("$.general.name").value("신규장수"))
            .andExpect(jsonPath("$.general.permission").value(0))
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
        `when`(logFeeds.findGeneralActionSince(10, 0, 16)).thenReturn(
            listOf(WorldLogReadEntity(id = 77, year = 200, month = 3, text = "비공개 개인 기록")),
        )

        mockMvc().perform(get("/api/front-info?generalId=10"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.general.hasGeneral").value(true))
            .andExpect(jsonPath("$.general.generalId").value(10))
            .andExpect(jsonPath("$.general.permission").value(0)) // officer_level 1 → 일반
            .andExpect(jsonPath("$.recentRecord.general[0]").doesNotExist())
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
            .andExpect(jsonPath("$.global.lastVote.startDate").value("2026-06-01 09:00:00"))
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

    @Test
    fun `global exposes matching configured serverId from sam_server cookie`() {
        seedWorld()

        listOf("current", "a".repeat(48)).forEach { serverId ->
            mockMvc(serverId = serverId).perform(get("/api/front-info").cookie(Cookie("sam_server", serverId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.global.serverId").value(serverId))
        }
    }

    @Test
    fun `global falls back to configured serverId for mismatched and uppercase cookies`() {
        seedWorld()

        listOf("s1", "PEP").forEach { cookieServerId ->
            mockMvc(serverId = "pep").perform(get("/api/front-info").cookie(Cookie("sam_server", cookieServerId)))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.global.serverId").value("pep"))
        }
    }

    @Test
    fun `global falls back to configured serverId without sam_server cookie`() {
        seedWorld()

        mockMvc(serverId = "pep").perform(get("/api/front-info"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.global.serverId").value("pep"))
    }

    @Test
    fun `global omits cookie serverId when configured ID is absent or invalid`() {
        seedWorld()

        listOf("", "PEP", "all", "main", "join", "a".repeat(49)).forEach { configuredServerId ->
            mockMvc(serverId = configuredServerId).perform(get("/api/front-info").cookie(Cookie("sam_server", "pep")))
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.global.serverId").doesNotExist())
        }
    }
}
