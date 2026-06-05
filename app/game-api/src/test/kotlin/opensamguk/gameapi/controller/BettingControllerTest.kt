package opensamguk.gameapi.controller

import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.infra.entity.GameKvEntity
import opensamguk.infra.read.BettingRepository
import opensamguk.infra.read.BettingTypeAggregate
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * W3 — [BettingController] 슬라이스 테스트(MockMvc standalone + mocked 레포).
 *
 * 검증: `/{id}/detail`이 market(game_kv 'betting' 디코드) + candidates + bettingDetail(GROUP BY type)을
 * 돌려주고, 배당(odds)은 절대 포함하지 않으며, 마스터 부재 시 market=null + 빈 candidates로 graceful.
 */
class BettingControllerTest {

    private val betting = mock(BettingRepository::class.java)
    private val kv = mock(GameKvReadRepository::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(BettingController(betting, kv)).build()

    /** BettingTypeAggregate projection 더미. */
    private fun agg(type: String, sum: Long) = object : BettingTypeAggregate {
        override val bettingType = type
        override val sumAmount = sum
    }

    @Test
    fun `detail returns market, candidates, bettingDetail and never odds`() {
        val masterJson = """
            {"id":3,"type":"bettingNation","name":"국가 강약 내기","finished":false,
             "selectCnt":1,"isExclusive":null,"reqInheritancePoint":false,
             "openYearMonth":2400,"closeYearMonth":2520,
             "candidates":{"0":{"title":"국가 1","aux":{"nation":1}},
                           "1":{"title":"국가 2","aux":{"nation":2}}}}
        """.trimIndent()
        `when`(kv.findAll()).thenReturn(
            listOf(
                GameKvEntity(table = "game_env", namespace = "game_env", key = "year", value = "2400", id = 1),
                GameKvEntity(table = "betting", namespace = "id_3", key = "master", value = masterJson, id = 2),
            ),
        )
        `when`(betting.aggregateAmountByType(3)).thenReturn(
            listOf(agg("[0]", 1500L), agg("[1]", 2500L)),
        )

        mockMvc().perform(get("/api/bettings/3/detail"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bettingId").value(3))
            .andExpect(jsonPath("$.market.id").value(3))
            .andExpect(jsonPath("$.market.type").value("bettingNation"))
            .andExpect(jsonPath("$.market.selectCnt").value(1))
            // candidates index 보존 + 정렬.
            .andExpect(jsonPath("$.candidates.length()").value(2))
            .andExpect(jsonPath("$.candidates[0].index").value(0))
            .andExpect(jsonPath("$.candidates[0].title").value("국가 1"))
            .andExpect(jsonPath("$.candidates[0].aux.nation").value(1))
            // bettingDetail GROUP BY type.
            .andExpect(jsonPath("$.bettingDetail.length()").value(2))
            .andExpect(jsonPath("$.bettingDetail[0].bettingType").value("[0]"))
            .andExpect(jsonPath("$.bettingDetail[0].sumAmount").value(1500))
            // 배당(odds)은 절대 노출하지 않는다(§W3_PLAN §2 contract).
            .andExpect(jsonPath("$.배당").doesNotExist())
            .andExpect(jsonPath("$.odds").doesNotExist())
            .andExpect(jsonPath("$.market.배당").doesNotExist())
    }

    @Test
    fun `detail with no master returns market null and empty candidates but keeps bettingDetail`() {
        `when`(kv.findAll()).thenReturn(emptyList())
        `when`(betting.aggregateAmountByType(99)).thenReturn(listOf(agg("[0]", 42L)))

        mockMvc().perform(get("/api/bettings/99/detail"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bettingId").value(99))
            .andExpect(jsonPath("$.market").value(nullValue())) // 마스터 부재 → JSON null
            .andExpect(jsonPath("$.candidates.length()").value(0))
            .andExpect(jsonPath("$.bettingDetail.length()").value(1))
            .andExpect(jsonPath("$.bettingDetail[0].sumAmount").value(42))
    }
}
