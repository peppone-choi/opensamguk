package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.BettingCandidate
import opensamguk.gameapi.dto.BettingDetailItem
import opensamguk.gameapi.dto.BettingDetailResponse
import opensamguk.gameapi.dto.BettingItemResponse
import opensamguk.gameapi.dto.BettingMarket
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.infra.read.BettingRepository
import opensamguk.infra.read.BettingTypeAggregate
import opensamguk.logic.util.jsonDecode
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 베팅 read API (W3 enriched). PHP grand truth: `API/Betting/GetBettingDetail.php` / `GetBettingList.php`.
 *
 * W3에서 추가:
 *  - `GET /api/bettings/{bettingId}/detail` — `market`(베팅 마스터, game_kv 디코드) + `candidates` +
 *    `bettingDetail`(SUM(amount) GROUP BY betting_type)를 한 번에 돌려준다.
 *
 * **배당(odds) 미반환(§W3_PLAN §2 contract):** PHP가 배당을 서버 계산해 반환하지 않듯 우리도 안 한다.
 * FE가 `bettingDetail`로 직접 산출한다.
 *
 * **`market` 원천 주의(BLOCKED 인접):** 베팅 마스터([opensamguk.logic.betting.BettingInfo])는 `game_kv`
 * (table='betting') jsonb에 저장된다. 다만 엔진의 마스터 영속화 키 규약이 아직 확정되지 않았다(PHP는
 * `id_{id}`, 엔진 in-memory는 `betting:{id}` — OpenNationBetting에 TODO 잔존). 따라서 키를 하드코딩하지
 * 않고 `table='betting'` 행들을 generic하게 read한 뒤 디코드된 `id`가 일치하는 마스터를 고른다(없으면
 * `market=null` — graceful, fabricate 금지). `bettingDetail`(ng_betting 집계)은 항상 신뢰 가능.
 *
 * read-only(§7).
 */
@RestController
@RequestMapping("/api/bettings")
class BettingController(
    private val bettingRepository: BettingRepository,
    private val gameKvReadRepository: GameKvReadRepository,
) {

    @GetMapping("/{bettingId}/bets")
    fun bets(@PathVariable bettingId: Int): ResponseEntity<List<BettingItemResponse>> {
        val bets = bettingRepository.findByBettingId(bettingId)
            .map { it.toResponse() }
        return ResponseEntity.ok(bets)
    }

    @GetMapping("/general/{generalId}")
    fun byGeneral(@PathVariable generalId: Int): ResponseEntity<List<BettingItemResponse>> {
        val bets = bettingRepository.findByGeneralId(generalId)
            .map { it.toResponse() }
        return ResponseEntity.ok(bets)
    }

    /**
     * W3 — 베팅 상세(`market` + `candidates` + `bettingDetail`). PHP `GetBettingDetail`.
     * 마스터가 없어도 200 + `market=null` + 빈 candidates(베팅 행만 존재하는 과도기 안전).
     */
    @GetMapping("/{bettingId}/detail")
    fun detail(@PathVariable bettingId: Int): ResponseEntity<BettingDetailResponse> {
        val master = loadBettingMaster(bettingId)
        val detail = bettingRepository.aggregateAmountByType(bettingId)
            .map { it.toDetailItem() }
        return ResponseEntity.ok(
            BettingDetailResponse(
                bettingId = bettingId,
                market = master?.first,
                candidates = master?.second ?: emptyList(),
                bettingDetail = detail,
            ),
        )
    }

    /**
     * `game_kv`(table='betting') 행들에서 id가 [bettingId]와 일치하는 [BettingInfo] 마스터를 찾아
     * (market, candidates)로 디코드. 키 규약 미확정이라 namespace/key를 가리지 않고 table만으로 모은 뒤
     * 디코드된 `id`로 매칭한다(graceful). 디코드 실패 행은 건너뛴다.
     */
    private fun loadBettingMaster(bettingId: Int): Pair<BettingMarket, List<BettingCandidate>>? {
        // table='betting' 전체를 read하는 generic 메서드가 GameKvReadRepository엔 없으므로 findAll 후
        // table 필터(KV 행 수가 적어 비용 무시 가능). 대규모화되면 table 인덱스 쿼리로 교체.
        val rows = gameKvReadRepository.findAll().filter { it.table == "betting" }
        for (row in rows) {
            val map = runCatching { jsonDecode(row.value) }.getOrNull() ?: continue
            val id = (map["id"] as? Number)?.toInt() ?: continue
            if (id != bettingId) continue
            return decodeMaster(map)
        }
        return null
    }

    /** 디코드된 BettingInfo 맵 → (BettingMarket, candidates). */
    private fun decodeMaster(map: Map<String, Any?>): Pair<BettingMarket, List<BettingCandidate>> {
        val market = BettingMarket(
            id = (map["id"] as? Number)?.toInt() ?: 0,
            type = map["type"] as? String ?: "",
            name = map["name"] as? String ?: "",
            finished = map["finished"] as? Boolean ?: false,
            selectCnt = (map["selectCnt"] as? Number)?.toInt() ?: 1,
            isExclusive = map["isExclusive"] as? Boolean,
            reqInheritancePoint = map["reqInheritancePoint"] as? Boolean ?: false,
            openYearMonth = (map["openYearMonth"] as? Number)?.toInt() ?: 0,
            closeYearMonth = (map["closeYearMonth"] as? Number)?.toInt() ?: 0,
        )
        // candidates = {index(String/Int) → SelectItem map}. 삽입순 보존.
        @Suppress("UNCHECKED_CAST")
        val rawCandidates = map["candidates"] as? Map<String, Any?> ?: emptyMap()
        val candidates = rawCandidates.mapNotNull { (k, v) ->
            val idx = k.toIntOrNull() ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val item = v as? Map<String, Any?> ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            BettingCandidate(
                index = idx,
                title = item["title"] as? String ?: "",
                info = item["info"] as? String,
                aux = item["aux"] as? Map<String, Any?>,
            )
        }.sortedBy { it.index }
        return market to candidates
    }

    private fun BettingTypeAggregate.toDetailItem() =
        BettingDetailItem(bettingType = bettingType, sumAmount = sumAmount)

    private fun opensamguk.infra.entity.NgBettingEntity.toResponse() = BettingItemResponse(
        id = id,
        bettingId = bettingId,
        generalId = generalId,
        userId = userId,
        bettingType = bettingType,
        amount = amount,
    )
}
