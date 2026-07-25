package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralOwnerEntity
import opensamguk.gameapi.owner.GeneralOwnerRepository
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.CityReadEntity
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.gameapi.read.GeneralAccessLogReadEntity
import opensamguk.gameapi.read.GeneralAccessLogReadRepository
import opensamguk.gameapi.read.NationCrewAggregate
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
 * F2 Wave 1 slice test for [MyController] — MockMvc standalone over mocked repos. Asserts the resolved
 * my-page shape, the per-nation my-generals list (isMe flag), and the no-character contract (404 on
 * my-page, result:false empty on the list endpoints).
 */
class MyControllerTest {

    private val owners = mock(GeneralOwnerRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)
    private val cities = mock(CityReadRepository::class.java)
    private val nations = mock(NationReadRepository::class.java)
    private val accessLogs = mock(GeneralAccessLogReadRepository::class.java)
    private val world = mock(WorldStateReadRepository::class.java)
    private val resolver = GeneralResolver(owners, generals, nations)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(MyController(resolver, generals, cities, nations, world, accessLogs))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    private fun gen(id: Int, name: String, nationId: Int, cityId: Int = 0, officerLevel: Int = 1, npcState: Int = 0) =
        GeneralReadEntity(id = id, name = name, nationId = nationId, cityId = cityId, officerLevel = officerLevel, npcState = npcState)

    /** [NationCrewAggregate] 프로젝션 인터페이스의 테스트 더블(SUM crew / SUM leadership*100). */
    private fun crewAgg(nationId: Int, now: Long, max: Long, generalCnt: Long = 0) = object : NationCrewAggregate {
        override val nationId = nationId
        override val generalCnt = generalCnt
        override val now = now
        override val max = max
    }

    @Test
    fun `my-page returns the resolved general detail`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, cityId = 5, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))
        `when`(cities.findAll()).thenReturn(emptyList())

        mockMvc().perform(get("/api/my-page").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generalId").value(10))
            .andExpect(jsonPath("$.name").value("순욱"))
            .andExpect(jsonPath("$.nationName").value("위"))
            .andExpect(jsonPath("$.cityName").value("허창"))
            .andExpect(jsonPath("$.officerLevel").value(5))
            .andExpect(jsonPath("$.permission").value(2)) // officer_level 5 → 수뇌 (showSecret)
            .andExpect(jsonPath("$.items.length()").value(4))
            .andExpect(jsonPath("$.items[0].type").value("horse"))
            .andExpect(jsonPath("$.items[0].name").value("-"))
            .andExpect(jsonPath("$.items[0].droppable").value(false))
            .andExpect(jsonPath("$.instantActions.instantRetreatPossible").value(false))
            .andExpect(jsonPath("$.instantActions.dieOnPrestartPossible").value(false))
    }

    @Test
    fun `my-page emits item slots and instant action flags when read gates allow them`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(
            Optional.of(
                GeneralReadEntity(
                    id = 10,
                    name = "순욱",
                    nationId = 0,
                    cityId = 5,
                    horseCode = "che_명마_15_적토마",
                    weaponCode = "None",
                    bookCode = "che_서적_03_손자병법",
                    itemCode = "None",
                ),
            ),
        )
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 0)))
        `when`(cities.findAll()).thenReturn(emptyList())
        `when`(world.findProcessWorld()).thenReturn(
            WorldStateReadEntity(
                tickSeconds = 60,
                config = linkedMapOf(
                    "turntime" to "2026-01-01 00:00:00",
                    "opentime" to "2026-01-01 00:00:00",
                    "turnterm" to 1,
                ),
            ),
        )
        `when`(accessLogs.findByGeneralId(10)).thenReturn(
            GeneralAccessLogReadEntity(id = 1, generalId = 10, lastRefresh = Instant.parse("2026-01-01T00:00:00Z")),
        )

        mockMvc().perform(get("/api/my-page").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].type").value("horse"))
            .andExpect(jsonPath("$.items[0].code").value("che_명마_15_적토마"))
            .andExpect(jsonPath("$.items[0].name").value("적토마(+15)"))
            .andExpect(jsonPath("$.items[0].droppable").value(true))
            .andExpect(jsonPath("$.items[1].droppable").value(false))
            .andExpect(jsonPath("$.items[2].type").value("book"))
            .andExpect(jsonPath("$.items[2].name").value("손자병법(+3)"))
            .andExpect(jsonPath("$.instantActions.instantRetreatPossible").value(false))
            .andExpect(jsonPath("$.instantActions.dieOnPrestartPossible").value(true))
    }

    @Test
    fun `my-page marks instant retreat possible for a joined general with a destination city`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, cityId = 5, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(cities.findById(5)).thenReturn(Optional.of(CityReadEntity(id = 5, name = "허창", nationId = 1)))
        `when`(cities.findAll()).thenReturn(
            listOf(
                CityReadEntity(id = 5, name = "허창", nationId = 1),
                CityReadEntity(id = 6, name = "업", nationId = 2),
            ),
        )

        mockMvc().perform(get("/api/my-page").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.instantActions.instantRetreatPossible").value(true))
            .andExpect(jsonPath("$.instantActions.dieOnPrestartPossible").value(false))
    }

    @Test
    fun `my-page 404 when the caller has no character`() {
        `when`(owners.findByUserId(7L)).thenReturn(null)

        mockMvc().perform(get("/api/my-page").with(principal(7L)))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `my-generals lists the nation roster with the isMe flag`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)).thenReturn(
            listOf(gen(1, "조조", nationId = 1, officerLevel = 12), gen(10, "순욱", nationId = 1, officerLevel = 5)),
        )
        mockMvc().perform(get("/api/my-generals").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.nationId").value(1))
            .andExpect(jsonPath("$.generals.length()").value(2))
            .andExpect(jsonPath("$.generals[0].name").value("조조"))
            .andExpect(jsonPath("$.generals[0].mine").value(false))
            .andExpect(jsonPath("$.generals[1].name").value("순욱"))
            .andExpect(jsonPath("$.generals[1].mine").value(true))
    }

    @Test
    fun `my-generals emits b_myGenInfo columns - korean text, bill, gold, belong, lbonus`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)).thenReturn(
            listOf(
                GeneralReadEntity(
                    id = 1, name = "조조", nationId = 1, officerLevel = 12,
                    leadership = 90, strength = 80, intel = 95, gold = 5000, rice = 3000,
                    dedication = 10000, experience = 50000, injury = 0,
                    picture = "chocho.jpg", imageServer = 2,
                    personalCode = "che_정복", specialCode = "None", special2Code = "None",
                    meta = linkedMapOf("belong" to 12),
                ),
            ),
        )
        `when`(accessLogs.findByGeneralIdIn(listOf(1))).thenReturn(
            listOf(GeneralAccessLogReadEntity(id = 1, generalId = 1, refreshScoreTotal = 88)),
        )

        mockMvc().perform(get("/api/my-generals").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generals[0].name").value("조조"))
            // 관직(officerLevel 12, nationLevel 7) → 황제. 통솔보너스 calcLeadershipBonus(12,7)=14.
            .andExpect(jsonPath("$.generals[0].officerLevelText").value("황제"))
            .andExpect(jsonPath("$.generals[0].lbonus").value(14))
            // 자금/군량/사관(belong)/봉록(getBill(dedication)).
            .andExpect(jsonPath("$.generals[0].gold").value(5000))
            .andExpect(jsonPath("$.generals[0].rice").value(3000))
            .andExpect(jsonPath("$.generals[0].belong").value(12))
            .andExpect(jsonPath("$.generals[0].personalText").value("정복"))
            .andExpect(jsonPath("$.generals[0].specialDomesticText").value("-"))
            .andExpect(jsonPath("$.generals[0].refreshScoreTotal").value(88))
    }

    @Test
    fun `my-generals emits raw sort-key columns - W0-2 P1-071 072 075`() {
        // PHP b_myGenInfo.php:90-110 — 정렬 키는 raw 컬럼(dedication/experience/personal/special/
        // special2) DESC. 한글 해석값과 별개로 raw 코드/원값을 그대로 배출해야 FE가 PHP usort를 재현한다.
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)).thenReturn(
            listOf(
                GeneralReadEntity(
                    id = 1, name = "조조", nationId = 1, officerLevel = 12,
                    dedication = 10000, experience = 50000,
                    personalCode = "che_정복", specialCode = "che_상재", special2Code = "che_귀모",
                    // isunited 시 소유 플레이어명(b_myGenInfo.php:31-36,155-157) — meta.owner_name 방어적 read.
                    meta = linkedMapOf("owner_name" to "페포네"),
                ),
            ),
        )

        mockMvc().perform(get("/api/my-generals").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generals[0].dedication").value(10000))
            .andExpect(jsonPath("$.generals[0].experience").value(50000))
            .andExpect(jsonPath("$.generals[0].personal").value("che_정복"))
            .andExpect(jsonPath("$.generals[0].special").value("che_상재"))
            .andExpect(jsonPath("$.generals[0].special2").value("che_귀모"))
            .andExpect(jsonPath("$.generals[0].ownerName").value("페포네"))
    }

    @Test
    fun `my-generals ownerName is null when meta has no owner_name`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)).thenReturn(
            listOf(gen(10, "순욱", nationId = 1, officerLevel = 5)),
        )

        mockMvc().perform(get("/api/my-generals").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generals[0].ownerName").doesNotExist())
    }

    @Test
    fun `my-generals returns result-false empty when no character`() {
        `when`(owners.findByUserId(7L)).thenReturn(null)

        mockMvc().perform(get("/api/my-generals").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(false))
            .andExpect(jsonPath("$.generals.length()").value(0))
    }

    @Test
    fun `my-boss returns the highest officer in the nation`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#00f", level = 7)))
        `when`(generals.findFirstByNationIdOrderByOfficerLevelDesc(1)).thenReturn(gen(1, "조조", nationId = 1, officerLevel = 12))

        mockMvc().perform(get("/api/my-boss").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.hasBoss").value(true))
            .andExpect(jsonPath("$.bossName").value("조조"))
            .andExpect(jsonPath("$.bossOfficerLevel").value(12))
    }

    // ── b_myKingdomInfo (my-nation-detail) 계약 버그 수정 회귀 ──────────────────────────────────────

    @Test
    fun `my-nation-detail emits the 19-field single-table contract`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        // nation: level 7(작위 황제), power 9999, tech 123.7(floor 123), gennum 8, rate 25, bill 30, 수도 5.
        `when`(nations.findById(1)).thenReturn(
            Optional.of(
                NationReadEntity(
                    id = 1, name = "위", color = "#0000FF", level = 7, gold = 50000, rice = 40000,
                    tech = 123.7, power = 9999, capitalCityId = 5,
                    meta = linkedMapOf("gennum" to 8, "rate" to 25, "bill" to 30),
                ),
            ),
        )
        // 보유 도시 2개(수도 5 = 허창, 일반 6 = 완) — currPop=300, maxPop=500.
        `when`(cities.findByNationIdOrderByIdAsc(1)).thenReturn(
            listOf(
                CityReadEntity(id = 5, name = "허창", nationId = 1, population = 200, populationMax = 300),
                CityReadEntity(id = 6, name = "완", nationId = 1, population = 100, populationMax = 200),
            ),
        )
        // 총병사 집계: crew 1000, leadership*100 = 1800.
        `when`(nations.aggregateCrewOfNation(1)).thenReturn(crewAgg(1, now = 1000, max = 1800, generalCnt = 8))

        mockMvc().perform(get("/api/my-nation-detail").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.hasNation").value(true))
            .andExpect(jsonPath("$.name").value("위"))
            .andExpect(jsonPath("$.color").value("#0000FF"))
            // 총주민/총병사
            .andExpect(jsonPath("$.population").value(300))
            .andExpect(jsonPath("$.populationMax").value(500))
            .andExpect(jsonPath("$.crew").value(1000))
            .andExpect(jsonPath("$.crewMax").value(1800))
            // 국력/국고/병량/속령수/장수수
            .andExpect(jsonPath("$.power").value(9999))
            .andExpect(jsonPath("$.gold").value(50000))
            .andExpect(jsonPath("$.rice").value(40000))
            .andExpect(jsonPath("$.cityCount").value(2))
            .andExpect(jsonPath("$.generalCount").value(8))
            // 기술력 floor(123.7)=123, 작위 level 7 → 황제
            .andExpect(jsonPath("$.tech").value(123))
            .andExpect(jsonPath("$.levelText").value("황제"))
            .andExpect(jsonPath("$.level").value(7))
            // 속령일람 — 수도(5=허창)만 isCapital true
            .andExpect(jsonPath("$.cities.length()").value(2))
            .andExpect(jsonPath("$.cities[0].name").value("허창"))
            .andExpect(jsonPath("$.cities[0].isCapital").value(true))
            .andExpect(jsonPath("$.cities[1].name").value("완"))
            .andExpect(jsonPath("$.cities[1].isCapital").value(false))
            // 세율/지급률 — meta에서 방어적 read(존재 시 노출)
            .andExpect(jsonPath("$.taxRate").value(25))
            .andExpect(jsonPath("$.bill").value(30))
            // legacy엔 없던 장수표/도시표는 더 이상 없다 — nation/generals 래퍼 부재(계약 버그 수정).
            .andExpect(jsonPath("$.nation").doesNotExist())
            .andExpect(jsonPath("$.generals").doesNotExist())
    }

    @Test
    fun `my-nation-detail leaves rate-bill null when meta lacks them - no fabrication`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#0000FF", level = 7)))
        `when`(cities.findByNationIdOrderByIdAsc(1)).thenReturn(emptyList())
        `when`(nations.aggregateCrewOfNation(1)).thenReturn(null)

        mockMvc().perform(get("/api/my-nation-detail").with(principal(7L)))
            .andExpect(status().isOk)
            // §2 BLOCKED — meta UNVERIFIED 부재 시 날조 금지로 null.
            .andExpect(jsonPath("$.taxRate").doesNotExist())
            .andExpect(jsonPath("$.bill").doesNotExist())
            // 집계 결손(도시/장수 0) → 0 보정.
            .andExpect(jsonPath("$.population").value(0))
            .andExpect(jsonPath("$.crew").value(0))
            .andExpect(jsonPath("$.crewMax").value(0))
    }

    @Test
    fun `my-nation-detail returns hasNation false for 재야`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "방랑", nationId = 0, officerLevel = 0)))

        mockMvc().perform(get("/api/my-nation-detail").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.hasNation").value(false))
    }

    // ── b_myCityInfo (my-cities) 도시 카드 필드 ──────────────────────────────────────────────────

    @Test
    fun `my-cities emits per-city card fields - levelText regionText capital trade officers generals`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#0000FF", level = 7, capitalCityId = 5)))
        // 도시 5(수도 허창, 특(8)/중원(2), 시세 105, 민심 77.5)
        `when`(cities.findByNationIdOrderByIdAsc(1)).thenReturn(
            listOf(
                CityReadEntity(
                    id = 5, name = "허창", nationId = 1, level = 8, region = 2,
                    population = 200, populationMax = 400,
                    agriculture = 120, agricultureMax = 150, commerce = 110, commerceMax = 140,
                    security = 90, securityMax = 100, defense = 117, defenseMax = 125,
                    wall = 120, wallMax = 125, trust = 77.5, trade = 105,
                ),
            ),
        )
        // 도시 소재 장수(city_id == 5): 순욱(user, npc 0) + 조조(npc 2)
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)).thenReturn(
            listOf(
                gen(1, "조조", nationId = 1, cityId = 5, officerLevel = 12, npcState = 2),
                gen(10, "순욱", nationId = 1, cityId = 5, officerLevel = 5, npcState = 0),
            ),
        )
        // 관직자(officer_city == 5, level∈{4,3,2}): 태수 4 = 하후돈(npc 1), 군사/종사 공석.
        `when`(generals.findByOfficerCityAndOfficerLevelInOrderByIdAsc(5, listOf(4, 3, 2))).thenReturn(
            listOf(gen(3, "하후돈", nationId = 1, cityId = 5, officerLevel = 4, npcState = 1)),
        )

        mockMvc().perform(get("/api/my-cities").with(principal(7L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.cities.length()").value(1))
            .andExpect(jsonPath("$.cities[0].name").value("허창"))
            // 등급(특)/지역(중원)/수도 강조
            .andExpect(jsonPath("$.cities[0].levelText").value("특"))
            .andExpect(jsonPath("$.cities[0].regionText").value("중원"))
            .andExpect(jsonPath("$.cities[0].isCapital").value(true))
            // 개발치 cur/max
            .andExpect(jsonPath("$.cities[0].agriculture").value(120))
            .andExpect(jsonPath("$.cities[0].agricultureMax").value(150))
            .andExpect(jsonPath("$.cities[0].wallMax").value(125))
            // 민심/시세
            .andExpect(jsonPath("$.cities[0].trust").value(77.5))
            .andExpect(jsonPath("$.cities[0].trade").value(105))
            // 관직자: 태수=하후돈(npc1), 군사/종사 공석(null)
            .andExpect(jsonPath("$.cities[0].governorName").value("하후돈"))
            .andExpect(jsonPath("$.cities[0].governorNpc").value(1))
            .andExpect(jsonPath("$.cities[0].strategistName").doesNotExist())
            .andExpect(jsonPath("$.cities[0].secretaryName").doesNotExist())
            // 도시 소재 장수(id ASC): 조조, 순욱
            .andExpect(jsonPath("$.cities[0].generals.length()").value(2))
            .andExpect(jsonPath("$.cities[0].generals[0].name").value("조조"))
            .andExpect(jsonPath("$.cities[0].generals[0].npc").value(2))
            .andExpect(jsonPath("$.cities[0].generals[1].name").value("순욱"))
    }

    @Test
    fun `my-cities keeps trade null when the column is null - 시세 verbatim guard`() {
        `when`(owners.findByUserId(7L)).thenReturn(GeneralOwnerEntity(generalId = 10L, userId = 7L, claimedAt = Instant.EPOCH))
        `when`(generals.findById(10)).thenReturn(Optional.of(gen(10, "순욱", nationId = 1, officerLevel = 5)))
        `when`(nations.findById(1)).thenReturn(Optional.of(NationReadEntity(id = 1, name = "위", color = "#0000FF", level = 7, capitalCityId = 5)))
        `when`(cities.findByNationIdOrderByIdAsc(1)).thenReturn(
            listOf(CityReadEntity(id = 6, name = "완", nationId = 1, level = 7, region = 2, trade = null)),
        )
        `when`(generals.findByNationIdOrderByOfficerLevelDescIdAsc(1)).thenReturn(emptyList())
        `when`(generals.findByOfficerCityAndOfficerLevelInOrderByIdAsc(6, listOf(4, 3, 2))).thenReturn(emptyList())

        mockMvc().perform(get("/api/my-cities").with(principal(7L)))
            .andExpect(status().isOk)
            // 시세 null → FE가 "- " verbatim. BE는 날조 금지로 null 유지(필드 부재).
            .andExpect(jsonPath("$.cities[0].trade").doesNotExist())
            // 소재 장수 0명 → 빈 리스트
            .andExpect(jsonPath("$.cities[0].generals.length()").value(0))
    }
}
