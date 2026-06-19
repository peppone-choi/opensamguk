package opensamguk.gameapi.controller

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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.Optional

/**
 * [AuctionController] 슬라이스 테스트(MockMvc standalone + mocked 레포).
 *
 * D1-D3를 PHP grand truth 형상에 고정한다:
 *  - D1 envelope `{result, buyRice[], sellRice[], recentLogs[], generalID}`,
 *    per-item `{id, type, hostGeneralID, hostName, openDate, closeDate, amount, startBidAmount,
 *    finishBidAmount, highestBid}`, 날짜 = 'yyyy-MM-dd HH:mm:ss' 문자열, hostName = detail.hostName only.
 *  - D2 envelope `{result, list[], obfuscatedName}`,
 *    per-item `{id, finished, title, target, isCallerHost, hostName, closeDate,
 *    remainCloseDateExtensionCnt, availableLatestBidCloseDate, highestBid}`,
 *    highestBid `{generalName, amount, isCallerHighestBidder, date}`.
 *  - D3 nested `{result, auction:{...}, bidList[], obfuscatedName, remainPoint}`,
 *    부재 시 한글 메시지 '선택한 경매가 없습니다.'.
 */
class AuctionControllerTest {

    private val auctions = mock(AuctionRepository::class.java)
    private val bids = mock(AuctionBidRepository::class.java)

    private fun mockMvc(): MockMvc =
        MockMvcBuilders.standaloneSetup(AuctionController(auctions, bids)).build()

    private fun auction(
        id: Int,
        type: AuctionType = AuctionType.SELL_RICE,
        hostGeneralId: Int,
        detailJson: String,
        target: String? = null,
        finished: Boolean = false,
        openDate: Instant = Instant.parse("2026-06-01T00:00:00Z"),
        closeDate: Instant = Instant.parse("2026-06-02T00:00:00Z"),
    ) = AuctionEntity(
        type = type,
        finished = finished,
        target = target,
        hostGeneralId = hostGeneralId,
        reqResource = ResourceType.GOLD,
        openDate = openDate,
        closeDate = closeDate,
        detail = detailJson,
        id = id,
    )

    private fun bid(
        auctionId: Int,
        generalId: Int,
        amount: Int,
        generalName: String,
        date: Instant = Instant.parse("2026-06-01T12:00:00Z"),
        no: Int = 1,
    ) = AuctionBidEntity(
        auctionId = auctionId, owner = null, generalId = generalId, amount = amount,
        date = date, aux = """{"generalName":"$generalName"}""", no = no,
    )

    // ── D1: 활성 자원 경매 목록 ───────────────────────────────────────────────

    @Test
    fun `D1 listActive returns PHP envelope with buyRice and sellRice separated`() {
        `when`(auctions.findByFinishedFalseAndTypeValue(AuctionType.BUY_RICE.value)).thenReturn(
            listOf(
                auction(
                    id = 7, type = AuctionType.BUY_RICE, hostGeneralId = 42,
                    detailJson = """{"hostName":"조조","amount":1000,"startBidAmount":50,"finishBidAmount":900}""",
                    closeDate = Instant.parse("2026-06-02T00:00:00Z"),
                ),
            ),
        )
        `when`(auctions.findByFinishedFalseAndTypeValue(AuctionType.SELL_RICE.value)).thenReturn(
            listOf(
                auction(
                    id = 8, type = AuctionType.SELL_RICE, hostGeneralId = 5,
                    detailJson = """{"hostName":"유비","amount":2000,"startBidAmount":100}""",
                    closeDate = Instant.parse("2026-06-03T00:00:00Z"),
                ),
            ),
        )
        `when`(bids.findHighestBidsByAuctionIds(listOf(7, 8))).thenReturn(
            listOf(
                bid(auctionId = 7, generalId = 99, amount = 777, generalName = "손권",
                    date = Instant.parse("2026-06-01T12:00:00Z"), no = 1),
                bid(auctionId = 8, generalId = 88, amount = 555, generalName = "관우",
                    date = Instant.parse("2026-06-01T10:00:00Z"), no = 2),
            ),
        )

        mockMvc().perform(get("/api/auctions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true)) // PHP result:true
            .andExpect(jsonPath("$.buyRice.length()").value(1))
            .andExpect(jsonPath("$.buyRice[0].id").value(7))
            .andExpect(jsonPath("$.buyRice[0].type").value("buyRice")) // type.value (소문자)
            .andExpect(jsonPath("$.buyRice[0].hostGeneralId").value(42))
            .andExpect(jsonPath("$.buyRice[0].hostName").value("조조")) // detail.hostName only
            .andExpect(jsonPath("$.buyRice[0].openDate").value("2026-06-01 09:00:00")) // Asia/Seoul 포맷 문자열
            .andExpect(jsonPath("$.buyRice[0].closeDate").value("2026-06-02 09:00:00"))
            .andExpect(jsonPath("$.buyRice[0].amount").value(1000))
            .andExpect(jsonPath("$.buyRice[0].startBidAmount").value(50))
            .andExpect(jsonPath("$.buyRice[0].finishBidAmount").value(900))
            .andExpect(jsonPath("$.buyRice[0].highestBid.amount").value(777))
            .andExpect(jsonPath("$.buyRice[0].highestBid.generalID").value(99)) // PHP generalID 키
            .andExpect(jsonPath("$.buyRice[0].highestBid.generalName").value("손권"))
            .andExpect(jsonPath("$.buyRice[0].highestBid.date").value("2026-06-01 21:00:00"))
            // D1 per-item 필드셋: PHP에 없는 finished/target/reqResource는 노출 금지
            .andExpect(jsonPath("$.buyRice[0].finished").doesNotExist())
            .andExpect(jsonPath("$.buyRice[0].target").doesNotExist())
            .andExpect(jsonPath("$.buyRice[0].reqResource").doesNotExist())
            .andExpect(jsonPath("$.sellRice.length()").value(1))
            .andExpect(jsonPath("$.sellRice[0].id").value(8))
            .andExpect(jsonPath("$.sellRice[0].type").value("sellRice"))
            .andExpect(jsonPath("$.sellRice[0].hostName").value("유비"))
            .andExpect(jsonPath("$.sellRice[0].amount").value(2000))
            .andExpect(jsonPath("$.sellRice[0].finishBidAmount").value(nullValue())) // detail에 없음
            .andExpect(jsonPath("$.recentLogs.length()").value(0)) // BLOCKED
            .andExpect(jsonPath("$.generalID").value(0))           // BLOCKED, PHP generalID 키
    }

    @Test
    fun `D1 listActive uses detail hostName only and no live-general fallback`() {
        `when`(auctions.findByFinishedFalseAndTypeValue(AuctionType.BUY_RICE.value)).thenReturn(emptyList())
        `when`(auctions.findByFinishedFalseAndTypeValue(AuctionType.SELL_RICE.value)).thenReturn(
            listOf(
                auction(
                    id = 9, type = AuctionType.SELL_RICE, hostGeneralId = 10,
                    detailJson = """{"amount":500}""", // hostName 없음 → detail.hostName 기본값 ""
                ),
            ),
        )
        `when`(bids.findHighestBidsByAuctionIds(listOf(9))).thenReturn(emptyList())

        mockMvc().perform(get("/api/auctions"))
            .andExpect(status().isOk)
            // PHP는 detail.hostName만 사용 — live general JOIN 폴백 없음(빈 hostName 그대로)
            .andExpect(jsonPath("$.sellRice[0].hostName").value(""))
            .andExpect(jsonPath("$.sellRice[0].highestBid").value(nullValue()))
    }

    @Test
    fun `D1 item carries inline-bid bound fields the AuctionResource component binds`() {
        // C1 AuctionResource 인라인 입찰은 min=startBidAmount/max=finishBidAmount/step=10에 바인딩하고,
        // highestBid null이면 startBidAmount로 폴백한다(legacy watch). 또 단가=highestBid.amount/amount,
        // 입찰자 generalName?? '-' 렌더 — 이 와이어 필드(startBidAmount/finishBidAmount/amount/
        // highestBid.amount/highestBid.generalName)를 고정해 컴포넌트 회귀를 가드한다.
        `when`(auctions.findByFinishedFalseAndTypeValue(AuctionType.BUY_RICE.value)).thenReturn(emptyList())
        `when`(auctions.findByFinishedFalseAndTypeValue(AuctionType.SELL_RICE.value)).thenReturn(
            listOf(
                auction(
                    id = 11, type = AuctionType.SELL_RICE, hostGeneralId = 3,
                    detailJson = """{"hostName":"동탁","amount":1000,"startBidAmount":300,"finishBidAmount":2500}""",
                ),
            ),
        )
        // generalName 없는 aux(난독 미스냅샷) — FE는 ?? '-'로 렌더.
        `when`(bids.findHighestBidsByAuctionIds(listOf(11))).thenReturn(
            listOf(
                AuctionBidEntity(
                    auctionId = 11, owner = null, generalId = 77, amount = 1500,
                    date = Instant.parse("2026-06-01T12:00:00Z"), aux = """{}""", no = 1,
                ),
            ),
        )

        mockMvc().perform(get("/api/auctions"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sellRice[0].amount").value(1000))         // 단가 분모
            .andExpect(jsonPath("$.sellRice[0].startBidAmount").value(300))  // 입찰 min + null 폴백
            .andExpect(jsonPath("$.sellRice[0].finishBidAmount").value(2500)) // 입찰 max
            .andExpect(jsonPath("$.sellRice[0].highestBid.amount").value(1500)) // 단가 분자
            .andExpect(jsonPath("$.sellRice[0].highestBid.generalName").value(nullValue())) // ?? '-' 렌더
    }

    @Test
    fun `D1 listActive orders combined lists by close_date ASC`() {
        // buyRice 마감이 sellRice보다 늦음 — 합쳐 정렬 후 type별 분배(순서는 각 리스트 내부 정렬 검증)
        `when`(auctions.findByFinishedFalseAndTypeValue(AuctionType.BUY_RICE.value)).thenReturn(
            listOf(
                auction(id = 1, type = AuctionType.BUY_RICE, hostGeneralId = 1,
                    detailJson = """{"hostName":"늦은","amount":10,"startBidAmount":1}""",
                    closeDate = Instant.parse("2026-06-20T00:00:00Z")),
                auction(id = 2, type = AuctionType.BUY_RICE, hostGeneralId = 2,
                    detailJson = """{"hostName":"이른","amount":10,"startBidAmount":1}""",
                    closeDate = Instant.parse("2026-06-05T00:00:00Z")),
            ),
        )
        `when`(auctions.findByFinishedFalseAndTypeValue(AuctionType.SELL_RICE.value)).thenReturn(emptyList())
        `when`(bids.findHighestBidsByAuctionIds(listOf(1, 2))).thenReturn(emptyList())

        mockMvc().perform(get("/api/auctions"))
            .andExpect(status().isOk)
            // close_date ASC → 이른(id=2)이 먼저
            .andExpect(jsonPath("$.buyRice[0].id").value(2))
            .andExpect(jsonPath("$.buyRice[1].id").value(1))
    }

    // ── D2: 유니크 아이템 경매 목록 ───────────────────────────────────────────

    @Test
    fun `D2 listUniqueItems returns PHP envelope and item field set`() {
        `when`(auctions.findByTypeValueOrderByCloseDateAsc(AuctionType.UNIQUE_ITEM.value)).thenReturn(
            listOf(
                auction(
                    id = 20, type = AuctionType.UNIQUE_ITEM, hostGeneralId = 42, target = "적토마",
                    detailJson = """{"title":"전설의 말","hostName":"비밀장수","amount":1,"startBidAmount":100,"finishBidAmount":500,"remainCloseDateExtensionCnt":2}""",
                    closeDate = Instant.parse("2026-06-10T00:00:00Z"),
                ),
            ),
        )
        `when`(bids.findHighestBidsByAuctionIds(listOf(20))).thenReturn(
            listOf(bid(auctionId = 20, generalId = 99, amount = 300, generalName = "입찰자A",
                date = Instant.parse("2026-06-05T12:00:00Z"), no = 1)),
        )

        mockMvc().perform(get("/api/auctions/unique"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.obfuscatedName").value(nullValue())) // BLOCKED (top-level, no session/KV)
            .andExpect(jsonPath("$.list.length()").value(1))
            .andExpect(jsonPath("$.list[0].id").value(20))
            .andExpect(jsonPath("$.list[0].finished").value(false))
            .andExpect(jsonPath("$.list[0].title").value("전설의 말"))
            .andExpect(jsonPath("$.list[0].target").value("적토마"))
            .andExpect(jsonPath("$.list[0].isCallerHost").value(false)) // viewer 0 != 42
            .andExpect(jsonPath("$.list[0].hostName").value("비밀장수")) // detail.hostName(난독)
            .andExpect(jsonPath("$.list[0].closeDate").value("2026-06-10 09:00:00"))
            .andExpect(jsonPath("$.list[0].remainCloseDateExtensionCnt").value(2))
            .andExpect(jsonPath("$.list[0].highestBid.generalName").value("입찰자A"))
            .andExpect(jsonPath("$.list[0].highestBid.amount").value(300))
            .andExpect(jsonPath("$.list[0].highestBid.isCallerHighestBidder").value(false))
            .andExpect(jsonPath("$.list[0].highestBid.date").value("2026-06-05 21:00:00"))
            // PHP 유니크 list 항목에 없는 필드 노출 금지
            .andExpect(jsonPath("$.list[0].type").doesNotExist())
            .andExpect(jsonPath("$.list[0].hostGeneralId").doesNotExist())
            .andExpect(jsonPath("$.list[0].reqResource").doesNotExist())
            .andExpect(jsonPath("$.list[0].amount").doesNotExist())
            .andExpect(jsonPath("$.list[0].obfuscatedName").doesNotExist()) // top-level only
    }

    @Test
    fun `D2 listUniqueItems skips items with no highestBid`() {
        `when`(auctions.findByTypeValueOrderByCloseDateAsc(AuctionType.UNIQUE_ITEM.value)).thenReturn(
            listOf(
                auction(
                    id = 21, type = AuctionType.UNIQUE_ITEM, hostGeneralId = 42, target = "적토마",
                    detailJson = """{"title":"전설의 말","hostName":"비밀장수","amount":1,"startBidAmount":100}""",
                    closeDate = Instant.parse("2026-06-10T00:00:00Z"),
                ),
            ),
        )
        `when`(bids.findHighestBidsByAuctionIds(listOf(21))).thenReturn(emptyList())

        mockMvc().perform(get("/api/auctions/unique"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.list.length()").value(0)) // highestBid null → skip
    }

    @Test
    fun `D2 listUniqueItems empty returns envelope with empty list`() {
        `when`(auctions.findByTypeValueOrderByCloseDateAsc(AuctionType.UNIQUE_ITEM.value)).thenReturn(emptyList())

        mockMvc().perform(get("/api/auctions/unique"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.list.length()").value(0))
            .andExpect(jsonPath("$.obfuscatedName").value(nullValue()))
    }

    // ── D3: 유니크 아이템 경매 상세 ───────────────────────────────────────────

    @Test
    fun `D3 uniqueDetail returns nested PHP envelope with bidList`() {
        `when`(auctions.findById(30)).thenReturn(
            Optional.of(
                auction(
                    id = 30, type = AuctionType.UNIQUE_ITEM, hostGeneralId = 50, target = "청홍검",
                    detailJson = """{"title":"전설의 검","hostName":"익명장수","amount":1,"startBidAmount":200,"finishBidAmount":800,"isReverse":false,"remainCloseDateExtensionCnt":3}""",
                    closeDate = Instant.parse("2026-06-15T00:00:00Z"),
                ),
            ),
        )
        `when`(bids.findByAuctionIdOrderByAmountDesc(30)).thenReturn(
            listOf(
                bid(auctionId = 30, generalId = 99, amount = 500, generalName = "최고입찰자",
                    date = Instant.parse("2026-06-05T12:00:00Z"), no = 1),
                bid(auctionId = 30, generalId = 88, amount = 400, generalName = "두번째입찰자",
                    date = Instant.parse("2026-06-04T10:00:00Z"), no = 2),
            ),
        )

        mockMvc().perform(get("/api/auctions/30/unique-detail"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.result").value(true))
            .andExpect(jsonPath("$.auction.id").value(30))
            .andExpect(jsonPath("$.auction.finished").value(false))
            .andExpect(jsonPath("$.auction.title").value("전설의 검"))
            .andExpect(jsonPath("$.auction.target").value("청홍검"))
            .andExpect(jsonPath("$.auction.isCallerHost").value(false)) // viewer 0 != 50
            .andExpect(jsonPath("$.auction.hostName").value("익명장수")) // detail.hostName(난독), JOIN 없음
            .andExpect(jsonPath("$.auction.closeDate").value("2026-06-15 09:00:00"))
            .andExpect(jsonPath("$.auction.remainCloseDateExtensionCnt").value(3))
            // PHP auction 객체에 없는 필드 노출 금지
            .andExpect(jsonPath("$.auction.type").doesNotExist())
            .andExpect(jsonPath("$.auction.hostGeneralId").doesNotExist())
            .andExpect(jsonPath("$.auction.reqResource").doesNotExist())
            .andExpect(jsonPath("$.auction.amount").doesNotExist())
            .andExpect(jsonPath("$.auction.isReverse").doesNotExist())
            // D3는 별도 highestBid 없음 — bidList(amount DESC)만
            .andExpect(jsonPath("$.highestBid").doesNotExist())
            .andExpect(jsonPath("$.bidList.length()").value(2))
            .andExpect(jsonPath("$.bidList[0].generalName").value("최고입찰자"))
            .andExpect(jsonPath("$.bidList[0].amount").value(500)) // DESC
            .andExpect(jsonPath("$.bidList[0].isCallerHighestBidder").value(false))
            .andExpect(jsonPath("$.bidList[0].date").value("2026-06-05 21:00:00"))
            .andExpect(jsonPath("$.bidList[1].amount").value(400))
            // PHP bidList 항목에 없는 raw 필드 제거
            .andExpect(jsonPath("$.bidList[0].no").doesNotExist())
            .andExpect(jsonPath("$.bidList[0].generalId").doesNotExist())
            .andExpect(jsonPath("$.bidList[0].owner").doesNotExist())
            .andExpect(jsonPath("$.bidList[0].aux").doesNotExist())
            .andExpect(jsonPath("$.obfuscatedName").value(nullValue())) // BLOCKED
            .andExpect(jsonPath("$.remainPoint").value(nullValue()))    // BLOCKED
    }

    @Test
    fun `D3 uniqueDetail returns Korean message for non-unique auction`() {
        `when`(auctions.findById(40)).thenReturn(
            Optional.of(
                auction(
                    id = 40, type = AuctionType.BUY_RICE, hostGeneralId = 1,
                    detailJson = """{}""",
                ),
            ),
        )

        mockMvc().perform(get("/api/auctions/40/unique-detail"))
            .andExpect(status().isNotFound)
            .andExpect(content().string("선택한 경매가 없습니다.")) // PHP 한글 메시지
    }

    @Test
    fun `D3 uniqueDetail returns Korean message for unknown auction`() {
        `when`(auctions.findById(999)).thenReturn(Optional.empty())

        mockMvc().perform(get("/api/auctions/999/unique-detail"))
            .andExpect(status().isNotFound)
            .andExpect(content().string("선택한 경매가 없습니다."))
    }
}
