package opensamguk.gameapi.controller

import opensamguk.gameapi.read.GeneralReadEntity
import opensamguk.gameapi.read.GeneralReadRepository
import opensamguk.infra.entity.AuctionBidEntity
import opensamguk.infra.entity.AuctionEntity
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.ResourceType
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Optional

/**
 * W3 — [AuctionController] 슬라이스 테스트(MockMvc standalone + mocked 레포).
 *
 * 검증: hostName(detail.hostName 우선, 없으면 general JOIN fallback) + highestBid(MAX amount,
 * aux.generalName 디코드) + detail jsonb의 amount/startBidAmount/finishBidAmount 노출.
 */
class AuctionControllerTest {

    private val auctions = mock(AuctionRepository::class.java)
    private val bids = mock(AuctionBidRepository::class.java)
    private val generals = mock(GeneralReadRepository::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(AuctionController(auctions, bids, generals)).build()

    private fun auction(
        id: Int,
        hostGeneralId: Int,
        detailJson: String,
    ) = AuctionEntity(
        type = AuctionType.SELL_RICE,
        finished = false,
        target = null,
        hostGeneralId = hostGeneralId,
        reqResource = ResourceType.GOLD,
        openDate = Instant.parse("2026-06-01T00:00:00Z"),
        closeDate = Instant.parse("2026-06-02T00:00:00Z"),
        detail = detailJson,
        id = id,
    )

    @Test
    fun `active list resolves hostName from detail and highestBid from MAX bid`() {
        `when`(auctions.findByFinishedFalse()).thenReturn(
            listOf(
                auction(
                    id = 7, hostGeneralId = 42,
                    detailJson = """{"hostName":"조조","amount":1000,"startBidAmount":50,"finishBidAmount":900}""",
                ),
            ),
        )
        // fallback용 general read는 호출되더라도 detail.hostName이 이기므로 미사용.
        `when`(generals.findAllById(setOf(42))).thenReturn(
            listOf(GeneralReadEntity(id = 42, name = "다른이름")),
        )
        `when`(bids.findTopByAuctionIdOrderByAmountDesc(7)).thenReturn(
            AuctionBidEntity(
                auctionId = 7, owner = null, generalId = 99, amount = 777,
                date = Instant.parse("2026-06-01T12:00:00Z"),
                aux = """{"generalName":"손권"}""", no = 1,
            ),
        )

        mockMvc().perform(get("/api/auctions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value(7))
            .andExpect(jsonPath("$[0].hostGeneralId").value(42))
            .andExpect(jsonPath("$[0].hostName").value("조조")) // detail.hostName 우선
            .andExpect(jsonPath("$[0].amount").value(1000))
            .andExpect(jsonPath("$[0].startBidAmount").value(50))
            .andExpect(jsonPath("$[0].finishBidAmount").value(900))
            .andExpect(jsonPath("$[0].highestBid.amount").value(777))
            .andExpect(jsonPath("$[0].highestBid.generalId").value(99))
            .andExpect(jsonPath("$[0].highestBid.generalName").value("손권"))
    }

    @Test
    fun `detail falls back to general name when detail hostName is absent and null highestBid`() {
        `when`(auctions.findById(8)).thenReturn(
            Optional.of(auction(id = 8, hostGeneralId = 5, detailJson = """{"amount":2000}""")),
        )
        `when`(generals.findById(5)).thenReturn(Optional.of(GeneralReadEntity(id = 5, name = "유비")))
        `when`(bids.findTopByAuctionIdOrderByAmountDesc(8)).thenReturn(null)

        mockMvc().perform(get("/api/auctions/8"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(8))
            .andExpect(jsonPath("$.hostName").value("유비")) // detail.hostName 없음 → general JOIN
            .andExpect(jsonPath("$.amount").value(2000))
            .andExpect(jsonPath("$.highestBid").value(nullValue())) // 입찰 없음 → JSON null
    }

    @Test
    fun `unknown auction returns 404`() {
        `when`(auctions.findById(999)).thenReturn(Optional.empty())
        mockMvc().perform(get("/api/auctions/999"))
            .andExpect(status().isNotFound)
    }
}
