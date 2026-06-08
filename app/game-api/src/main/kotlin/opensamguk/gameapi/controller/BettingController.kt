package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.BettingDetailItem
import opensamguk.gameapi.dto.BettingDetailResponse
import opensamguk.gameapi.dto.BettingItemResponse
import opensamguk.gameapi.dto.BettingListItem
import opensamguk.gameapi.dto.BettingListResponse
import opensamguk.gameapi.owner.GeneralResolver
import opensamguk.gameapi.read.GameKvReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.read.BettingRepository
import opensamguk.infra.read.BettingTypeAggregate
import opensamguk.logic.util.jsonDecode
import opensamguk.logic.util.jsonDecodeAny
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 베팅 read API (D4-D5). PHP grand truth: `API/Betting/GetBettingList.php` / `GetBettingDetail.php`.
 *
 * D4: `GET /api/bettings` — 전체 베팅 목록. 봉투 `{result, bettingList(Map<id,item>), year, month}`.
 *     `req`(type) 필터는 PHP Validator `in 'req' ['bettingNation','tournament']` → 화이트리스트 외 값은 400.
 * D5: `GET /api/bettings/{bettingId}/detail` — 상세. 봉투 **정확히 7키**
 *     `{result, bettingInfo, bettingDetail, myBetting, remainPoint, year, month}`(userCnt 없음).
 *
 * **배당(odds) 미반환:** PHP가 배당을 서버 계산해 반환하지 않듯 우리도 안 한다.
 * FE가 `bettingDetail`로 직접 산출한다.
 *
 * **`bettingInfo` 원천:** 베팅 마스터는 `game_kv`(table='betting') jsonb([BettingInfo])에 저장된다.
 * PHP는 `bettingStor->getValue("id_{bettingID}")`로 raw 배열을 통째 패스스루(candidates 인라인 + winner).
 * opensamguk 매핑(table='betting')에서 raw 행을 디코드해 그대로 날린다(가공 없음, 삽입순서 보존).
 *
 * **인증:** D5는 per-OWNER 데이터(myBetting/remainPoint) → `@AuthenticationPrincipal userId` → [GeneralResolver].
 * 캐릭터 없으면 401(InheritPointController 동일 패턴).
 *
 * read-only(§7).
 */
@RestController
@RequestMapping("/api/bettings")
class BettingController(
    private val bettingRepository: BettingRepository,
    private val gameKvReadRepository: GameKvReadRepository,
    private val worldStateReadRepository: WorldStateReadRepository,
    private val resolver: GeneralResolver,
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
     * D4 — 베팅 전체 목록. PHP `GetBettingList.php:33-95`.
     *
     * - PHP Validator: `$v->rule('in', 'req', ['bettingNation','tournament'])` → `req`(여기선 `type`
     *   쿼리파라미터)가 주어졌을 때 화이트리스트 외 값이면 400(빈 목록으로 삼키지 않음). null이면 전체.
     * - game_kv(betting) 전체 디코드 → `BettingInfo` 목록
     * - `$item->type != $reqType` 필터 (req null이면 전체 통과)
     * - 봉투: `{result:true, bettingList(Map<id,item>), year, month}`
     * - 항목: raw BettingInfo - candidates + totalAmount. candidates 제거(PHP `unset`).
     * - totalAmount = repo `SUM(amount) GROUP BY betting_id`(행 없으면 0)
     * - year/month = world_state current
     */
    @GetMapping
    fun list(
        @RequestParam(required = false) type: String?,
    ): ResponseEntity<BettingListResponse> {
        // PHP Validator `in 'req' ['bettingNation','tournament']` — 값이 있을 때만 검사.
        if (type != null && type !in ALLOWED_REQ_TYPES) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }

        val world = worldStateReadRepository.findById(1).orElse(null)
        val year = world?.currentYear ?: 0
        val month = world?.currentMonth ?: 0

        // game_kv 'betting' 테이블 전체 디코드
        val masters = loadAllBettingMasters()

        // type 필터 (PHP: `$item->type != $reqType` → req null이면 전체)
        val filtered = if (type != null) {
            masters.filter { it.type == type }
        } else {
            masters
        }

        // totalAmount 집계 Map<bettingId, sum>
        val totalMap = bettingRepository.aggregateTotalAmountByBetting()
            .associate { it.bettingId to it.sumAmount }

        val bettingList = filtered.associate { info ->
            info.id to BettingListItem(
                id = info.id,
                type = info.type,
                name = info.name,
                finished = info.finished,
                selectCnt = info.selectCnt,
                isExclusive = info.isExclusive,
                reqInheritancePoint = info.reqInheritancePoint,
                openYearMonth = info.openYearMonth,
                closeYearMonth = info.closeYearMonth,
                winner = info.winner,
                totalAmount = totalMap[info.id] ?: 0L,
            )
        }

        return ResponseEntity.ok(
            BettingListResponse(
                result = true,
                bettingList = bettingList,
                year = year,
                month = month,
            ),
        )
    }

    /**
     * D5 — 베팅 상세. PHP `GetBettingDetail.php:46-98`.
     *
     * - `bettingStor->getValue("id_{bettingID}")` 부재 시 PHP는 '해당 베팅이 없습니다' 문자열 반환
     *   → 여기선 404 + 동일 메시지(데이터 패러티 우선).
     * - `bettingInfo` = raw `$rawBettingInfo` 통째 패스스루(candidates 인라인 + winner + 각 isHtml).
     * - `bettingDetail` = GROUP BY betting_type SUM(amount).
     * - `myBetting` = GROUP BY betting_type SUM(amount) WHERE user_id = session.userID(로그인 사용자).
     * - `remainPoint` = reqInheritancePoint ? `inheritance_{userID}.previous[0]` : `general.gold`(부재 0).
     * - `year`/`month` = world_state current.
     *
     * per-OWNER 데이터(myBetting/remainPoint) → `@AuthenticationPrincipal userId` → [GeneralResolver].
     * 캐릭터 없으면 401(InheritPointController 동일 패턴).
     */
    @GetMapping("/{bettingId}/detail")
    fun detail(
        @PathVariable bettingId: Int,
        @AuthenticationPrincipal userId: Long?,
    ): ResponseEntity<Any> {
        if (userId == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        val resolved = resolver.resolve(userId)
            ?: return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()

        // raw $rawBettingInfo (id_{bettingID}). 부재 시 PHP는 '해당 베팅이 없습니다' 문자열 반환.
        // text/plain;charset=UTF-8 명시 — 한글 바이트가 ISO-8859-1로 디코드돼 깨지지 않도록.
        val bettingInfo = loadRawBettingInfo(bettingId)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType(MediaType.TEXT_PLAIN, Charsets.UTF_8))
                .body("해당 베팅이 없습니다")

        val world = worldStateReadRepository.findById(1).orElse(null)
        val year = world?.currentYear ?: 0
        val month = world?.currentMonth ?: 0

        val bettingDetail = bettingRepository.aggregateAmountByType(bettingId)
            .map { it.toDetailItem() }

        // myBetting — 로그인 사용자(user_id = session.userID)의 betting_type별 집계.
        val myBetting = bettingRepository.aggregateAmountByTypeForUser(bettingId, userId.toInt())
            .map { it.toDetailItem() }

        // remainPoint — reqInheritancePoint ? inheritance_{userID}.previous[0] : general.gold(부재 0).
        val reqInheritancePoint = bettingInfo["reqInheritancePoint"] as? Boolean ?: false
        val remainPoint = if (reqInheritancePoint) {
            loadInheritancePoint(userId)
        } else {
            resolved.general.gold
        }

        return ResponseEntity.ok(
            BettingDetailResponse(
                result = true,
                bettingInfo = bettingInfo,
                bettingDetail = bettingDetail,
                myBetting = myBetting,
                remainPoint = remainPoint,
                year = year,
                month = month,
            ),
        )
    }

    /** `game_kv`(table='betting') 전체 행을 디코드해 [BettingMaster] 목록으로 반환(D4 목록용). */
    private fun loadAllBettingMasters(): List<BettingMaster> {
        val rows = gameKvReadRepository.findAll().filter { it.table == "betting" }
        return rows.mapNotNull { row ->
            val map = runCatching { jsonDecode(row.value) }.getOrNull() ?: return@mapNotNull null
            decodeMaster(map)
        }
    }

    /**
     * D5 — `game_kv`(table='betting')에서 id가 [bettingId]와 일치하는 raw BettingInfo 맵을 그대로 반환.
     *
     * PHP `bettingStor->getValue("id_{bettingID}")`는 가공 없이 통째로 날린다(candidates 인라인 + winner
     * + 각 candidate의 isHtml/title/info/aux). [jsonDecode]가 삽입순서를 보존(LinkedHashMap)하므로
     * 디코드된 맵을 그대로 응답에 싣는다. 부재 시 null.
     */
    private fun loadRawBettingInfo(bettingId: Int): Map<String, Any?>? {
        val rows = gameKvReadRepository.findAll().filter { it.table == "betting" }
        for (row in rows) {
            val map = runCatching { jsonDecode(row.value) }.getOrNull() ?: continue
            val id = (map["id"] as? Number)?.toInt() ?: continue
            if (id != bettingId) continue
            return map
        }
        return null
    }

    /** 디코드된 BettingInfo 맵 → [BettingMaster](D4 목록 항목 빌드용 큐레이션 서브셋). */
    private fun decodeMaster(map: Map<String, Any?>): BettingMaster {
        // winner 디코드
        @Suppress("UNCHECKED_CAST")
        val winner = (map["winner"] as? List<Any?>)?.mapNotNull { (it as? Number)?.toInt() }

        return BettingMaster(
            id = (map["id"] as? Number)?.toInt() ?: 0,
            type = map["type"] as? String ?: "",
            name = map["name"] as? String ?: "",
            finished = map["finished"] as? Boolean ?: false,
            selectCnt = (map["selectCnt"] as? Number)?.toInt() ?: 1,
            isExclusive = map["isExclusive"] as? Boolean,
            reqInheritancePoint = map["reqInheritancePoint"] as? Boolean ?: false,
            openYearMonth = (map["openYearMonth"] as? Number)?.toInt() ?: 0,
            closeYearMonth = (map["closeYearMonth"] as? Number)?.toInt() ?: 0,
            winner = winner,
        )
    }

    /**
     * 유산 포인트 조회: game_kv(table=inheritance, namespace=inheritance_{userID}, key=previous)[0].
     *
     * PHP: `KVStorage::getStorage($db, "inheritance_{$session->userID}")->getValue('previous')`
     * → 네임스페이스가 `inheritance_{userID}`(소유자 userID 기준), 키 `previous`. InheritPointController와
     * 동일 매핑. `previous` 값은 JSON 배열(`[1234,5678]`)이고 PHP는 `(... ?? [0,0])[0]`로 0번째를 쓴다.
     * jsonDecode는 object-root 전용이라 배열 루트를 빈 Map으로 떨궈 항상 0을 반환했었다(버그) → jsonDecodeAny.
     */
    private fun loadInheritancePoint(userId: Long): Int {
        val row = gameKvReadRepository.findByTableAndNamespaceAndKey(
            "inheritance", "inheritance_$userId", "previous",
        ) ?: return 0
        val decoded = runCatching { jsonDecodeAny(row.value) }.getOrNull() ?: return 0
        @Suppress("UNCHECKED_CAST")
        val list = decoded as? List<Any?> ?: return 0
        return (list.firstOrNull() as? Number)?.toInt() ?: 0
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

    /** D4 목록 항목 빌드용 큐레이션 서브셋(raw BettingInfo - candidates). */
    private data class BettingMaster(
        val id: Int,
        val type: String,
        val name: String,
        val finished: Boolean,
        val selectCnt: Int,
        val isExclusive: Boolean?,
        val reqInheritancePoint: Boolean,
        val openYearMonth: Int,
        val closeYearMonth: Int,
        val winner: List<Int>?,
    )

    companion object {
        /** PHP `GetBettingList.php` Validator `in 'req' ['bettingNation','tournament']`. */
        private val ALLOWED_REQ_TYPES = setOf("bettingNation", "tournament")
    }
}
