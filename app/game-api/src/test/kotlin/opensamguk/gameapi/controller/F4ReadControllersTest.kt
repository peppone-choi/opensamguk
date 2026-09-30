package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.reserve.CommandReserveService
import opensamguk.gameapi.read.BoardCommentReadRepository
import opensamguk.gameapi.read.BoardPostReadLogRepository
import opensamguk.gameapi.read.BoardPostReadRepository
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.DiplomacyLetterReadEntity
import opensamguk.gameapi.read.DiplomacyLetterReadRepository
import opensamguk.gameapi.read.DiplomacyReadEntity
import opensamguk.gameapi.read.DiplomacyReadRepository
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralAccessLogReadEntity
import opensamguk.gameapi.read.GeneralAccessLogReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.HistoryReadRepository
import opensamguk.gameapi.read.HistoryJsonValue
import opensamguk.gameapi.read.YearbookHistoryReadEntity
import opensamguk.gameapi.read.InheritanceLogReadEntity
import opensamguk.gameapi.read.InheritanceLogReadRepository
import opensamguk.gameapi.read.NationReadEntity
import opensamguk.gameapi.read.NationEnvReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.SecretPermissionReader
import opensamguk.gameapi.read.TroopReadEntity
import opensamguk.gameapi.read.TroopReadRepository
import opensamguk.gameapi.read.VoteCommentReadRepository
import opensamguk.gameapi.read.VotePollReadRepository
import opensamguk.gameapi.read.VoteReadRepository
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.entity.NationEnvEntity
import opensamguk.logic.domestic.getOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.domain.PageRequest
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
import java.time.Instant
import java.util.Optional

/**
 * 기존 화면이 쓰는 읽기 컨트롤러의 응답·권한 검사. 저장소는 mock이며 Spring 컨텍스트와 DB를 쓰지 않는다.
 */
class F4ReadControllersTest {
    @BeforeEach
    fun clearAuthentication() = SecurityContextHolder.clearContext()

    private val generals = mock(GeneralReadRepository::class.java)
    private val accessLogs = mock(GeneralAccessLogReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val owners = mock(GeneralOwnerRepository::class.java)
    private val resolver = fixtureGeneralResolver(owners, generals, nations)
    private val letters = mock(DiplomacyLetterReadRepository::class.java)
    private val diplomacy = mock(DiplomacyReadRepository::class.java)
    private val gameKv = mock(GameKvReadRepository::class.java)
    private val inheritLogs = mock(InheritanceLogReadRepository::class.java)
    private val boardPosts = mock(BoardPostReadRepository::class.java)
    private val boardComments = mock(BoardCommentReadRepository::class.java)
    private val boardReads = mock(BoardPostReadLogRepository::class.java)
    private val polls = mock(VotePollReadRepository::class.java)
    private val votes = mock(VoteReadRepository::class.java)
    private val voteComments = mock(VoteCommentReadRepository::class.java)
    private val troops = mock(TroopReadRepository::class.java)
    private val history = mock(HistoryReadRepository::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    private val objectMapper = ObjectMapper()
    // nation_env(V3) read mock — 스텁 미설정 시 findByNamespaceAndKey가 null 반환 → nationMsg/scoutMsg/remain null(기존 BLOCKED 동작 보존).
    private val nationEnv = mock(NationEnvReadRepository::class.java)

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
        `when`(accessLogs.findByGeneralIdIn(listOf(1, 2))).thenReturn(
            listOf(GeneralAccessLogReadEntity(id = 1, generalId = 1, refreshScoreTotal = 73)),
        )

        mvc(GeneralsController(generals, nations, cities, accessLogs)).perform(get("/api/generals"))
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
            .andExpect(jsonPath("$[0].refreshScoreTotal").value(73))
            .andExpect(jsonPath("$[1].refreshScoreTotal").value(0))
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

    private fun ownedBoardGeneral(nationId: Int) {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = nationId, officerLevel = 0)))
    }
    // ── GET /api/board (empty + 회의실/기밀실 title + secret gate) ──────────────────────────────────
    @Test
    fun `board nation 회의실 returns empty articles with verbatim title`() {
        ownedBoardGeneral(1)
        `when`(boardPosts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, false)).thenReturn(emptyList())

        mvc(BoardController(boardPosts, boardComments, resolver, generals, polls, votes, boardReads, world)).perform(get("/api/board?secret=false").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.secret").value(false))
            .andExpect(jsonPath("$.title").value("회의실"))
            .andExpect(jsonPath("$.articles.length()").value(0))
            .andExpect(jsonPath("$.blockedReason").doesNotExist())
    }

    @Test
    fun `board 기밀실 rejects anonymous caller`() {
        mvc(BoardController(boardPosts, boardComments, resolver, generals, polls, votes, boardReads, world))
            .perform(get("/api/board?secret=true"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `board 기밀실 allowed for 수뇌`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(boardPosts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, true)).thenReturn(emptyList())

        mvc(BoardController(boardPosts, boardComments, resolver, generals, polls, votes, boardReads, world)).perform(get("/api/board?secret=true").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.blockedReason").doesNotExist())
            .andExpect(jsonPath("$.articles.length()").value(0))
    }

    @Test
    fun `board 기밀실 lists kind, readers, chief count and participants from read sources only`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(nation(1, "위", level = 7)))
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)).thenReturn(
            listOf(gen(10, "순욱", nationId = 1, officerLevel = 5), gen(11, "곽가", nationId = 1, officerLevel = 1)),
        )
        `when`(boardPosts.findByNationIdAndIsSecretOrderByCreatedAtDescIdDesc(1, true)).thenReturn(
            listOf(
                opensamguk.gameapi.read.BoardPostReadEntity(
                    id = 5, worldId = 1, nationId = 1, isSecret = true, authorGeneralId = 10, authorName = "순욱",
                    title = "원소 불가침", contentHtml = "회신 미루자", createdAt = Instant.EPOCH, kind = "notice",
                ),
            ),
        )
        `when`(boardComments.findByPostIdOrderByCreatedAtAscIdAsc(5)).thenReturn(emptyList())
        `when`(boardReads.findByPostIds(listOf(5))).thenReturn(
            listOf(opensamguk.gameapi.read.BoardPostReadLogEntity(id = 1, worldId = 1, postId = 5, generalId = 10, readAt = Instant.EPOCH)),
        )

        mvc(BoardController(boardPosts, boardComments, resolver, generals, polls, votes, boardReads, world))
            .perform(get("/api/board?secret=true").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.articles[0].kind").value("notice"))
            .andExpect(jsonPath("$.articles[0].readers.read[0].generalId").value(10))
            .andExpect(jsonPath("$.articles[0].readers.total").value(1))
            .andExpect(jsonPath("$.articles[0].authorGeneralId").value(10))
            .andExpect(jsonPath("$.chiefCount").value(1))
            .andExpect(jsonPath("$.myGeneralId").value(10))
            .andExpect(jsonPath("$.myPermission").value(2))
            .andExpect(jsonPath("$.participants.length()").value(2))
            // tick 원천이 없으면(월드 상태 없음) 활동 판정은 전부 false — 날조 없음.
            .andExpect(jsonPath("$.participants[0].active").value(false))
    }

    // ── GET /api/troops (empty when no rows) ─────────────────────────────────────────────────────────
    @Test
    fun `troops returns empty list when own nation troop table has no rows`() {
        ownedBoardGeneral(1)
        `when`(troops.findByNationOrderByTroopLeaderAsc(1)).thenReturn(emptyList())

        mvc(TroopController(troops, generals, cities, resolver)).perform(get("/api/troops").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.troops.length()").value(0))
            // 인증된 본인 장수 id를 유지하고 일반 장수 permission=0을 반환한다.
            .andExpect(jsonPath("$.myGeneralId").value(10))
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
            .andExpect(jsonPath("$.troops[0].turnTime").value("2026-06-03 19:30:45"))
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

    // ── GET /api/history (empty record + legacy current-server range when no yearbook rows) ────────────
    @Test
    fun `history anchors empty yearbook range to previous live month`() {
        `when`(history.findAllByOrderByYearAscMonthAsc()).thenReturn(emptyList())
        `when`(world.findProcessWorld()).thenReturn(
            WorldStateReadEntity(
                id = 1,
                scenarioCode = "scenario_1021",
                currentYear = 190,
                currentMonth = 7,
                tickSeconds = 3600,
            ),
        )

        mvc(HistoryController(history, world)).perform(get("/api/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.firstYearMonth").value(2285))
            .andExpect(jsonPath("$.lastYearMonth").value(2285))
            .andExpect(jsonPath("$.currentYearMonth").value(2286))
            .andExpect(jsonPath("$.serverId").value("scenario_1021"))
            .andExpect(jsonPath("$.mapName").value("scenario_1021"))
            .andExpect(jsonPath("$.record").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `history returns zero range only when yearbook and world state are both empty`() {
        `when`(history.findAllByOrderByYearAscMonthAsc()).thenReturn(emptyList())
        `when`(world.findProcessWorld()).thenReturn(null)

        mvc(HistoryController(history, world)).perform(get("/api/history"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.firstYearMonth").value(0))
            .andExpect(jsonPath("$.lastYearMonth").value(0))
            .andExpect(jsonPath("$.currentYearMonth").value(0))
            .andExpect(jsonPath("$.record").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `history projects stored global logs with its archived map snapshot`() {
        `when`(history.findAllByOrderByYearAscMonthAsc()).thenReturn(
            listOf(
                YearbookHistoryReadEntity(
                    id = 7,
                    year = 190,
                    month = 6,
                    map = linkedMapOf("year" to 190, "month" to 6, "cityList" to emptyList<Any?>()),
                    nations = HistoryJsonValue(
                        listOf(
                            linkedMapOf(
                                "nation" to 1,
                                "name" to "촉",
                                "color" to "#2e7d32",
                                "power" to 500,
                                "gennum" to 2,
                                "cities" to listOf("성도"),
                            ),
                        ),
                    ),
                    globalHistory = listOf("<C>●</>190년 6월: 중원 정세"),
                    globalAction = listOf("<Y>관우</>의 동향"),
                    hash = "archive-hash",
                ),
            ),
        )
        `when`(world.findProcessWorld()).thenReturn(
            WorldStateReadEntity(id = 1, scenarioCode = "scenario_1010", currentYear = 190, currentMonth = 6),
        )

        mvc(HistoryController(history, world)).perform(get("/api/history").param("yearMonth", "2285"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.record.map.year").value(190))
            .andExpect(jsonPath("$.record.nations[0].name").value("촉"))
            .andExpect(jsonPath("$.record.globalHistory[0]").value("<C>●</>190년 6월: 중원 정세"))
            .andExpect(jsonPath("$.record.globalAction[0]").value("<Y>관우</>의 동향"))
    }

    @Test
    fun `history never relabels the latest archive as an unrecorded current month`() {
        `when`(history.findAllByOrderByYearAscMonthAsc()).thenReturn(
            listOf(
                YearbookHistoryReadEntity(
                    id = 8,
                    year = 190,
                    month = 6,
                    map = linkedMapOf("year" to 190),
                    nations = HistoryJsonValue(),
                    globalHistory = emptyList(),
                    globalAction = emptyList(),
                    hash = "archive-only",
                ),
            ),
        )
        `when`(world.findProcessWorld()).thenReturn(
            WorldStateReadEntity(id = 1, scenarioCode = "scenario_1010", currentYear = 190, currentMonth = 7),
        )

        mvc(HistoryController(history, world)).perform(get("/api/history").param("yearMonth", "2286"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.currentYearMonth").value(2286))
            .andExpect(jsonPath("$.record").value(org.hamcrest.Matchers.nullValue()))
    }
}
