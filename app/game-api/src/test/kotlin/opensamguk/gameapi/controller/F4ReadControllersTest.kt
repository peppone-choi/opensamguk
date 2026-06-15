package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.precheck.CommandPrecheckService
import opensamguk.gameapi.read.BoardCommentReadRepository
import opensamguk.gameapi.read.BoardPostReadRepository
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyLetterReadEntity
import opensamguk.gameapi.read.DiplomacyLetterReadRepository
import opensamguk.gameapi.read.DiplomacyReadEntity
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.HistoryReadRepository
import opensamguk.gameapi.read.InheritanceLogReadEntity
import opensamguk.gameapi.read.InheritanceLogReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationEnvReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.NationTurnReadEntity
import opensamguk.gameapi.read.NationTurnReadRepository
import opensamguk.gameapi.read.SecretPermissionReader
import opensamguk.gameapi.read.TroopReadEntity
import opensamguk.gameapi.read.TroopReadRepository
import opensamguk.gameapi.read.VoteCommentReadRepository
import opensamguk.gameapi.read.VotePollReadRepository
import opensamguk.gameapi.read.VoteReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.entity.NationEnvEntity
import opensamguk.logic.actions.CommandRegistry
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.stats.GeneralActionPipeline
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
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
 * F4 — MockMvc standalone slice tests for the 12 READ-only action-page controllers (spec
 * 2026-06-03-F4-action-pages-spec.md). No Spring context, no Testcontainers — every repo is a mock.
 *
 * Coverage: the non-trivial real-data shapes (generals public projection, diplomacy letters state text,
 * conflict matrix, nation finance editable gate, chief-reserved post grid, inherit cost+stat clamp,
 * vote tally + myVote) AND the empty/graceful defaults for the seed-empty tables (tournament state-0,
 * board, votes, troops, history) + the permission gates (board 기밀실, chief/npc/inherit identity).
 */
class F4ReadControllersTest {

    private val generals = mock(GeneralReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val owners = mock(GeneralOwnerRepository::class.java)
    private val resolver = GeneralResolver(owners, generals, nations)
    private val letters = mock(DiplomacyLetterReadRepository::class.java)
    private val diplomacy = mock(DiplomacyReadRepository::class.java)
    private val nationTurns = mock(NationTurnReadRepository::class.java)
    private val gameKv = mock(GameKvReadRepository::class.java)
    private val inheritLogs = mock(InheritanceLogReadRepository::class.java)
    private val boardPosts = mock(BoardPostReadRepository::class.java)
    private val boardComments = mock(BoardCommentReadRepository::class.java)
    private val polls = mock(VotePollReadRepository::class.java)
    private val votes = mock(VoteReadRepository::class.java)
    private val voteComments = mock(VoteCommentReadRepository::class.java)
    private val troops = mock(TroopReadRepository::class.java)
    private val history = mock(HistoryReadRepository::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    // ChiefCenter 명령 팔레트는 REAL CommandRegistry로 구동(실제 :logic 정의 key/name/argsSchema 사용).
    private val commandRegistry = CommandRegistry(GeneralActionPipeline())
    // precheck mock — 스텁 미설정 시 precheckAll이 null 반환(액터 상태 없음) → 레지스트리-only 폴백
    // (possible=true). 명령 팔레트 카테고리/이름/reqArg 검증에는 이 폴백 동작으로 충분하다.
    private val precheck = mock(CommandPrecheckService::class.java)
    private val objectMapper = ObjectMapper()
    // nation_env(V3) read mock — 스텁 미설정 시 findByNamespaceAndKey가 null 반환 → nationMsg/scoutMsg/remain null(기존 BLOCKED 동작 보존).
    private val nationEnv = mock(NationEnvReadRepository::class.java)

    /** ChiefCenterController 8-인자 생성 헬퍼(B1 precheck 배선 후 시그니처). */
    private fun chiefCenterController() =
        ChiefCenterController(resolver, nationTurns, generals, nations, world, troops, commandRegistry, precheck)

    private fun mvc(vararg controllers: Any): MockMvc =
        MockMvcBuilders.standaloneSetup(*controllers)
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun gen(
        id: Int, name: String, nationId: Int = 0, cityId: Int = 0, officerLevel: Int = 0,
        leadership: Int = 0, strength: Int = 0, intel: Int = 0, crew: Int = 0, troopId: Int = 0,
        meta: Map<String, Any?> = linkedMapOf(),
    ) = GeneralReadEntity(
        id = id, name = name, nationId = nationId, cityId = cityId, officerLevel = officerLevel,
        leadership = leadership, strength = strength, intel = intel, crew = crew, troopId = troopId, meta = meta,
    )

    private fun nation(id: Int, name: String, color: String = "#fff", level: Int = 0, gold: Int = 0, rice: Int = 0, meta: Map<String, Any?> = linkedMapOf()) =
        NationReadEntity(id = id, name = name, color = color, level = level, gold = gold, rice = rice, meta = meta)

    /** D11 — power/capital/type/gennum까지 채운 nation 헬퍼(GetDiplomacy SimpleNationObj 검증용). */
    private fun nationP(
        id: Int, name: String, color: String = "#fff", level: Int = 0, power: Int = 0,
        capital: Int = 0, type: String = "che_중립", meta: Map<String, Any?> = linkedMapOf(),
    ) = NationReadEntity(
        id = id, name = name, color = color, level = level, power = power,
        capitalCityId = capital, typeCode = type, meta = meta,
    )

    private fun city(id: Int, name: String, nationId: Int = 0, conflict: Map<String, Any?> = linkedMapOf()) =
        CityReadEntity(id = id, name = name, nationId = nationId, conflict = conflict)

    // ── GET /api/generals (public projection, 재야 join) ─────────────────────────────────────────────
    @Test
    fun `generals returns public fields with neutral join and city name`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "위", "#c62828")))
        `when`(cities.findAll()).thenReturn(listOf(city(5, "허창", nationId = 1)))
        `when`(generals.findAll()).thenReturn(
            listOf(
                gen(id = 2, name = "방랑", nationId = 0, cityId = 0, officerLevel = 0, leadership = 70, strength = 80, intel = 90, crew = 0),
                gen(id = 1, name = "조조", nationId = 1, cityId = 5, officerLevel = 12, leadership = 90, strength = 80, intel = 95, crew = 1000),
            ),
        )

        mvc(GeneralsController(generals, nations, cities)).perform(get("/api/generals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[0].generalId").value(1)) // sorted by id asc
            .andExpect(jsonPath("$[0].name").value("조조"))
            .andExpect(jsonPath("$[0].nationName").value("위"))
            .andExpect(jsonPath("$[0].nationColor").value("#c62828"))
            .andExpect(jsonPath("$[0].cityName").value("허창"))
            // 명성/계급은 레벨 버킷(raw exp/ded 아님). exp/ded 미지정 → 버킷 0.
            .andExpect(jsonPath("$[0].explevel").value(0))
            .andExpect(jsonPath("$[0].honorText").value("전무"))       // getHonor(0)
            .andExpect(jsonPath("$[0].dedlevel").value(0))
            .andExpect(jsonPath("$[0].dedLevelText").value("무품관"))   // getDedLevelText(0)
            .andExpect(jsonPath("$[0].bill").value(400))               // getBillByLevel(0)
            .andExpect(jsonPath("$[1].generalId").value(2))
            .andExpect(jsonPath("$[1].nationName").value("재야"))
            .andExpect(jsonPath("$[1].nationColor").value("#000000"))
            .andExpect(jsonPath("$[1].cityName").value(""))
            // permission=0 surface only — no raw gold/rice/experience/dedication field (OQ-5).
            .andExpect(jsonPath("$[0].gold").doesNotExist())
            .andExpect(jsonPath("$[0].experience").doesNotExist())
            .andExpect(jsonPath("$[0].dedication").doesNotExist())
            .andExpect(jsonPath("$[0].rice").doesNotExist())
            // 벌점(refresh_score_total)은 §2 BLOCKED — 필드 자체가 없어야 한다(날조 금지).
            .andExpect(jsonPath("$[0].refreshScoreTotal").doesNotExist())
    }

    // ── GET /api/generals — a_genList 15컬럼 보강(C3①) 한글 해석/부상보너스/삭턴 검증 ──────────────────
    @Test
    fun `generals emits a_genList columns - korean text, lbonus, killturn`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "위", "#c62828", level = 7)))
        `when`(cities.findAll()).thenReturn(listOf(city(5, "허창", nationId = 1)))
        `when`(generals.findAll()).thenReturn(
            listOf(
                GeneralReadEntity(
                    id = 1, name = "조조", nationId = 1, cityId = 5, officerLevel = 12,
                    leadership = 90, strength = 80, intel = 95, crew = 1000,
                    age = 41, injury = 0, picture = "chocho.jpg", imageServer = 2,
                    personalCode = "che_정복", specialCode = "None", special2Code = "None",
                    meta = linkedMapOf("killturn" to 38),
                ),
            ),
        )

        mvc(GeneralsController(generals, nations, cities)).perform(get("/api/generals"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].age").value(41))
            .andExpect(jsonPath("$[0].picture").value("chocho.jpg"))
            .andExpect(jsonPath("$[0].imageServer").value(2))
            .andExpect(jsonPath("$[0].injury").value(0))
            // 성격 한글명(personalityNameOf). None 특기는 "-"로 정규화(코드 미등록=PHP None.php $name='-').
            .andExpect(jsonPath("$[0].personalText").value("정복"))
            .andExpect(jsonPath("$[0].specialDomesticText").value("-"))
            .andExpect(jsonPath("$[0].specialWarText").value("-"))
            // 관직(officerLevel 12, nationLevel 7) → 황제. 통솔보너스 = calcLeadershipBonus(12,7)=14.
            .andExpect(jsonPath("$[0].officerLevelText").value("황제"))
            .andExpect(jsonPath("$[0].lbonus").value(14))
            // 삭턴 = meta.killturn.
            .andExpect(jsonPath("$[0].killturn").value(38))
    }

    // ── GET /api/tournament (state-0 default, no table) ──────────────────────────────────────────────
    @Test
    fun `tournament returns state-0 default with 4 empty ranking boards`() {
        `when`(gameKv.findByTableAndNamespaceAndKey(anyString(), anyString(), anyString())).thenReturn(null)

        mvc(TournamentController(gameKv, objectMapper)).perform(get("/api/tournament"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.state").value(0))
            .andExpect(jsonPath("$.tnmtType").value(0))
            .andExpect(jsonPath("$.tnmtTypeText").value("전력전"))
            .andExpect(jsonPath("$.tnmtMsg").value(""))
            .andExpect(jsonPath("$.groups.length()").value(0))
            .andExpect(jsonPath("$.bracket.length()").value(0))
            .andExpect(jsonPath("$.rankings.length()").value(4))
            .andExpect(jsonPath("$.rankings[0].type").value("전력전"))
            .andExpect(jsonPath("$.rankings[1].type").value("통솔전"))
            .andExpect(jsonPath("$.rankings[2].type").value("일기토"))
            .andExpect(jsonPath("$.rankings[3].type").value("설전"))
    }

    private fun diplomacyController() =
        DiplomacyController(diplomacy, letters, nations, cities, resolver, SecretPermissionReader(nations))

    // ── GET /api/diplomacy/letters (state text verbatim) ─────────────────────────────────────────────
    @Test
    fun `diplomacy letters maps state text verbatim and scopes to my nation`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "위", "#c62828", level = 7), nation(2, "촉", "#2e7d32", level = 5)))
        `when`(letters.findBySrcNationIdOrDestNationIdOrderByDateAscIdAsc(1, 1)).thenReturn(
            listOf(
                DiplomacyLetterReadEntity(id = 1, srcNationId = 1, destNationId = 2, state = "PROPOSED", textBrief = "종전제의", textDetail = "종전합시다", srcSigner = 10),
                DiplomacyLetterReadEntity(id = 2, srcNationId = 2, destNationId = 1, state = "ACTIVATED", textBrief = "승인", textDetail = "좋소", srcSigner = 20, destSigner = 10),
            ),
        )

        mvc(diplomacyController()).perform(get("/api/diplomacy/letters").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.myNationID").value(1))
            .andExpect(jsonPath("$.nations.1.name").value("위"))
            // legacy NationStaticItem.level — 수신국 select 표시용(실 nation.level 컬럼).
            .andExpect(jsonPath("$.nations.1.level").value(7))
            .andExpect(jsonPath("$.nations.2.color").value("#2e7d32"))
            .andExpect(jsonPath("$.letters.length()").value(2))
            .andExpect(jsonPath("$.letters[0].stateText").value("제안됨"))
            .andExpect(jsonPath("$.letters[1].stateText").value("승인됨"))
            // C1-α — Party 와이어 키는 legacy aux/MessageTarget 키(nationID/nationName/nationColor).
            // aux 결손이면 nation 조회 폴백(nationName/Color), generalName/Icon은 null.
            .andExpect(jsonPath("$.letters[0].src.nationID").value(1))
            .andExpect(jsonPath("$.letters[0].src.nationName").value("위"))
            .andExpect(jsonPath("$.letters[0].src.nationColor").value("#c62828"))
            .andExpect(jsonPath("$.letters[0].src.generalName").doesNotExist())
            .andExpect(jsonPath("$.letters[0].dest.nationID").value(2))
            .andExpect(jsonPath("$.letters[0].dest.nationName").value("촉"))
            // state(소문자) + prev_no/state_opt 와이어 키.
            .andExpect(jsonPath("$.letters[0].state").value("proposed"))
            .andExpect(jsonPath("$.letters[0].prev_no").doesNotExist())
            .andExpect(jsonPath("$.letters[0].state_opt").doesNotExist())
    }

    // ── C1-α — aux 스냅샷에서 서명자(generalName/generalIcon) + state_opt 구성, permission<3 detail 마스킹 ──
    @Test
    fun `diplomacy letters source party from aux, mask detail for non-군주, filter cancelled`() {
        // 호출자 officer_level 5(수뇌) → secretPermission 2 (<3) → detail 마스킹.
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "위", "#c62828"), nation(2, "촉", "#2e7d32")))
        `when`(letters.findBySrcNationIdOrDestNationIdOrderByDateAscIdAsc(1, 1)).thenReturn(
            listOf(
                // activated 서신: aux['src'] 풀 타깃(서명자) + aux['dest'] nation-only + state_opt.
                DiplomacyLetterReadEntity(
                    id = 1, srcNationId = 1, destNationId = 2, state = "ACTIVATED",
                    textBrief = "불가침", textDetail = "5년 불가침을 제안합니다", srcSigner = 10, destSigner = 20,
                    aux = linkedMapOf(
                        "src" to linkedMapOf(
                            "nationName" to "위", "nationColor" to "#c62828",
                            "generalName" to "순욱", "generalIcon" to "//cdn/sunyuk.png",
                        ),
                        "dest" to linkedMapOf("nationName" to "촉", "nationColor" to "#2e7d32"),
                        "state_opt" to "try_destroy_src",
                    ),
                ),
                // cancelled 서신은 목록에서 제외돼야 한다(legacy WHERE state != 'cancelled').
                DiplomacyLetterReadEntity(id = 2, srcNationId = 1, destNationId = 2, state = "CANCELLED", textBrief = "파기됨", textDetail = "x", srcSigner = 10),
            ),
        )

        mvc(diplomacyController()).perform(get("/api/diplomacy/letters").with(principal(7L)))
            .andExpect(status().isOk)
            // cancelled 1건 제외 → 1건만.
            .andExpect(jsonPath("$.letters.length()").value(1))
            .andExpect(jsonPath("$.letters[0].no").value(1))
            // aux['src'] 서명자(generalName/generalIcon)가 그대로 내려온다(nationID는 컬럼값으로 덮음).
            .andExpect(jsonPath("$.letters[0].src.nationID").value(1))
            .andExpect(jsonPath("$.letters[0].src.generalName").value("순욱"))
            .andExpect(jsonPath("$.letters[0].src.generalIcon").value("//cdn/sunyuk.png"))
            // aux['dest']는 nation-only → generalName 없음.
            .andExpect(jsonPath("$.letters[0].dest.nationID").value(2))
            .andExpect(jsonPath("$.letters[0].dest.nationName").value("촉"))
            .andExpect(jsonPath("$.letters[0].dest.generalName").doesNotExist())
            // state_opt 와이어 키(파기 2단계).
            .andExpect(jsonPath("$.letters[0].state_opt").value("try_destroy_src"))
            // permission 2 (<3) → detail 마스킹 verbatim.
            .andExpect(jsonPath("$.letters[0].detail").value("(권한이 부족합니다)"))
            // brief는 마스킹하지 않는다.
            .andExpect(jsonPath("$.letters[0].brief").value("불가침"))
    }

    // ── C1-α — 군주(officer_level 12 → secretPermission 4)는 detail 마스킹 해제 ──────────────────────────
    @Test
    fun `diplomacy letters do NOT mask detail for 군주 caller`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "조조", nationId = 1, officerLevel = 12)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "위", "#c62828"), nation(2, "촉", "#2e7d32")))
        `when`(letters.findBySrcNationIdOrDestNationIdOrderByDateAscIdAsc(1, 1)).thenReturn(
            listOf(
                DiplomacyLetterReadEntity(id = 1, srcNationId = 1, destNationId = 2, state = "ACTIVATED", textBrief = "불가침", textDetail = "5년 불가침을 제안합니다", srcSigner = 10),
            ),
        )

        mvc(diplomacyController()).perform(get("/api/diplomacy/letters").with(principal(7L)))
            .andExpect(status().isOk)
            // 군주(secretPermission 4) → detail 원문 노출.
            .andExpect(jsonPath("$.letters[0].detail").value("5년 불가침을 제안합니다"))
    }

    // ── W0-3 — 외교권자(ambassador)는 직급 무관 secretPermission 4 → detail 마스킹 해제 ─────────────────
    @Test
    fun `diplomacy letters do NOT mask detail for ambassador caller`() {
        // PHP checkSecretPermission(func.php:413-414): permission=='ambassador' → 4 (>=3 → 원문).
        // 종전의 officer_level-only 모델은 lv1 외교권자를 0으로 깎아 마스킹했다(감사 P1-031) — 교정 핀.
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(gen(10, "외교관", nationId = 1, officerLevel = 1, meta = linkedMapOf("permission" to "ambassador"))),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "위", "#c62828"), nation(2, "촉", "#2e7d32")))
        `when`(letters.findBySrcNationIdOrDestNationIdOrderByDateAscIdAsc(1, 1)).thenReturn(
            listOf(
                DiplomacyLetterReadEntity(id = 1, srcNationId = 1, destNationId = 2, state = "ACTIVATED", textBrief = "불가침", textDetail = "5년 불가침을 제안합니다", srcSigner = 10),
            ),
        )

        mvc(diplomacyController()).perform(get("/api/diplomacy/letters").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.letters[0].detail").value("5년 불가침을 제안합니다"))
    }

    @Test
    fun `diplomacy letters returns empty for anonymous caller`() {
        `when`(nations.findAll()).thenReturn(listOf(nation(1, "위", "#c62828")))

        mvc(diplomacyController()).perform(get("/api/diplomacy/letters"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myNationID").value(0))
            .andExpect(jsonPath("$.letters.length()").value(0))
            .andExpect(jsonPath("$.nations.1.name").value("위"))
    }

    // ── GET /api/diplomacy/conflict = GetDiplomacy.php envelope (nations/conflict/diplomacyList/myNationID) ──
    @Test
    fun `diplomacy conflict returns GetDiplomacy envelope with power-sorted nations, normalized conflict, masked diplomacyList`() {
        // 위(power 30) < 촉(power 50) → power DESC면 촉이 먼저. 둘 다 level>0.
        `when`(nations.findAll()).thenReturn(
            listOf(
                nationP(1, "위", "#c62828", level = 7, power = 30, capital = 5, type = "che_위나라", meta = linkedMapOf("gennum" to 12)),
                nationP(2, "촉", "#2e7d32", level = 5, power = 50, capital = 8, type = "che_촉나라", meta = linkedMapOf("gennum" to 9)),
            ),
        )
        `when`(cities.findAll()).thenReturn(
            listOf(
                // 분쟁 2개 항목, sum=50 → 2:round(80.0,1)=80.0, 3:round(20.0,1)=20.0.
                city(5, "허창", nationId = 1, conflict = linkedMapOf("2" to 40, "3" to 10)),
                // 항목<2(단일 nationId) → 분쟁 목록에서 제외(도시명 보강만).
                city(8, "성도", nationId = 2, conflict = linkedMapOf("1" to 5)),
                // 빈 분쟁맵 → 제외(도시명 보강만).
                city(9, "강주", nationId = 2, conflict = linkedMapOf()),
            ),
        )
        // PHP는 diplomacy 전체 행을 1회 순회(findAll) — me→you→state.
        `when`(diplomacy.findAll()).thenReturn(
            listOf(DiplomacyReadEntity(id = 1, srcNationId = 1, destNationId = 2, stateCode = 5, term = 3)),
        )

        mvc(diplomacyController()).perform(get("/api/diplomacy/conflict"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.myNationID").value(0)) // 익명 호출자
            // nations: power DESC → 촉(50) 먼저, 위(30) 다음. SimpleNationObj 필드셋.
            .andExpect(jsonPath("$.nations.length()").value(2))
            .andExpect(jsonPath("$.nations[0].nation").value(2))
            .andExpect(jsonPath("$.nations[0].name").value("촉"))
            .andExpect(jsonPath("$.nations[0].type").value("che_촉나라"))
            .andExpect(jsonPath("$.nations[0].level").value(5))
            .andExpect(jsonPath("$.nations[0].capital").value(8))
            .andExpect(jsonPath("$.nations[0].gennum").value(9))
            .andExpect(jsonPath("$.nations[0].power").value(50))
            // 촉 도시명(성도, 강주) 행 순서 보강.
            .andExpect(jsonPath("$.nations[0].cities[0]").value("성도"))
            .andExpect(jsonPath("$.nations[0].cities[1]").value("강주"))
            .andExpect(jsonPath("$.nations[1].nation").value(1))
            .andExpect(jsonPath("$.nations[1].cities[0]").value("허창"))
            // conflict = [[cityId, {nationId: pct}]] 튜플, 정규화된 Double 소수1자리. 허창(5)만 남는다.
            .andExpect(jsonPath("$.conflict.length()").value(1))
            .andExpect(jsonPath("$.conflict[0][0]").value(5))
            .andExpect(jsonPath("$.conflict[0][1].2").value(80.0))
            .andExpect(jsonPath("$.conflict[0][1].3").value(20.0))
            // diplomacyList: 익명(myNationID=0) → 둘 다 내 국가 아님 → state 5 마스킹 2.
            .andExpect(jsonPath("$.diplomacyList.1.2").value(2))
    }

    @Test
    fun `diplomacy conflict does NOT mask state when viewer is a party (viewer-conditional)`() {
        // 호출자(userId 7) → 장수 10 → 국가 1(위). myNationID=1.
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "조조", nationId = 1, officerLevel = 12)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(nations.findAll()).thenReturn(
            listOf(
                nationP(1, "위", "#c62828", level = 7, power = 30),
                nationP(2, "촉", "#2e7d32", level = 5, power = 50),
                nationP(3, "오", "#1565c0", level = 4, power = 20),
            ),
        )
        `when`(cities.findAll()).thenReturn(emptyList())
        `when`(diplomacy.findAll()).thenReturn(
            listOf(
                // 1↔2: 내 국가(1)가 당사자 → 원 state 5 노출.
                DiplomacyReadEntity(id = 1, srcNationId = 1, destNationId = 2, stateCode = 5, term = 3),
                // 2↔3: 둘 다 내 국가 아님 → state 5 마스킹 2.
                DiplomacyReadEntity(id = 2, srcNationId = 2, destNationId = 3, stateCode = 5, term = 3),
            ),
        )

        mvc(diplomacyController()).perform(get("/api/diplomacy/conflict").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.myNationID").value(1))
            // 내 국가 당사자 관계는 원 state 5 (마스킹 안 함).
            .andExpect(jsonPath("$.diplomacyList.1.2").value(5))
            // 제3자끼리 관계는 3..7→2 마스킹.
            .andExpect(jsonPath("$.diplomacyList.2.3").value(2))
    }

    // ── GET /api/nation/{id}/finance (W0-2 P0-51 중첩 구조 + P0-53 read 키 정합 + editable gate) ──────
    @Test
    fun `nation finance emits the legacy nested staticValues shape`() {
        // PHP v_nationStratFinan.php:126-154 — {editable, nationMsg, scoutMsg, nationID, officerLevel,
        // year, month, gold, rice, income{...}, outcome, policy{...}, warSettingCnt{remain,inc,max}}.
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        // P0-53: blockWar/blockScout의 실제 write 키는 meta["war"]/["scout"](Int) — NationFinanceSetters.
        val meta = linkedMapOf<String, Any?>("rate" to 20, "bill" to 100, "secretlimit" to 30, "war" to 1, "scout" to 0)
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", "#c62828", level = 7, gold = 5000, rice = 3000, meta = meta)))
        `when`(world.findAll()).thenReturn(
            listOf(WorldStateReadEntity(id = 1, scenarioCode = "che_1010", currentYear = 200, currentMonth = 3, tickSeconds = 3600)),
        )

        mvc(NationFinanceController(nations, resolver, world, nationEnv, objectMapper)).perform(get("/api/nation/1/finance").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.gold").value(5000))
            .andExpect(jsonPath("$.rice").value(3000))
            .andExpect(jsonPath("$.officerLevel").value(5))
            .andExpect(jsonPath("$.year").value(200))
            .andExpect(jsonPath("$.month").value(3))
            // policy 중첩(PHP policy{rate,bill,secretLimit,blockScout,blockWar}).
            .andExpect(jsonPath("$.policy.rate").value(20))
            .andExpect(jsonPath("$.policy.bill").value(100))
            .andExpect(jsonPath("$.policy.secretLimit").value(30))
            .andExpect(jsonPath("$.policy.blockWar").value(true))
            .andExpect(jsonPath("$.policy.blockScout").value(false))
            // warSettingCnt — inc/max는 GameConst 실상수, remain은 nation_env read 부재로 null(P0-53 BLOCKED).
            .andExpect(jsonPath("$.warSettingCnt.inc").value(2))
            .andExpect(jsonPath("$.warSettingCnt.max").value(10))
            .andExpect(jsonPath("$.warSettingCnt.remain").doesNotExist())
            // income/outcome — read 경로에 income 파이프라인 미조립(P0-52 BLOCKED, W1-O 배선).
            .andExpect(jsonPath("$.income").doesNotExist())
            .andExpect(jsonPath("$.outcome").doesNotExist())
            // nationMsg/scoutMsg — 실제 write는 nation_env KV(P0-53), read repo 부재 → null(날조 금지).
            .andExpect(jsonPath("$.nationMsg").doesNotExist())
            .andExpect(jsonPath("$.scoutMsg").doesNotExist())
            .andExpect(jsonPath("$.editable").value(true))
    }

    @Test
    fun `nation finance surfaces nation_env notice scout and war-count when present`() {
        // NF-P1-B/P0-C(W1-O 바퀴49) — 데몬이 nation_env(V3)에 쓴 nationNotice{msg}/scout_msg/available_war_setting_cnt를
        // NationEnvReadRepository로 read → nationMsg/scoutMsg/warSettingCnt.remain 언블록(부재→null 규약은 위 테스트가 핀).
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", "#c62828", level = 7, gold = 5000, rice = 3000)))
        `when`(world.findAll()).thenReturn(
            listOf(WorldStateReadEntity(id = 1, scenarioCode = "che_1010", currentYear = 200, currentMonth = 3, tickSeconds = 3600)),
        )
        // nationNotice = {date,msg,author,authorID} 객체(handler 삽입순서) → nationMsg = .msg.
        `when`(nationEnv.findByNamespaceAndKey(1, "nationNotice")).thenReturn(
            NationEnvEntity(namespace = 1, key = "nationNotice", value = """{"date":"200-3","msg":"천하통일을 위하여","author":"순욱","authorID":10}"""),
        )
        // scout_msg = JSON 문자열.
        `when`(nationEnv.findByNamespaceAndKey(1, "scout_msg")).thenReturn(
            NationEnvEntity(namespace = 1, key = "scout_msg", value = "\"인재를 구합니다\""),
        )
        // available_war_setting_cnt = JSON int.
        `when`(nationEnv.findByNamespaceAndKey(1, "available_war_setting_cnt")).thenReturn(
            NationEnvEntity(namespace = 1, key = "available_war_setting_cnt", value = "3"),
        )

        mvc(NationFinanceController(nations, resolver, world, nationEnv, objectMapper)).perform(get("/api/nation/1/finance").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.nationMsg").value("천하통일을 위하여"))
            .andExpect(jsonPath("$.scoutMsg").value("인재를 구합니다"))
            .andExpect(jsonPath("$.warSettingCnt.remain").value(3))
    }

    @Test
    fun `nation finance missing nation returns result-false zeroed shape`() {
        `when`(nations.findById(99)).thenReturn(Optional.empty())

        mvc(NationFinanceController(nations, resolver, world, nationEnv, objectMapper)).perform(get("/api/nation/99/finance"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.gold").value(0))
            .andExpect(jsonPath("$.officerLevel").value(0))
            .andExpect(jsonPath("$.editable").value(false))
    }

    // ── GET /api/nation/chief-reserved (8 posts, reserved turns by level) ────────────────────────────
    @Test
    fun `chief reserved returns 8 posts with reserved turns grouped by officer level`() {
        // 액터 상태 없음 → precheckAll null → 명령 팔레트는 레지스트리-only 폴백(possible=true).
        `when`(precheck.precheckAll(anyInt(), anyList<GeneralActionDefinition>())).thenReturn(null)
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "조조", nationId = 1, officerLevel = 12)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)).thenReturn(
            listOf(gen(10, "조조", nationId = 1, officerLevel = 12)),
        )
        `when`(nationTurns.findByNationIdOrderByOfficerLevelDescTurnIdxAsc(1)).thenReturn(
            listOf(
                NationTurnReadEntity(
                    id = 1, nationId = 1, officerLevel = 12, turnIdx = 0,
                    actionCode = "che_증축", arg = linkedMapOf("destCityID" to 5), brief = "증축",
                ),
            ),
        )
        `when`(world.findAll()).thenReturn(
            listOf(WorldStateReadEntity(id = 1, currentYear = 200, currentMonth = 3, tickSeconds = 3600)),
        )
        `when`(troops.findByNationOrderByTroopLeaderAsc(1)).thenReturn(
            listOf(TroopReadEntity(troopLeader = 10, nation = 1, name = "선봉대")),
        )

        mvc(chiefCenterController()).perform(get("/api/nation/chief-reserved").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.nationId").value(1))
            // W3 — 호출자 식별 + 국가 컨텍스트 + 게임 시각.
            .andExpect(jsonPath("$.myGeneralId").value(10))
            .andExpect(jsonPath("$.myOfficerLevel").value(12))
            .andExpect(jsonPath("$.nationName").value("위"))
            .andExpect(jsonPath("$.nationLevel").value(7))
            .andExpect(jsonPath("$.year").value(200))
            .andExpect(jsonPath("$.month").value(3))
            .andExpect(jsonPath("$.turnTerm").value(60)) // 3600/60
            .andExpect(jsonPath("$.isChief").value(true)) // officer_level 12 > 4
            // posts grid (8 칸, 보강 필드).
            .andExpect(jsonPath("$.posts.length()").value(8))
            .andExpect(jsonPath("$.posts[0].officerLevel").value(12))
            .andExpect(jsonPath("$.posts[0].title").value("군주"))
            .andExpect(jsonPath("$.posts[0].name").value("조조")) // W3 — 직책 보유 장수 이름
            .andExpect(jsonPath("$.posts[0].npcType").value(0)) // W3 — npc_state 기본 0(PC)
            .andExpect(jsonPath("$.posts[0].officerLevelText").value("황제")) // 국가 레벨 7 + lv12
            .andExpect(jsonPath("$.posts[0].reservedTurns.length()").value(1))
            .andExpect(jsonPath("$.posts[0].reservedTurns[0].actionCode").value("che_증축"))
            .andExpect(jsonPath("$.posts[0].reservedTurns[0].arg.destCityID").value(5)) // W3 — 누락됐던 arg
            // 공석 칸(lv5)은 보유 장수 null.
            .andExpect(jsonPath("$.posts[7].officerLevel").value(5))
            .andExpect(jsonPath("$.posts[7].name").doesNotExist())
            .andExpect(jsonPath("$.posts[7].officerLevelText").value("사도")) // 국가 레벨 7 + lv5 → code 705
            .andExpect(jsonPath("$.posts[7].reservedTurns.length()").value(0))
            // W3 — 부대 목록(troop_leader → name).
            .andExpect(jsonPath("$.troopList.10").value("선봉대"))
            // W3 — 사령부 명령 팔레트(7 카테고리, GameConst availableChiefCommand 순서).
            .andExpect(jsonPath("$.commandList.length()").value(7))
            .andExpect(jsonPath("$.commandList[0].category").value("휴식"))
            .andExpect(jsonPath("$.commandList[1].category").value("인사"))
            .andExpect(jsonPath("$.commandList[1].values[0].value").value("che_발령"))
            .andExpect(jsonPath("$.commandList[1].values[0].simpleName").value("발령"))
            .andExpect(jsonPath("$.commandList[1].values[0].reqArg").value(true)) // 발령 = destGeneralID/destCityID
            // B1 — possible은 실제 precheck 결과. precheck mock이 null 반환(액터 상태 없음) → 레지스트리-only
            // 폴백으로 possible=true, deny reason 없음.
            .andExpect(jsonPath("$.commandList[1].values[0].possible").value(true))
            .andExpect(jsonPath("$.commandList[1].values[0].reason").doesNotExist())
            // BLOCKED(§2): autorun_limit 원천 부재 → null(직렬화 생략).
            .andExpect(jsonPath("$.autorunLimit").doesNotExist())
    }

    @Test
    fun `chief reserved for 재야 caller returns 8 empty posts with neutral nation`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "방랑", nationId = 0, officerLevel = 0)))
        `when`(world.findAll()).thenReturn(
            listOf(WorldStateReadEntity(id = 1, currentYear = 200, currentMonth = 1, tickSeconds = 3600)),
        )

        mvc(chiefCenterController()).perform(get("/api/nation/chief-reserved").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.nationId").value(0))
            .andExpect(jsonPath("$.nationName").value("재야"))
            .andExpect(jsonPath("$.nationLevel").value(0))
            .andExpect(jsonPath("$.isChief").value(false)) // officer_level 0 not > 4
            .andExpect(jsonPath("$.posts.length()").value(8))
            .andExpect(jsonPath("$.posts[0].name").doesNotExist())
            .andExpect(jsonPath("$.posts[0].reservedTurns.length()").value(0))
            .andExpect(jsonPath("$.troopList.length()").value(0))
            // 명령 팔레트는 국가 무관 정적 카테고리이므로 항상 7개.
            .andExpect(jsonPath("$.commandList.length()").value(7))
    }

    @Test
    fun `chief reserved 401 for anonymous caller`() {
        mvc(chiefCenterController()).perform(get("/api/nation/chief-reserved"))
            .andExpect(status().isUnauthorized)
    }

    // ── GET /api/nation/npc-policy (default + current, permission gate) ──────────────────────────────
    @Test
    fun `npc policy returns defaults plus current for a 관직자`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7, meta = linkedMapOf("npc_policy" to linkedMapOf("minNPCWarLeadership" to 55)))))

        mvc(NpcPolicyController(resolver, nations)).perform(get("/api/nation/npc-policy").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.defaultPolicy.reqNationGold").value(10000))
            .andExpect(jsonPath("$.defaultPolicy.reqNationRice").value(12000))
            .andExpect(jsonPath("$.defaultPolicy.reqHumanWarUprising").doesNotExist())
            .andExpect(jsonPath("$.defaultPolicy.autorun_user").doesNotExist())
            .andExpect(jsonPath("$.defaultPolicy.CombatForce.length()").value(0))
            .andExpect(jsonPath("$.defaultPolicy.minNPCWarLeadership").value(40))
            .andExpect(jsonPath("$.currentPolicy.minNPCWarLeadership").value(55))
    }

    @Test
    fun `npc policy 403 for a 재야 caller`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "방랑", nationId = 0, officerLevel = 0)))

        mvc(NpcPolicyController(resolver, nations)).perform(get("/api/nation/npc-policy").with(principal(7L)))
            .andExpect(status().isForbidden)
    }

    // ── GET /api/inherit-point (cost table + stat clamp + zeroed items) ──────────────────────────────
    @Test
    fun `inherit point returns zeroed items, clamped stat, and the GameConst cost table`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5, leadership = 95, strength = 5, intel = 80)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(gameKv.findByTableAndNamespaceAndKey(anyString(), anyString(), anyString())).thenReturn(null)
        `when`(inheritLogs.findByUserIdOrderByIdDesc("7", PageRequest.of(0, 30))).thenReturn(emptyList())

        mvc(InheritPointController(resolver, gameKv, inheritLogs, objectMapper, generals)).perform(get("/api/inherit-point").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.items.previous").value(0))
            .andExpect(jsonPath("$.maxInheritBuff").value(5))
            .andExpect(jsonPath("$.resetTurnTimeLevel").value(0))
            // calcResetAttrPoint(0) = base[0] = 1000
            .andExpect(jsonPath("$.inheritActionCost.resetTurnTime").value(1000))
            .andExpect(jsonPath("$.inheritActionCost.randomUnique").value(3000))
            .andExpect(jsonPath("$.inheritActionCost.bornStatPoint").value(1000))
            // stat clamp to [15,80](GameConst): leadership 95→80, strength 5→15, intel 80→80
            .andExpect(jsonPath("$.currentStat.leadership").value(80))
            .andExpect(jsonPath("$.currentStat.strength").value(15))
            .andExpect(jsonPath("$.currentStat.intel").value(80))
            .andExpect(jsonPath("$.currentStat.statMin").value(15))
            .andExpect(jsonPath("$.currentStat.statMax").value(80))
            .andExpect(jsonPath("$.lastInheritPointLogs.length()").value(0))
            // P0-23 — InheritCatalog 배선(v_inheritPoint.php:41-63): 더 이상 emptyMap 아님.
            .andExpect(jsonPath("$.availableSpecialWar.length()").value(20))
            .andExpect(jsonPath("$.availableUnique.length()").value(100))
            // ActionSpecialWar/che_귀병.php:12 / BaseStatItem.php:30(적토마 +15)
            .andExpect(jsonPath("$.availableSpecialWar.che_귀병.title").value("귀병"))
            .andExpect(jsonPath("$.availableUnique.che_명마_15_적토마.title").value("적토마(+15)"))
            .andExpect(jsonPath("$.availableUnique.che_명마_15_적토마.rawName").value("적토마"))
    }

    @Test
    fun `inherit point 401 for anonymous`() {
        mvc(InheritPointController(resolver, gameKv, inheritLogs, objectMapper, generals)).perform(get("/api/inherit-point"))
            .andExpect(status().isUnauthorized)
    }

    // ── W0-2(P0-25) availableTargetGeneral + (P1-043) InheritLog.date ────────────────────────────────
    @Test
    fun `inherit point exposes availableTargetGeneral npc lt 2 and log date chain`() {
        // PHP v_inheritPoint.php:19-22(SELECT no,name FROM general WHERE npc < 2 → {no: name})
        // + :74(user_record date 컬럼) + :107(availableTargetGeneral staticValue).
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(gameKv.findByTableAndNamespaceAndKey(anyString(), anyString(), anyString())).thenReturn(null)
        `when`(generals.findByNpcStateLessThanOrderByIdAsc(2)).thenReturn(
            listOf(gen(10, "순욱", nationId = 1), gen(20, "하후돈", nationId = 1)),
        )
        `when`(inheritLogs.findByUserIdOrderByIdDesc("7", PageRequest.of(0, 30))).thenReturn(
            listOf(
                InheritanceLogReadEntity(
                    id = 5, userId = "7", year = 200, month = 3, text = "유산 포인트 100 획득",
                    createdAt = Instant.parse("2026-06-01T10:30:00Z"),
                ),
            ),
        )

        mvc(InheritPointController(resolver, gameKv, inheritLogs, objectMapper, generals)).perform(get("/api/inherit-point").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.availableTargetGeneral.10").value("순욱"))
            .andExpect(jsonPath("$.availableTargetGeneral.20").value("하후돈"))
            .andExpect(jsonPath("$.lastInheritPointLogs[0].id").value(5))
            .andExpect(jsonPath("$.lastInheritPointLogs[0].date").value("2026-06-01 10:30:00"))
    }

    // ── GET /api/board (empty + 회의실/기밀실 title + secret gate) ──────────────────────────────────
    @Test
    fun `board public 회의실 returns empty articles with verbatim title`() {
        `when`(boardPosts.findByIsSecretOrderByCreatedAtDescIdDesc(false)).thenReturn(emptyList())

        mvc(BoardController(boardPosts, boardComments, resolver)).perform(get("/api/board?secret=false"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.secret").value(false))
            .andExpect(jsonPath("$.title").value("회의실"))
            .andExpect(jsonPath("$.articles.length()").value(0))
            .andExpect(jsonPath("$.blockedReason").doesNotExist())
    }

    @Test
    fun `board 기밀실 blocked for anonymous with INFO reason`() {
        mvc(BoardController(boardPosts, boardComments, resolver)).perform(get("/api/board?secret=true"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.secret").value(true))
            .andExpect(jsonPath("$.title").value("기밀실"))
            .andExpect(jsonPath("$.articles.length()").value(0))
            .andExpect(jsonPath("$.blockedReason").value("권한이 부족합니다. 수뇌부가 아닙니다."))
    }

    @Test
    fun `board 기밀실 allowed for 수뇌`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(boardPosts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, true)).thenReturn(emptyList())

        mvc(BoardController(boardPosts, boardComments, resolver)).perform(get("/api/board?secret=true").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.blockedReason").doesNotExist())
            .andExpect(jsonPath("$.articles.length()").value(0))
    }

    // ── GET /api/votes (empty list) + detail tally ───────────────────────────────────────────────────
    @Test
    fun `votes list empty when no polls`() {
        `when`(polls.findAllByOrderByIdDesc()).thenReturn(emptyList())

        mvc(VoteController(polls, votes, voteComments, generals, resolver)).perform(get("/api/votes"))
            .andExpect(status().isOk)
            // D9 envelope: {result:true, votes:Map} (PHP GetVoteList.php) — votes 맵이 비어 있어야 한다.
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.votes").isEmpty)
    }

    @Test
    fun `vote detail 404 for missing poll`() {
        `when`(polls.findById(99)).thenReturn(Optional.empty())

        mvc(VoteController(polls, votes, voteComments, generals, resolver)).perform(get("/api/votes/99"))
            .andExpect(status().isNotFound)
    }

    // ── GET /api/troops (empty when no rows) ─────────────────────────────────────────────────────────
    @Test
    fun `troops returns empty list when troop table has no rows`() {
        `when`(troops.findAll()).thenReturn(emptyList())

        mvc(TroopController(troops, generals, cities, resolver)).perform(get("/api/troops"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.troops.length()").value(0))
            // 익명 호출자 → myGeneralId/permission 0(멤버십/뮤테이션 게이팅 기준, Direction A).
            .andExpect(jsonPath("$.myGeneralId").value(0))
            .andExpect(jsonPath("$.permission").value(0))
    }

    // ── GET /api/troops (populated: myGeneralId/permission + per-member cityName/npc + leader header) ──
    @Test
    fun `troops emits myGeneralId permission member cityName npc and leader header`() {
        // 호출자(userId 7) → 장수 10(부대장, officer_level 5 → permission 2). 부대 = 선봉대(부대장 10).
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(
                GeneralReadEntity(
                    id = 10, name = "조조", nationId = 1, cityId = 5, officerLevel = 5, crew = 1000,
                    troopId = 10, npcState = 0, turnTime = Instant.parse("2026-06-03T10:30:45Z"),
                ),
            ),
        )
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(troops.findByNationOrderByTroopLeaderAsc(1)).thenReturn(
            listOf(TroopReadEntity(troopLeader = 10, nation = 1, name = "선봉대")),
        )
        `when`(cities.findAll()).thenReturn(listOf(city(5, "허창", nationId = 1), city(8, "성도", nationId = 2)))
        // 멤버: 부대장(조조, 허창) + 같은도시 멤버(하후돈, 허창) + 타도시 멤버(빙의NPC, 성도).
        `when`(generals.findByTroopIdOrderByOfficerLevelDescIdAsc(10)).thenReturn(
            listOf(
                GeneralReadEntity(id = 10, name = "조조", nationId = 1, cityId = 5, officerLevel = 5, crew = 1000, troopId = 10, npcState = 0),
                GeneralReadEntity(id = 20, name = "하후돈", nationId = 1, cityId = 5, officerLevel = 2, crew = 800, troopId = 10, npcState = 0),
                GeneralReadEntity(id = 30, name = "악진", nationId = 1, cityId = 8, officerLevel = 2, crew = 500, troopId = 10, npcState = 1),
            ),
        )

        mvc(TroopController(troops, generals, cities, resolver)).perform(get("/api/troops").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            // 멤버십/게이팅 기준(레거시 myGeneralID/myPermission). officer_level 5 → permission 2.
            .andExpect(jsonPath("$.myGeneralId").value(10))
            .andExpect(jsonPath("$.permission").value(2))
            .andExpect(jsonPath("$.troops.length()").value(1))
            .andExpect(jsonPath("$.troops[0].troopLeader").value(10))
            .andExpect(jsonPath("$.troops[0].name").value("선봉대"))
            .andExpect(jsonPath("$.troops[0].leaderName").value("조조"))
            // 부대장 카드 헤더 — 한글 도시명 + npc 티어 + turnTime(YYYY-MM-DD HH:MM:SS).
            .andExpect(jsonPath("$.troops[0].leaderCityName").value("허창"))
            .andExpect(jsonPath("$.troops[0].leaderNpc").value(0))
            .andExpect(jsonPath("$.troops[0].turnTime").value("2026-06-03 10:30:45"))
            // 예약명령 브리핑은 read 모델 미배선 → 빈 목록(날조 금지).
            .andExpect(jsonPath("$.troops[0].reservedCommandBrief.length()").value(0))
            .andExpect(jsonPath("$.troops[0].memberCount").value(3))
            // 멤버 소재 도시는 숫자 id가 아니라 한글 cityName(bug #11) + npc 티어.
            .andExpect(jsonPath("$.troops[0].members[0].name").value("조조"))
            .andExpect(jsonPath("$.troops[0].members[0].cityName").value("허창"))
            .andExpect(jsonPath("$.troops[0].members[0].npc").value(0))
            .andExpect(jsonPath("$.troops[0].members[2].name").value("악진"))
            .andExpect(jsonPath("$.troops[0].members[2].cityName").value("성도"))
            .andExpect(jsonPath("$.troops[0].members[2].npc").value(1))
    }

    // ── GET /api/history (empty record + 0 range when no yearbook rows) ───────────────────────────────
    @Test
    fun `history returns null record and zero range when yearbook table has no rows`() {
        `when`(history.findAllByOrderByYearAscMonthAsc()).thenReturn(emptyList())

        mvc(HistoryController(history, world)).perform(get("/api/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.firstYearMonth").value(0))
            .andExpect(jsonPath("$.lastYearMonth").value(0))
            .andExpect(jsonPath("$.currentYearMonth").value(0))
            .andExpect(jsonPath("$.record").value(org.hamcrest.Matchers.nullValue()))
    }
}
