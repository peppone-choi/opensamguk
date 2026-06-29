package opensamguk.gameapi.controller

import opensamguk.gameapi.read.AdminGeneralLogReadRepository
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyReadEntity
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralTurnReadEntity
import opensamguk.gameapi.read.GeneralTurnReadRepository
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.RankDataReadEntity
import opensamguk.gameapi.read.RankDataReadRepository
import opensamguk.gameapi.read.ScenarioTitleResolver
import opensamguk.gameapi.read.WorldLogReadEntity
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.gameapi.security.GameApiJwtVerifier
import opensamguk.infra.entity.GameKvEntity
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.lenient
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant

/**
 * B3a/B4a/B4b — [AdminReadController] slice 테스트(MockMvc standalone, mocked read repo + verifier).
 *
 * 검증:
 *  - ADMIN 게이트: 토큰 없음 → 401, 비-ADMIN role → 403, ADMIN → 200.
 *  - B3a nation-stats: type 정렬(verbatim) + 집계(평균/합/비율) + sortOptions verbatim + historyStats BLOCKED.
 *  - B4a general-log: queryMap 정렬(verbatim) + gen 미지정 시 첫 장수 + 로그 category 매핑.
 *  - B4b diplomacy-all: me<you & state!=2 필터 + state desc + 마스킹 없음(원본 state).
 */
class AdminReadControllerTest {

    private val verifier = mock(GameApiJwtVerifier::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val ranks = mock(RankDataReadRepository::class.java)
    private val diplomacy = mock(DiplomacyReadRepository::class.java)
    private val generalLogs = mock(AdminGeneralLogReadRepository::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    private val gameKv = mock(GameKvReadRepository::class.java)
    private val generalTurns = mock(GeneralTurnReadRepository::class.java)
    private val scenarioTitle = mock(ScenarioTitleResolver::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(
            AdminReadController(
                verifier,
                nations,
                generals,
                cities,
                ranks,
                diplomacy,
                generalLogs,
                world,
                gameKv,
                generalTurns,
                scenarioTitle,
            ),
        ).build()

    /** ADMIN 토큰 발급(stub) — verifier가 valid + role=ADMIN을 반환하게 한다. */
    private fun stubAdmin(token: String = "admintok") {
        `when`(verifier.isValid(token)).thenReturn(true)
        `when`(verifier.getRole(token)).thenReturn("ADMIN")
    }

    private fun bearer(token: String) = "Bearer $token"

    @Test
    fun `game-settings returns admin1 read surface with PHP labels blocked as writes`() {
        stubAdmin()
        `when`(world.findById(1)).thenReturn(
            java.util.Optional.of(
                WorldStateReadEntity(
                    id = 1,
                    scenarioCode = "scenario_1010",
                    currentYear = 181,
                    currentMonth = 1,
                    currentPhase = 2,
                    tickSeconds = 1800,
                    config = mapOf(
                        "startyear" to 180,
                        "starttime" to "2026-06-01 00:00:00",
                        "turnterm" to 30,
                        "map" to mapOf("mapName" to "miniche_b"),
                    ),
                ),
            ),
        )
        `when`(scenarioTitle.titleOf("scenario_1010")).thenReturn("황건적의 난")
        `when`(gameKv.findByTableAndNamespaceAndKey("game_env", "global", "msg")).thenReturn(
            GameKvEntity(table = "game_env", namespace = "global", key = "msg", value = "\"공지\\t내용\""),
        )

        mockMvc().perform(get("/api/admin/game-settings").header("Authorization", bearer("admintok")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.msg").value("공지\t내용"))
            .andExpect(jsonPath("$.scenarioCode").value("scenario_1010"))
            .andExpect(jsonPath("$.scenarioText").value("황건적의 난"))
            .andExpect(jsonPath("$.mapCode").value("miniche_b"))
            .andExpect(jsonPath("$.year").value(181))
            .andExpect(jsonPath("$.turnPhase").value(2))
            .andExpect(jsonPath("$.turnPhaseText").value("중순"))
            .andExpect(jsonPath("$.turnterm").value(30))
            .andExpect(jsonPath("$.turnOptions[5]").value(30))
            .andExpect(jsonPath("$.blockedWrites[0].label").value("중원정세추가"))
            .andExpect(jsonPath("$.editableFields[0].key").value("msg"))
            .andExpect(jsonPath("$.editableFields[1].key").value("npcmode"))
            .andExpect(jsonPath("$.editableFields[2].key").value("block_general_create"))
            .andExpect(jsonPath("$.editableFields[1].options[0].value").value("0"))
    }

    @Test
    fun `general-moderation returns admin2 selector rows and blocked write catalogue`() {
        stubAdmin()
        `when`(generals.findAll()).thenReturn(
            listOf(
                GeneralReadEntity(id = 2, name = "NPC장", npcState = 2),
                GeneralReadEntity(id = 1, name = "유저장", npcState = 0, meta = mapOf("block" to 1, "killturn" to 24)),
            ),
        )
        `when`(generalTurns.findReservedByGeneralIds(listOf(1, 2))).thenReturn(
            listOf(
                GeneralTurnReadEntity(id = 10, generalId = 1, turnIdx = 0, actionCode = "che_하야", brief = "하야"),
                GeneralTurnReadEntity(id = 11, generalId = 1, turnIdx = 1, actionCode = "che_해산", brief = "해산"),
            ),
        )

        mockMvc().perform(get("/api/admin/general-moderation").header("Authorization", bearer("admintok")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generals[0].no").value(1))
            .andExpect(jsonPath("$.generals[0].block").value(1))
            .andExpect(jsonPath("$.generals[0].killturn").value(24))
            .andExpect(jsonPath("$.generals[0].command0").value("하야"))
            .andExpect(jsonPath("$.selectedActions[0].label").value("블럭 해제"))
    }

    @Test
    fun `game-settings returns defaults when world and game env are absent`() {
        stubAdmin()
        `when`(world.findById(1)).thenReturn(java.util.Optional.empty())

        mockMvc().perform(get("/api/admin/game-settings").header("Authorization", bearer("admintok")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.msg").value(""))
            .andExpect(jsonPath("$.scenarioCode").doesNotExist())
            .andExpect(jsonPath("$.year").doesNotExist())
            .andExpect(jsonPath("$.month").doesNotExist())
            .andExpect(jsonPath("$.startyear").value(180))
            .andExpect(jsonPath("$.maxgeneral").value(500))
            .andExpect(jsonPath("$.maxnation").value(55))
            .andExpect(jsonPath("$.blockedWrites[0].label").value("중원정세추가"))
    }

    @Test
    fun `general-moderation returns empty rows with action catalogues`() {
        stubAdmin()
        `when`(generals.findAll()).thenReturn(emptyList())

        mockMvc().perform(get("/api/admin/general-moderation").header("Authorization", bearer("admintok")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generals").isEmpty)
            .andExpect(jsonPath("$.bulkActions[0].label").value("전체 접속허용"))
            .andExpect(jsonPath("$.selectedActions[15].label").value("메세지 전달"))
    }

    // ── ADMIN 게이트 ────────────────────────────────────────────────────────

    @Test
    fun `nation-stats without token is 401`() {
        mockMvc().perform(get("/api/admin/nation-stats"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `nation-stats with non-admin role is 403`() {
        `when`(verifier.isValid("usertok")).thenReturn(true)
        `when`(verifier.getRole("usertok")).thenReturn("USER")

        mockMvc().perform(get("/api/admin/nation-stats").header("Authorization", bearer("usertok")))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `nation-stats with invalid token is 401`() {
        `when`(verifier.isValid("bad")).thenReturn(false)

        mockMvc().perform(get("/api/admin/nation-stats").header("Authorization", bearer("bad")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `new admin read endpoints require admin token`() {
        for (path in listOf("/api/admin/game-settings", "/api/admin/general-moderation")) {
            mockMvc().perform(get(path))
                .andExpect(status().isUnauthorized)

            `when`(verifier.isValid("usertok")).thenReturn(true)
            `when`(verifier.getRole("usertok")).thenReturn("USER")

            mockMvc().perform(get(path).header("Authorization", bearer("usertok")))
                .andExpect(status().isForbidden)
        }
    }

    // ── B3a nation-stats ─────────────────────────────────────────────────────

    @Test
    fun `nation-stats sorts by power desc by default and carries verbatim sort options + aggregates`() {
        stubAdmin()
        `when`(nations.findAll()).thenReturn(
            listOf(
                NationReadEntity(id = 1, name = "위", color = "#c62828", power = 100, gold = 500, rice = 300, tech = 12.34, level = 5),
                NationReadEntity(id = 2, name = "촉", color = "#2e7d32", power = 300, gold = 700, rice = 200, tech = 8.0, level = 5),
            ),
        )
        `when`(generals.findAll()).thenReturn(
            listOf(
                // 위(1): 장수 2명, leadership 60/40 → avg 50.0, crew 1000+2000=3000, dex1 100/300 → avg 200.
                GeneralReadEntity(id = 10, nationId = 1, name = "장A", leadership = 60, strength = 70, intel = 80, gold = 1000, rice = 500, crew = 1000, meta = mapOf("dex1" to 100)),
                GeneralReadEntity(id = 11, nationId = 1, name = "장B", leadership = 40, strength = 50, intel = 60, gold = 2000, rice = 700, crew = 2000, meta = mapOf("dex1" to 300)),
                // 촉(2): 장수 1명.
                GeneralReadEntity(id = 20, nationId = 2, name = "장C", leadership = 90, strength = 90, intel = 90, gold = 3000, rice = 100, crew = 5000),
            ),
        )
        `when`(cities.findAll()).thenReturn(
            listOf(
                // 위(1) 도시 1: pop 50/100 → 50%, agri 30/60 → 50%.
                CityReadEntity(id = 100, nationId = 1, name = "낙양", population = 50, populationMax = 100, agriculture = 30, agricultureMax = 60),
                CityReadEntity(id = 200, nationId = 2, name = "성도", population = 80, populationMax = 80),
            ),
        )

        mockMvc().perform(get("/api/admin/nation-stats").header("Authorization", bearer("admintok")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value(0))
            .andExpect(jsonPath("$.type2").value(0))
            // sortOptions verbatim (value+label) — 0 국력, 13 보숙.
            .andExpect(jsonPath("$.sortOptions[0].value").value(0))
            .andExpect(jsonPath("$.sortOptions[0].label").value("국력"))
            .andExpect(jsonPath("$.sortOptions[11].value").value(13))
            .andExpect(jsonPath("$.sortOptions[11].label").value("보숙"))
            .andExpect(jsonPath("$.sortOptions2[1].label").value("국가별성향"))
            // 기본 정렬 = power desc → 촉(300) 먼저, 위(100) 다음.
            .andExpect(jsonPath("$.rows[0].nationId").value(2))
            .andExpect(jsonPath("$.rows[1].nationId").value(1))
            // 위(1) 집계 검증.
            .andExpect(jsonPath("$.rows[1].genCnt").value(2))
            .andExpect(jsonPath("$.rows[1].cityCnt").value(1))
            .andExpect(jsonPath("$.rows[1].avgLeadership").value(50.0))
            .andExpect(jsonPath("$.rows[1].sumCrew").value(3000))
            .andExpect(jsonPath("$.rows[1].dex1").value(200))
            .andExpect(jsonPath("$.rows[1].pop").value(50))
            .andExpect(jsonPath("$.rows[1].popRate").value(50.0))
            .andExpect(jsonPath("$.rows[1].agri").value(50.0))
            .andExpect(jsonPath("$.rows[1].tech").value(12.3))
            // historyStats / sabotageLog는 BLOCKED(스키마 원천 부재).
            .andExpect(jsonPath("$.historyStatsBlocked").value(true))
            .andExpect(jsonPath("$.sabotageLogBlocked").value(true))
    }

    @Test
    fun `nation-stats type out of range clamps to 0`() {
        stubAdmin()
        `when`(nations.findAll()).thenReturn(emptyList())
        `when`(generals.findAll()).thenReturn(emptyList())
        `when`(cities.findAll()).thenReturn(emptyList())

        mockMvc().perform(
            get("/api/admin/nation-stats").param("type", "99").param("type2", "55").header("Authorization", bearer("admintok")),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value(0))
            .andExpect(jsonPath("$.type2").value(0))
    }

    @Test
    fun `nation-stats sorts by genCnt desc when type is 1`() {
        stubAdmin()
        `when`(nations.findAll()).thenReturn(
            listOf(
                NationReadEntity(id = 1, name = "위", power = 999),
                NationReadEntity(id = 2, name = "촉", power = 1),
            ),
        )
        `when`(generals.findAll()).thenReturn(
            listOf(
                GeneralReadEntity(id = 20, nationId = 2, name = "a"),
                GeneralReadEntity(id = 21, nationId = 2, name = "b"),
                GeneralReadEntity(id = 10, nationId = 1, name = "c"),
            ),
        )
        `when`(cities.findAll()).thenReturn(emptyList())

        mockMvc().perform(
            get("/api/admin/nation-stats").param("type", "1").header("Authorization", bearer("admintok")),
        )
            .andExpect(status().isOk)
            // type=1(장수수 desc): 촉(2명) 먼저, 위(1명) 다음 — power와 무관.
            .andExpect(jsonPath("$.rows[0].nationId").value(2))
            .andExpect(jsonPath("$.rows[0].genCnt").value(2))
            .andExpect(jsonPath("$.rows[1].nationId").value(1))
    }

    // ── B4a general-log ──────────────────────────────────────────────────────

    @Test
    fun `general-log defaults to turntime sort, picks first general, maps log categories`() {
        stubAdmin()
        val tEarly = Instant.parse("2026-06-01T10:00:00Z")
        val tLate = Instant.parse("2026-06-05T10:30:00Z")
        `when`(generals.findAll()).thenReturn(
            listOf(
                GeneralReadEntity(id = 10, name = "장A", nationId = 1, npcState = 0, leadership = 70, strength = 60, intel = 50, officerLevel = 12, turnTime = tEarly),
                GeneralReadEntity(id = 11, name = "장B", nationId = 1, npcState = 0, turnTime = tLate),
            ),
        )
        // 첫 장수(turntime desc = 장B id11)의 로그 패널 매핑.
        `when`(generalLogs.findRecentByGeneral(11, "ACTION", 24)).thenReturn(listOf(WorldLogReadEntity(id = 1, text = "내정행동")))
        `when`(generalLogs.findRecentByGeneral(11, "BATTLE_DETAIL", 24)).thenReturn(listOf(WorldLogReadEntity(id = 2, text = "전투상세")))
        `when`(generalLogs.findAllHistoryByGeneral(11)).thenReturn(listOf(WorldLogReadEntity(id = 3, text = "열전")))
        `when`(generalLogs.findRecentByGeneral(11, "BATTLE_BRIEF", 24)).thenReturn(listOf(WorldLogReadEntity(id = 4, text = "전투결과")))

        mockMvc().perform(get("/api/admin/general-log").header("Authorization", bearer("admintok")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.queryType").value("turntime"))
            // sortOptions verbatim(첫 키 = turntime/최근턴).
            .andExpect(jsonPath("$.sortOptions[0].queryType").value("turntime"))
            .andExpect(jsonPath("$.sortOptions[0].label").value("최근턴"))
            .andExpect(jsonPath("$.sortOptions[3].queryType").value("warnum"))
            .andExpect(jsonPath("$.sortOptions[3].label").value("전투수"))
            // turntime desc → 장B(id11, 최신) 첫 장수로 선택.
            .andExpect(jsonPath("$.gen").value(11))
            .andExpect(jsonPath("$.generalList[0].no").value(11))
            .andExpect(jsonPath("$.generalList[1].no").value(10))
            // 로그 category 매핑.
            .andExpect(jsonPath("$.detail.no").value(11))
            .andExpect(jsonPath("$.detail.actionLog[0]").value("내정행동"))
            .andExpect(jsonPath("$.detail.battleDetailLog[0]").value("전투상세"))
            .andExpect(jsonPath("$.detail.historyLog[0]").value("열전"))
            .andExpect(jsonPath("$.detail.battleResultLog[0]").value("전투결과"))
    }

    @Test
    fun `general-log name sort orders by npc asc then name asc`() {
        stubAdmin()
        `when`(generals.findAll()).thenReturn(
            listOf(
                GeneralReadEntity(id = 10, name = "나비", npcState = 1, turnTime = Instant.parse("2026-06-01T00:00:00Z")),
                GeneralReadEntity(id = 11, name = "가가", npcState = 0, turnTime = Instant.parse("2026-06-01T00:00:00Z")),
                GeneralReadEntity(id = 12, name = "다다", npcState = 0, turnTime = Instant.parse("2026-06-01T00:00:00Z")),
            ),
        )
        // gen=11(가가) 명시 — 로그는 빈 리스트.
        lenient().`when`(generalLogs.findRecentByGeneral(anyInt(), anyString(), anyInt())).thenReturn(emptyList())
        lenient().`when`(generalLogs.findAllHistoryByGeneral(anyInt())).thenReturn(emptyList())

        mockMvc().perform(
            get("/api/admin/general-log").param("query_type", "name").param("gen", "11").header("Authorization", bearer("admintok")),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.queryType").value("name"))
            // npc asc(0 먼저): 가가(0) < 다다(0) < 나비(1).
            .andExpect(jsonPath("$.generalList[0].no").value(11)) // 가가
            .andExpect(jsonPath("$.generalList[1].no").value(12)) // 다다
            .andExpect(jsonPath("$.generalList[2].no").value(10)) // 나비(npc=1)
            .andExpect(jsonPath("$.gen").value(11))
    }

    @Test
    fun `general-log warnum sort uses rank_data value desc`() {
        stubAdmin()
        `when`(generals.findAll()).thenReturn(
            listOf(
                GeneralReadEntity(id = 10, name = "장A", turnTime = Instant.parse("2026-06-01T00:00:00Z")),
                GeneralReadEntity(id = 11, name = "장B", turnTime = Instant.parse("2026-06-01T00:00:00Z")),
            ),
        )
        `when`(ranks.findByGeneralIdsAndTypes(listOf(10, 11), listOf("warnum"))).thenReturn(
            listOf(
                RankDataReadEntity(id = 1, generalId = 10, type = "warnum", value = 5),
                RankDataReadEntity(id = 2, generalId = 11, type = "warnum", value = 50),
            ),
        )
        lenient().`when`(generalLogs.findRecentByGeneral(anyInt(), anyString(), anyInt())).thenReturn(emptyList())
        lenient().`when`(generalLogs.findAllHistoryByGeneral(anyInt())).thenReturn(emptyList())

        mockMvc().perform(
            get("/api/admin/general-log").param("query_type", "warnum").header("Authorization", bearer("admintok")),
        )
            .andExpect(status().isOk)
            // warnum desc: 장B(50) 먼저 → 첫 장수로 선택.
            .andExpect(jsonPath("$.generalList[0].no").value(11))
            .andExpect(jsonPath("$.gen").value(11))
    }

    @Test
    fun `general-log unknown query_type falls back to first key turntime`() {
        stubAdmin()
        `when`(generals.findAll()).thenReturn(emptyList())

        mockMvc().perform(
            get("/api/admin/general-log").param("query_type", "bogus").header("Authorization", bearer("admintok")),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.queryType").value("turntime"))
            .andExpect(jsonPath("$.gen").value(0))
            .andExpect(jsonPath("$.detail").doesNotExist())
    }

    // ── B4b diplomacy-all ─────────────────────────────────────────────────────

    @Test
    fun `diplomacy-all filters me lt you and state ne 2, orders state desc, no masking`() {
        stubAdmin()
        `when`(nations.findAll()).thenReturn(
            listOf(
                NationReadEntity(id = 1, name = "위", color = "#c62828"),
                NationReadEntity(id = 2, name = "촉", color = "#2e7d32"),
                NationReadEntity(id = 3, name = "오", color = "#1565c0"),
            ),
        )
        `when`(diplomacy.findAll()).thenReturn(
            listOf(
                DiplomacyReadEntity(id = 1, srcNationId = 1, destNationId = 2, stateCode = 0, term = 0), // 교전
                DiplomacyReadEntity(id = 2, srcNationId = 1, destNationId = 3, stateCode = 7, term = 12), // 불가침
                DiplomacyReadEntity(id = 3, srcNationId = 2, destNationId = 3, stateCode = 2, term = 0), // 통상(skip)
                DiplomacyReadEntity(id = 4, srcNationId = 2, destNationId = 1, stateCode = 0, term = 0), // me>you(skip)
            ),
        )

        mockMvc().perform(get("/api/admin/diplomacy-all").header("Authorization", bearer("admintok")))
            .andExpect(status().isOk)
            // me<you & state!=2 → id1(state0), id2(state7). state desc → 불가침(7) 먼저.
            .andExpect(jsonPath("$.relations.length()").value(2))
            .andExpect(jsonPath("$.relations[0].me").value(1))
            .andExpect(jsonPath("$.relations[0].you").value(3))
            .andExpect(jsonPath("$.relations[0].state").value(7))
            .andExpect(jsonPath("$.relations[0].stateText").value("불가침"))
            .andExpect(jsonPath("$.relations[0].meName").value("위"))
            .andExpect(jsonPath("$.relations[0].youName").value("오"))
            .andExpect(jsonPath("$.relations[0].term").value(12))
            // 교전(0) — 원본 state 노출(마스킹 없음).
            .andExpect(jsonPath("$.relations[1].state").value(0))
            .andExpect(jsonPath("$.relations[1].stateText").value("교 전"))
    }

    @Test
    fun `diplomacy-all without token is 401`() {
        mockMvc().perform(get("/api/admin/diplomacy-all"))
            .andExpect(status().isUnauthorized)
    }
}
