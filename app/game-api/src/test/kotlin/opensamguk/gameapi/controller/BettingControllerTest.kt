package opensamguk.gameapi.controller

import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.entity.GameKvEntity
import opensamguk.infra.read.BettingRepository
import opensamguk.infra.read.BettingTotalAggregate
import opensamguk.infra.read.BettingTypeAggregate
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Optional

/**
 * D4-D5 — [BettingController] 슬라이스 테스트(MockMvc standalone + mocked 레포 + 주체 리졸버).
 *
 * PHP grand truth 봉투:
 *  - D4 `GetBettingList.php:67-71/86-90` → `{result:true, bettingList(Map<id,item>), year, month}`,
 *    `req`(type) 화이트리스트 `['bettingNation','tournament']`(외 값 400).
 *  - D5 `GetBettingDetail.php:86-94` → **정확히 7키** `{result, bettingInfo, bettingDetail, myBetting,
 *    remainPoint, year, month}`. `bettingInfo`는 raw 배열(candidates 인라인 + winner + isHtml). userCnt 없음.
 *
 * 검증:
 *  - 배당(odds)은 절대 포함하지 않음
 *  - 마스터 부재 시 D5는 404 + '해당 베팅이 없습니다'(데이터 패러티)
 *  - D5는 per-OWNER → `@AuthenticationPrincipal` 필요(미인증 401)
 */
class BettingControllerTest {

    private val betting = mock(BettingRepository::class.java)
    private val kv = mock(GameKvReadRepository::class.java)
    private val worldState = mock(WorldStateReadRepository::class.java)
    private val resolver = mock(GeneralResolver::class.java)

    // SecurityContextHolder는 스레드로컬 정적이라 테스트 간 누설된다 → 매 테스트 전후 비워 미인증(401) 결정성 보장.
    @BeforeEach
    fun clearSecurityContextBefore() = SecurityContextHolder.clearContext()

    @AfterEach
    fun clearSecurityContextAfter() = SecurityContextHolder.clearContext()

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(BettingController(betting, kv, worldState, resolver))
            .setCustomArgumentResolvers(AuthenticationPrincipalArgumentResolver())
            .build()

    /** 검증된 userId 주체 주입(슬라이스에 실제 JWT 필터 없음 — Possession 테스트 동일 패턴). */
    private fun principal(userId: Long): RequestPostProcessor = RequestPostProcessor { req ->
        org.springframework.security.core.context.SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(userId, null, listOf(SimpleGrantedAuthority("ROLE_USER")))
        req
    }

    /** BettingTypeAggregate projection 더미. */
    private fun agg(type: String, sum: Long) = object : BettingTypeAggregate {
        override val bettingType = type
        override val sumAmount = sum
    }

    private fun totalAgg(bettingId: Int, sum: Long) = object : BettingTotalAggregate {
        override val bettingId = bettingId
        override val sumAmount = sum
    }

    private fun mockWorld(year: Int = 2400, month: Int = 1) {
        `when`(worldState.findById(1)).thenReturn(
            Optional.of(WorldStateReadEntity(currentYear = year, currentMonth = month)),
        )
    }

    /** resolver.resolve(userId) → 소유 general(gold)로 ResolvedGeneral 반환. */
    private fun mockResolved(userId: Long, gold: Int = 0) {
        val g = GeneralReadEntity(id = userId.toInt(), gold = gold)
        `when`(resolver.resolve(userId)).thenReturn(
            GeneralResolver.ResolvedGeneral(
                general = g,
                officerLevel = 0,
                permission = 0,
                nationId = 0,
                nationLevel = 0,
            ),
        )
    }

    // ── D4: list ──

    @Test
    fun `list returns result true with bettingList map and year and month`() {
        mockWorld(2400, 6)
        val masterJson = """
            {"id":3,"type":"bettingNation","name":"국가 강약 내기","finished":false,
             "selectCnt":1,"isExclusive":null,"reqInheritancePoint":false,
             "openYearMonth":2400,"closeYearMonth":2520,
             "candidates":{"0":{"title":"국가 1","aux":{"nation":1}},
                           "1":{"title":"국가 2","aux":{"nation":2}}}}
        """.trimIndent()
        `when`(kv.findAll()).thenReturn(
            listOf(
                GameKvEntity(table = "betting", namespace = "betting", key = "id_3", value = masterJson, id = 2),
            ),
        )
        `when`(betting.aggregateTotalAmountByBetting()).thenReturn(
            listOf(totalAgg(3, 4000L)),
        )

        mockMvc().perform(get("/api/bettings"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.year").value(2400))
            .andExpect(jsonPath("$.month").value(6))
            .andExpect(jsonPath("$.bettingList.3.id").value(3))
            .andExpect(jsonPath("$.bettingList.3.type").value("bettingNation"))
            .andExpect(jsonPath("$.bettingList.3.name").value("국가 강약 내기"))
            .andExpect(jsonPath("$.bettingList.3.finished").value(false))
            .andExpect(jsonPath("$.bettingList.3.selectCnt").value(1))
            .andExpect(jsonPath("$.bettingList.3.totalAmount").value(4000))
            // candidates는 D4에서 제거 (PHP unset)
            .andExpect(jsonPath("$.bettingList.3.candidates").doesNotExist())
    }

    @Test
    fun `list filters by req type parameter`() {
        mockWorld()
        val nationJson = """{"id":1,"type":"bettingNation","name":"NationBet","finished":false,"selectCnt":1,"reqInheritancePoint":false,"openYearMonth":0,"closeYearMonth":0}"""
        val tourJson = """{"id":2,"type":"tournament","name":"TourBet","finished":false,"selectCnt":1,"reqInheritancePoint":false,"openYearMonth":0,"closeYearMonth":0}"""
        `when`(kv.findAll()).thenReturn(
            listOf(
                GameKvEntity(table = "betting", namespace = "betting", key = "id_1", value = nationJson, id = 1),
                GameKvEntity(table = "betting", namespace = "betting", key = "id_2", value = tourJson, id = 2),
            ),
        )
        `when`(betting.aggregateTotalAmountByBetting()).thenReturn(emptyList())

        mockMvc().perform(get("/api/bettings").param("type", "bettingNation"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bettingList.1").exists())
            .andExpect(jsonPath("$.bettingList.2").doesNotExist())
    }

    @Test
    fun `list rejects req type not in whitelist with 400`() {
        // PHP Validator `in 'req' ['bettingNation','tournament']` — 외 값은 빈 목록으로 삼키지 않고 400.
        mockMvc().perform(get("/api/bettings").param("type", "req"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `list with no betting masters returns empty map`() {
        // world_state 미존재(findById Optional.empty) → year/month graceful 0 (production: world?.currentYear ?: 0)
        `when`(kv.findAll()).thenReturn(emptyList())
        `when`(betting.aggregateTotalAmountByBetting()).thenReturn(emptyList())

        mockMvc().perform(get("/api/bettings"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.bettingList").isEmpty)
            .andExpect(jsonPath("$.year").value(0))
            .andExpect(jsonPath("$.month").value(0))
    }

    // ── D5: detail ──

    @Test
    fun `detail returns 7-key envelope with raw bettingInfo bettingDetail myBetting remainPoint year month`() {
        mockWorld(2400, 3)
        mockResolved(100L, gold = 9999)
        val masterJson = """
            {"id":3,"type":"bettingNation","name":"국가 강약 내기","finished":false,
             "selectCnt":1,"isExclusive":null,"reqInheritancePoint":false,
             "openYearMonth":2400,"closeYearMonth":2520,
             "winner":[1],
             "candidates":{"0":{"title":"국가 1","isHtml":true,"info":"설명","aux":{"nation":1}},
                           "1":{"title":"국가 2","aux":{"nation":2}}}}
        """.trimIndent()
        `when`(kv.findAll()).thenReturn(
            listOf(
                GameKvEntity(table = "game_env", namespace = "game_env", key = "year", value = "2400", id = 1),
                GameKvEntity(table = "betting", namespace = "betting", key = "id_3", value = masterJson, id = 2),
            ),
        )
        `when`(betting.aggregateAmountByType(3)).thenReturn(
            listOf(agg("[0]", 1500L), agg("[1]", 2500L)),
        )
        `when`(betting.aggregateAmountByTypeForUser(3, 100)).thenReturn(
            listOf(agg("[0]", 500L)),
        )

        mockMvc().perform(get("/api/bettings/3/detail").with(principal(100L)))
            .andExpect(status().isOk)
            // 정확히 7키 (userCnt/market/candidates 분리 없음)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.userCnt").doesNotExist())
            .andExpect(jsonPath("$.market").doesNotExist())
            .andExpect(jsonPath("$.candidates").doesNotExist())
            .andExpect(jsonPath("$.bettingId").doesNotExist())
            // bettingInfo = raw 배열 (candidates 인라인 + winner + isHtml)
            .andExpect(jsonPath("$.bettingInfo.id").value(3))
            .andExpect(jsonPath("$.bettingInfo.type").value("bettingNation"))
            .andExpect(jsonPath("$.bettingInfo.selectCnt").value(1))
            .andExpect(jsonPath("$.bettingInfo.winner[0]").value(1))
            .andExpect(jsonPath("$.bettingInfo.candidates.0.title").value("국가 1"))
            .andExpect(jsonPath("$.bettingInfo.candidates.0.isHtml").value(true))
            .andExpect(jsonPath("$.bettingInfo.candidates.0.aux.nation").value(1))
            .andExpect(jsonPath("$.bettingInfo.candidates.1.title").value("국가 2"))
            // bettingDetail GROUP BY type
            .andExpect(jsonPath("$.bettingDetail.length()").value(2))
            .andExpect(jsonPath("$.bettingDetail[0].bettingType").value("[0]"))
            .andExpect(jsonPath("$.bettingDetail[0].sumAmount").value(1500))
            // myBetting
            .andExpect(jsonPath("$.myBetting.length()").value(1))
            .andExpect(jsonPath("$.myBetting[0].bettingType").value("[0]"))
            .andExpect(jsonPath("$.myBetting[0].sumAmount").value(500))
            // remainPoint (gold, reqInheritancePoint=false)
            .andExpect(jsonPath("$.remainPoint").value(9999))
            // year/month
            .andExpect(jsonPath("$.year").value(2400))
            .andExpect(jsonPath("$.month").value(3))
            // 배당(odds)은 절대 노출하지 않는다
            .andExpect(jsonPath("$.배당").doesNotExist())
            .andExpect(jsonPath("$.odds").doesNotExist())
            .andExpect(jsonPath("$.bettingInfo.배당").doesNotExist())
    }

    @Test
    fun `detail with reqInheritancePoint uses inheritance previous point for remainPoint`() {
        mockWorld()
        mockResolved(200L, gold = 50)
        val masterJson = """
            {"id":5,"type":"tournament","name":"유산 베팅","finished":false,
             "selectCnt":1,"reqInheritancePoint":true,
             "openYearMonth":0,"closeYearMonth":0,"candidates":{}}
        """.trimIndent()
        `when`(kv.findAll()).thenReturn(
            listOf(
                GameKvEntity(table = "betting", namespace = "betting", key = "id_5", value = masterJson, id = 1),
            ),
        )
        `when`(betting.aggregateAmountByType(5)).thenReturn(emptyList())
        `when`(betting.aggregateAmountByTypeForUser(5, 200)).thenReturn(emptyList())
        // PHP: inheritance_{session->userID}.previous[0] (namespace = inheritance_{userId}).
        `when`(kv.findByTableAndNamespaceAndKey("inheritance", "inheritance_200", "previous"))
            .thenReturn(GameKvEntity(table = "inheritance", namespace = "inheritance_200", key = "previous", value = "[1234,5678]", id = 3))

        mockMvc().perform(get("/api/bettings/5/detail").with(principal(200L)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.remainPoint").value(1234))
    }

    @Test
    fun `detail with no master returns 404 with legacy message`() {
        // PHP: getValue("id_{bettingID}")===null → '해당 베팅이 없습니다'(데이터 패러티 우선).
        mockResolved(100L)
        `when`(kv.findAll()).thenReturn(emptyList())

        mockMvc().perform(get("/api/bettings/99/detail").with(principal(100L)))
            .andExpect(status().isNotFound)
            .andExpect(content().string("해당 베팅이 없습니다"))
    }

    @Test
    fun `detail without principal returns 401`() {
        // per-OWNER 데이터(myBetting/remainPoint) → 인증 필수.
        mockMvc().perform(get("/api/bettings/3/detail"))
            .andExpect(status().isUnauthorized)
    }
}
