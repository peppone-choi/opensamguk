package opensamguk.engine.auction

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.serializeSeed
import opensamguk.common.wire.AuctionBidFail
import opensamguk.common.wire.AuctionBidOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.TurnGeneral
import opensamguk.infra.read.AuctionBidRepository
import opensamguk.infra.read.AuctionRepository
import opensamguk.logic.auction.AuctionBidItem
import opensamguk.logic.auction.AuctionBidItemData
import opensamguk.logic.auction.AuctionBidValidator
import opensamguk.logic.auction.AuctionInfo
import opensamguk.logic.auction.AuctionInfoDetail
import opensamguk.logic.auction.AuctionType
import opensamguk.logic.auction.BidValidationResult
import opensamguk.logic.auction.ObfuscatedNamePool
import opensamguk.logic.auction.ResourceType
import java.time.Instant

/**
 * 경매 입찰 핸들러 — [TurnDaemonCommand.AuctionBid] 명령 처리.
 *
 * PHP `Auction::_bid()`(Auction.php:350-455) + `Auction::bidInheritPoint()`(Auction.php:278-348) +
 * `Auction::refundBid()`(Auction.php:211-259) + 서브클래스 `AuctionBasicResource::bid()`
 * (AuctionBasicResource.php:233-254)의 충실 포팅. 핵심 도출 규칙(전부 PHP 인용):
 *
 *  - **R1 이전입찰 무효화** (Auction.php:399-403 / :293-297): 내 이전 입찰(`getMyPrevBid` = 내 최고액
 *    행)이 현재 최고가 행과 `no`(PK)가 다르면 "이미 환불 받았으니 무효" — null 취급. 환불 여부 플래그는
 *    DB 컬럼이 아니라 **암묵적**이다: 입찰 행이 살아있는 에스크로인 조건 = 그 행이 곧 현재 최고가 행.
 *  - **R2 차액 차감** (Auction.php:405,437 / :299,340): 입찰자는 `morePoint = amount - (유효 이전입찰액)`
 *    만 차감한다. 자기 상회입찰(내가 현재 최고가 보유) = 차액만, 그 외 = 전액.
 *  - **R3 조건부 환불** (Auction.php:450-452 / :343-345): `highestBid !== null && myPrevBid === null`
 *    일 때 **만** 직전 최고 입찰자에게 `highestBid->amount` **전액** 환불(refundBid :224-228).
 *    자기 상회입찰이면 환불 없음. R1 덕분에 한 행은 최고가 지위를 잃는 순간 단 한 번만 환불된다 —
 *    이후 영원히 무효(이중 환불 불가).
 *  - **R4 에스크로 불변식**: 임의 시점에 현재 최고 입찰자는 정확히 `highestBid.amount`를 누적 지불한
 *    상태고, 그 외 전원의 순지불액은 0이다 (R1+R2+R3의 귀결 — 자원 총량 보존).
 *
 * 종전 구현의 결함(이번 수정의 대상): (a) 환불이 무조건이라 자기 상회입찰 시 본인 에스크로가 환급되며
 * 자원이 복제됐고, (b) 무효화 없이 stale `myPrevBid` 차액을 적용해 환불완료된 입찰 차액만큼 미달
 * 차감됐고, (c) 유산포인트 차감이 `previous` 키가 아닌 별도 키(`auction_bid`)로 기록돼 실제 포인트
 * 잔액이 줄지 않았다.
 *
 * 유산포인트는 바퀴 20 정본 seam을 재사용한다: game_kv `"table"` 판별자 'inheritance' +
 * [ChangeRecorder.recordInheritancePointSet] (`previous` = [잔액, null]), read 는 [PlaceBetHandler]와
 * 동일한 previousPointReader + same-run pending 우선([effectivePreviousPoint]).
 *
 * PHP 환불 시 발송되는 private Message(Auction.php:248-258)는 격리(quarantine) — 입찰 경로는 로그/
 * 메시지를 일절 push 하지 않는다(바퀴 23: 비패러티 로그 1건이 flush BatchUpdateException → 턴 동결).
 *
 * 형제 turn 컴포넌트([PlaceBetHandler] 등)와 동일하게 per-run plain 클래스다.
 *
 * @param world 인메모리 턴 월드 — 장수 금/쌀 차감·환불
 * @param recorder 변경 녹음기 — auction/bid/inheritance flush 채널의 단일 dirty source
 * @param auctionRepository JPA read repository — 경매 조회
 * @param bidRepository JPA read repository — 입찰 조회
 * @param previousPointReader `inheritance_{owner}` `previous[0]` read seam —
 *        기본은 world meta `inheritancePrevious` 스냅샷([PlaceBetHandler]와 동일 기본)
 */
class AuctionBidHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    private val auctionRepository: AuctionRepository,
    private val bidRepository: AuctionBidRepository,
    private val previousPointReader: (ownerId: Int) -> Double = { ownerId ->
        ((world.getState().meta["inheritancePrevious"] as? Map<*, *>)?.get(ownerId) as? Number)?.toDouble() ?: 0.0
    },
) : TurnDaemonCommandHandler<TurnDaemonCommand.AuctionBid> {

    override fun handle(command: TurnDaemonCommand.AuctionBid): TurnDaemonCommandResult {
        val auctionId = command.auctionId
        val generalId = command.generalId
        val amount = command.amount

        fun fail(reason: String) = AuctionBidFail(auctionId = auctionId, reason = reason)

        // ── 1. 경매 조회 (PHP Auction 생성자 Auction.php:130-142) ───────────────
        val auctionEntity = auctionRepository.findById(auctionId).orElse(null)
            ?: return fail("해당 경매가 없습니다: $auctionId")

        val auction = toAuctionInfo(auctionEntity)
        val detail = auction.detail
        val isReverse = detail.isReverse ?: false
        val isInheritPoint = auction.reqResource == ResourceType.INHERITANCE_POINT

        // PHP API 의 tryExtendCloseDate 는 경로별 고정이다 — 자원 경매는 클라이언트 입력을 무시하고
        // 항상 true(BidBuyRiceAuction.php:44 `$auction->bid($amount, true)`), 유니크 경매는 미지정 시
        // false(BidUniqueAuction.php:41 `$this->args['extendCloseDate'] ?? false`).
        val tryExtendCloseDate = if (isInheritPoint) (command.tryExtendCloseDate ?: false) else true

        // ── 2. 장수 조회 (PHP는 세션이 보장 — 도달 불가 경로의 데몬 크래시 가드) ──
        val general = world.getGeneralById(generalId)
            ?: return fail("장수가 존재하지 않습니다.")

        // ── 3. 자원 경매 host 자기입찰 금지 (AuctionBasicResource.php:235-237) ───
        // 유니크 경매에는 이 가드가 없다 — 개설자가 시작가 self-first-bid 를 넣는다
        // (AuctionUniqueItem.php:113 `$auction->bid($startAmount, false)`).
        if (!isInheritPoint && auction.hostGeneralId == generalId) {
            return fail("자신이 연 경매에 입찰할 수 없습니다.")
        }

        // ── 4. finished — 유니크는 래퍼가 _bid 진입 전에 자체 검사한다(AuctionUniqueItem.php:143-144
        //       '경매가 종료되었습니다.'), 자원은 _bid 의 검사(Auction.php:355-357 '경매가 이미 끝났습니다.') ──
        if (auctionEntity.finished) {
            return fail(if (isInheritPoint) "경매가 종료되었습니다." else "경매가 이미 끝났습니다.")
        }

        // ── 4b. 유니크 래퍼 부위 가드 (AuctionUniqueItem.php:147-226) — _bid 진입 전 ──
        if (isInheritPoint) {
            uniqueWrapperGuard(auction, general)?.let { return fail(it) }
        }

        // ── 4c. wall-clock 입찰 차단 (Auction.php:359-366) — PHP `$now = new \DateTimeImmutable()` 와
        //       동일하게 핸들러 진입 시점의 실벽시계. 마감 경과~AuctionExpiryDaemon 틱 사이 창에서도
        //       PHP 와 같이 거부한다.
        val now = Instant.now()
        if (auctionEntity.closeDate < now) {
            return fail("경매가 이미 끝났습니다.")
        }
        if (auctionEntity.openDate > now) {
            return fail("경매가 아직 시작되지 않았습니다.")
        }

        // ── 5. 입찰 목록 = DB + same-run pending ([bidsOf]) ─────────────────────
        val allBids = bidsOf(auctionId)

        // ── 6. 최고가 / 내 이전 입찰 + R1 무효화 ───────────────────────────────
        // getHighestBid (Auction.php:78-106): 정방향 amount DESC / 역방향 ASC LIMIT 1.
        // getMyPrevBid (Auction.php:108-128): 동일 극값을 내 입찰로 한정.
        val highestBid = selectHighestBid(allBids, isReverse)
        val myPrevBidRaw = selectHighestBid(allBids.filter { it.generalId == generalId }, isReverse)
        // R1 (Auction.php:399-403 / :293-297): `$highestBid->no !== $myPrevBid->no` → "이미 환불
        // 받았으니 무효" — 내 이전 입찰은 그것이 곧 현재 최고가 행일 때만 살아있는 에스크로다.
        val myPrevBid = if (myPrevBidRaw != null && highestBid?.no != myPrevBidRaw.no) null else myPrevBidRaw

        // ── 7. 검증 (무효화 적용 후의 previousBidAmount 전달) ──────────────────
        val userId = general.userId?.toIntOrNull()
        val validationResult = if (isInheritPoint) {
            AuctionBidValidator.validateUniqueBid(
                bidAmount = amount,
                highestBidAmount = highestBid?.amount,
                // PHP getInheritancePoint(previous) (Auction.php:300) — owner 부재/npc>=2 면 **0** 반환
                // (InheritancePointManager.php:109-116 — KV 부재도 `?? [0, null]` 라 null 도달 불가),
                // 검증(:300-303)이 `currPoint < morePoint` 로 거부. userId null(주소 불가)도 같은
                // '유산포인트가 부족합니다.' 로 수렴한다.
                currentPoint = if (general.npcState >= 2) 0.0 else userId?.let { effectivePreviousPoint(it) },
                previousBidAmount = myPrevBid?.amount,
            )
        } else {
            AuctionBidValidator.validateBid(
                bidAmount = amount,
                currentWinningBidAmount = highestBid?.amount,
                finishBidAmount = detail.finishBidAmount,
                isReverse = isReverse,
                reqResource = auction.reqResource,
                generalResource = when (auction.reqResource) {
                    ResourceType.GOLD -> general.gold
                    ResourceType.RICE -> general.rice
                    ResourceType.INHERITANCE_POINT -> 0 // isInheritPoint 분기에서 도달 불가
                },
                previousBidAmount = myPrevBid?.amount,
            )
        }
        if (validationResult is BidValidationResult.Fail) {
            return fail(validationResult.reason)
        }

        // ── 8. R2 차액 (Auction.php:405 / :299) — 여기서부터 입찰 성공 ──────────
        val morePoint = AuctionBidValidator.calculateMorePoint(amount, myPrevBid?.amount)

        // ── 9. ng_auction_bid INSERT (Auction.php:418-432 / :308-321) ───────────
        // owner = 장수 소유 유저(PHP `$general->getVar('owner')`, :421/:311) — 환불 시 유산포인트
        // KV 네임스페이스 해석에 쓰인다. ownerName = `$general->getVar('owner_name')`(:426/:316 —
        // aux 첫 필드, NullIsUndefined). 유니크 경매의 표기명은 `genObfuscatedName(id)`(:305) —
        // [obfuscatedBidderName], 자원 경매는 실명 `getName()`(:427).
        val bidItem = AuctionBidItem(
            no = null,
            auctionId = auctionId,
            owner = userId,
            generalId = generalId,
            amount = amount,
            date = now.toString(),
            aux = AuctionBidItemData(
                ownerName = general.meta["owner_name"] as? String,
                generalName = if (isInheritPoint) obfuscatedBidderName(generalId) else general.name,
                tryExtendCloseDate = tryExtendCloseDate,
            ),
        )
        recorder.recordAuctionBidInsert(bidItem.toArray())

        // ── 10/11. 차감·연장 — 브랜치별 PHP 부수효과 순서를 그대로 따른다 ──────
        // 자원 _bid: INSERT(:432) → 차감(:437) → 연장(:439-448) → 환불(:450-452)
        // 유산 bidInheritPoint: INSERT(:321) → 연장(:329-338) → 차감(:340-341) → 환불(:343-345)
        // extendCloseDate(force=true) 는 remainCloseDateExtensionCnt 를 건드리지 않는다(:182-194 —
        // force 가 카운트 차감을 우회). 종전 구현의 bid 마다 -1 차감은 PHP 비정합이라 제거.
        val turnTerm = resolveTurnTerm()
        val closeAtMs = auctionEntity.closeDate.toEpochMilli()
        var newCloseMs = closeAtMs

        if (isInheritPoint) {
            // 연장 (Auction.php:329-338): availableLatestBidCloseDate != null 일 때만,
            // min(extended, availableLatest) 클램프. closeDate ≥ availableLatest 면 무연장(:334)은
            // 클램프+coerceAtLeast 로 등가(클램프 결과가 closeDate 미만이면 변경 없음).
            val availableLatest = detail.availableLatestBidCloseDate?.let {
                runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
            }
            if (availableLatest != null) {
                newCloseMs = opensamguk.logic.auction.AuctionResultCalculator.extendCloseDate(
                    now = now.toEpochMilli(),
                    closeAt = closeAtMs,
                    turnMinutes = turnTerm,
                    availableLatestBidCloseDate = availableLatest,
                ).coerceAtLeast(closeAtMs) // PHP extendCloseDate 는 단축 불가(:196-198)
            }
            if (newCloseMs != closeAtMs) {
                recordCloseDateChange(auction, auctionId, newCloseMs)
            }

            // 차감 (Auction.php:340-341): KV 는 PHP increaseInheritancePoint 의 가드를 따른다 —
            // owner 부재는 검증(:300-303)이 이미 차단, npc>=2 무기록(InheritancePointManager.php:264-267),
            // isunited != 0 무기록(:241-244). rank 가산은 별도 문장이라 게이트 밖(무조건).
            val ownerId = userId!!
            if (general.npcState < 2 && !isUnited()) {
                val current = effectivePreviousPoint(ownerId)
                recorder.recordInheritancePointSet(ownerId, "previous", current - morePoint, null)
            }
            recorder.recordRankIncrease(generalId, RankColumn.INHERIT_SPENT_DYN, morePoint)
        } else {
            // 차감 (Auction.php:437): increaseVar(resType, -morePoint) — 무조건 적용(역경매 자기
            // 하회입찰의 음수 morePoint = 차액 환급까지 동일 산식으로 처리된다).
            val updated = when (auction.reqResource) {
                ResourceType.GOLD -> general.copy(gold = general.gold - morePoint)
                ResourceType.RICE -> general.copy(rice = general.rice - morePoint)
                ResourceType.INHERITANCE_POINT -> general // 도달 불가
            }
            world.updateGeneral(updated)

            // 연장 (Auction.php:439-448): 무조건 계산, extended > closeDate 면 연장(availableLatest
            // 클램프 없음 — 자원 경매 detail 은 null).
            newCloseMs = opensamguk.logic.auction.AuctionResultCalculator.extendCloseDate(
                now = now.toEpochMilli(),
                closeAt = closeAtMs,
                turnMinutes = turnTerm,
                availableLatestBidCloseDate = null,
            ).coerceAtLeast(closeAtMs)

            // 즉시거래(자원 경매 한정, AuctionBasicResource.php:244-251): amount == finishBidAmount 면
            // shrinkCloseDate(now + 1턴) — 연장과 달리 단축이 허용된다(:149-160). PHP 는 _bid 복귀 후
            // 래퍼가 단축하지만(환불 뒤) close_date 외 상태와 독립이라 여기 fold 해도 최종 상태 동치.
            if (detail.finishBidAmount != null && amount == detail.finishBidAmount) {
                newCloseMs = now.toEpochMilli() + turnTerm.toLong() * 60_000L
            }
            if (newCloseMs != closeAtMs) {
                recordCloseDateChange(auction, auctionId, newCloseMs)
            }
        }

        // ── 12. R3 조건부 환불 (Auction.php:450-452 / :343-345) ─────────────────
        // `highestBid !== null && myPrevBid === null` 일 때만 — 자기 상회입찰(myPrevBid 생존)이면
        // 환불 없음. 무효화(R1)된 행은 이미 과거에 환불됐으므로 다시 환불되지 않는다.
        // (highestBid 가 내 행이면 myPrevBid == highestBid 로 생존하므로 자기 환불은 구조적으로 불가.)
        if (highestBid != null && myPrevBid == null) {
            // PHP refundBid(Auction.php:211-259) — reqResource 로 환불 채널 분기(:224-228).
            if (isInheritPoint) {
                refundInheritPoint(highestBid)
            } else {
                refundResource(highestBid, auction.reqResource)
            }
        }

        // ── 13. 로그/메시지 없음 — PHP bid 경로는 Logger push 0건(환불 private Message
        // Auction.php:248-258 는 격리). 위조 로그 1건이 flush BatchUpdateException → 턴 동결을
        // 유발했다(바퀴 23 제거 — 재추가 금지).

        return AuctionBidOk(
            auctionId = auctionId,
            closeAt = Instant.ofEpochMilli(newCloseMs).toString(),
        )
    }

    /**
     * 자원(금/쌀) 환불 — PHP `refundBid`(Auction.php:211-259)의 금/쌀 경로:
     * `increaseVar(reqResource, +amount)` (:228) **전액**.
     */
    private fun refundResource(bidItem: AuctionBidItem, reqResource: ResourceType) {
        val oldBidder = world.getGeneralById(bidItem.generalId) ?: return
        val updated = when (reqResource) {
            ResourceType.GOLD -> oldBidder.copy(gold = oldBidder.gold + bidItem.amount)
            ResourceType.RICE -> oldBidder.copy(rice = oldBidder.rice + bidItem.amount)
            ResourceType.INHERITANCE_POINT -> return // 호출부에서 분기 — 도달 불가
        }
        world.updateGeneral(updated)
    }

    /**
     * 유산포인트 환불 — PHP `refundBid`의 inheritancePoint 경로(Auction.php:218-226):
     * `oldBidder = General::createObjFromDB(bidItem->generalID)` — 환불 시점의 **현재** general 행에서
     * owner 를 재해석한다(입찰 행의 owner 스냅샷이 아니다 — 입찰 후 빙의 해제/이전된 장수에 stale
     * 환불 금지). 장수 부재(DummyGeneral)면 전체 no-op(:231-233). KV 가산(전액 +amount)은
     * increaseInheritancePoint 의 가드를 따른다 — owner 부재/npc>=2 무기록(InheritancePointManager.php:
     * 264-267), isunited != 0 무기록(:241-244). rank `inherit_point_spent_dynamic -= amount` 는 별도
     * 문장이라 게이트 밖(실존 장수면 무조건, :226).
     */
    private fun refundInheritPoint(bidItem: AuctionBidItem) {
        val oldBidder = world.getGeneralById(bidItem.generalId)
            ?: return // PHP DummyGeneral 환불은 no-op (:231-233)
        val ownerId = oldBidder.userId?.toIntOrNull()
        if (ownerId != null && oldBidder.npcState < 2 && !isUnited()) {
            val current = effectivePreviousPoint(ownerId)
            recorder.recordInheritancePointSet(ownerId, "previous", current + bidItem.amount, null)
        }
        recorder.recordRankIncrease(bidItem.generalId, RankColumn.INHERIT_SPENT_DYN, -bidItem.amount)
    }

    /**
     * 한 경매의 입찰 목록 — DB 행 + 같은 런의 pending [ChangeRecorder.auctionBidInserts] 합산.
     * PHP 는 bid 마다 즉시 INSERT 라(Auction.php:321/:432) 다음 bid 가 곧바로 본다 — pending 을 합쳐
     * 같은 런 내 가시성을 충실 재현한다([PlaceBetHandler]의 누적 한도 pending 합산과 같은 장치).
     * synthetic `no` = max(DB no) + 기록순 — auto-increment 부여 순서의 충실 재현(R1 의 `no` 비교가
     * pending 행에도 성립한다).
     */
    private fun bidsOf(auctionId: Int): List<AuctionBidItem> {
        val dbBids = bidRepository.findByAuctionIdOrderByAmountDesc(auctionId)
            .map { toAuctionBidItem(it) }
        val maxDbNo = dbBids.maxOfOrNull { it.no ?: 0 } ?: 0
        val pendingBids = recorder.auctionBidInserts()
            .filter { (it.columns["auction_id"] as? Number)?.toInt() == auctionId }
            .mapIndexed { i, ins -> AuctionBidItem.fromArray(ins.columns).copy(no = maxDbNo + i + 1) }
        return dbBids + pendingBids
    }

    /**
     * 유니크 경매 래퍼 부위 가드 (PHP `AuctionUniqueItem::bid()` AuctionUniqueItem.php:140-226) —
     * `_bid` 진입 전 2종 거부.
     *
     *  1. **보유 가드** (:185-200): 대상 아이템 코드가 속한 부위(itemType)에 이미 비구매성(진귀)
     *     아이템을 장착 중이면 `'이미 가진 아이템이 있습니다.'`. 비구매성 판별은 [GameConst.allItems]
     *     cnt>0 ⟺ !isBuyable ([opensamguk.logic.world.UpdateNationLevel.ownedNonBuyableCount] 와
     *     동일하게 정본화된 등가). 보유 검사는 cnt<=0 continue 보다 **먼저**다(:193-195).
     *  2. **1순위 중복 가드** (:202-226): 다른 열린 유니크 경매에서 내가 현재 1순위(최고 입찰)이고
     *     그 대상이 같은 부위면 `'1순위 입찰자인 경매중에 같은 부위가 있습니다.'`.
     *
     * 열린 유니크 경매 목록(:148-152)은 DB 조회([AuctionOpenHandler]의 동일-아이템 중복 검사와 같은
     * 선례), id 순 정렬로 PHP 의 PK 스캔 순서를 결정화. 경매별 최고 입찰은 [bidsOf](DB + same-run
     * pending). PHP grouped dict(`convertArrayToDict`)는 동률 시 마지막 스캔 행이 살아남으므로 동액
     * 최고가는 `no` 최대 행을 취한다.
     */
    private fun uniqueWrapperGuard(auction: AuctionInfo, general: TurnGeneral): String? {
        // (:186-188) 대상 코드 부재 — PHP throw '아이템 코드가 없습니다.' (데몬 크래시 가드로 fail 변환)
        val itemCode = auction.target ?: return "아이템 코드가 없습니다."

        // (:190-200) 부위 수집 + 보유 가드 — 대상 코드를 포함하는 부위만 순회(key_exists).
        val equipped = mapOf(
            "horse" to general.role.items.horse,
            "weapon" to general.role.items.weapon,
            "book" to general.role.items.book,
            "item" to general.role.items.item,
        )
        val bidItemTypes = mutableSetOf<String>()
        for ((itemType, itemList) in GameConst.allItems) {
            if (itemCode !in itemList) continue
            val ownCode = equipped[itemType]
            if (ownCode != null && ownCode != "None" && (itemList[ownCode] ?: 0) > 0) {
                return "이미 가진 아이템이 있습니다."
            }
            if ((itemList[itemCode] ?: 0) <= 0) continue
            bidItemTypes += itemType
        }

        // (:202-226) 1순위 중복 가드.
        val openUnique = auctionRepository.findByFinishedFalseAndType(AuctionType.UNIQUE_ITEM)
            .sortedBy { it.id ?: 0 }
        for (other in openUnique) {
            val otherId = other.id ?: continue
            if (otherId == auction.id) continue
            val otherBids = bidsOf(otherId)
            if (otherBids.isEmpty()) continue
            val maxAmount = otherBids.maxOf { it.amount }
            val highest = otherBids.filter { it.amount == maxAmount }.maxByOrNull { it.no ?: 0 } ?: continue
            if (highest.generalId != general.id) continue
            val compCode = other.target ?: continue
            for ((itemType, itemList) in GameConst.allItems) {
                if ((itemList[compCode] ?: 0) <= 0) continue
                if (itemType in bidItemTypes) {
                    return "1순위 입찰자인 경매중에 같은 부위가 있습니다."
                }
            }
        }
        return null
    }

    /**
     * 유니크 경매 입찰자 난독 이름 (PHP `Auction::genObfuscatedName(id)` Auction.php:35-65) —
     * [AuctionOpenHandler]의 host 난독화와 동일 메커니즘: `state.meta['hiddenSeed']` 로부터
     * `serializeSeed(hiddenSeed, KV_KEY)` 시드로 풀을 결정적으로 빌드해 id 를 디코드한다
     * (동일 시드 → 동일 풀 — PHP 의 KV 캐시 유무와 무관하게 byte-faithful). hiddenSeed 부재
     * (시드 미주입 월드)에는 중립 placeholder — PHP 유니크 경로는 어떤 경우에도 실명을 aux 에
     * 쓰지 않으므로 실명 폴백 금지([AuctionOpenHandler]의 '(상인)' 폴백과 동형).
     */
    private fun obfuscatedBidderName(generalId: Int): String {
        val hiddenSeed = world.getState().meta["hiddenSeed"] as? String ?: return NEUTRAL_BIDDER_NAME
        val pool = ObfuscatedNamePool.buildPool(serializeSeed(hiddenSeed, ObfuscatedNamePool.KV_KEY))
        return ObfuscatedNamePool.decode(generalId, pool)
    }

    /** game_env `isunited` != 0 — 통일 후 유산포인트 KV 증감 전면 무기록(InheritancePointManager.php:241-244). */
    private fun isUnited(): Boolean = ((world.getState().meta["isunited"] as? Number)?.toInt() ?: 0) != 0

    /** close_date 변경 기록 — PHP 는 close_date 가 실제로 변할 때만 applyDB(:336/:447/:157), detail 불변. */
    private fun recordCloseDateChange(auction: AuctionInfo, auctionId: Int, newCloseMs: Long) {
        val updatedAuction = auction.copy(closeDate = Instant.ofEpochMilli(newCloseMs).toString())
        recorder.recordAuctionUpsert(
            id = auctionId,
            columns = updatedAuction.toArray(withoutId = true),
        )
    }

    /**
     * `previous[0]` 유효값 — 같은 런의 pending [ChangeRecorder.inheritanceKvWrites]가 있으면 마지막
     * 쓰기가 우선(PHP setValue 는 즉시 가시), 없으면 [previousPointReader].
     * [PlaceBetHandler.effectivePreviousPoint]와 동일한 same-run 정합 장치다.
     */
    private fun effectivePreviousPoint(ownerId: Int): Double {
        val pending = recorder.inheritanceKvWrites()
            .lastOrNull { it.namespace == "inheritance_$ownerId" && it.key == "previous" }
        if (pending != null) {
            return ((pending.value as? List<*>)?.getOrNull(0) as? Number)?.toDouble() ?: 0.0
        }
        return previousPointReader(ownerId)
    }

    /**
     * isReverse 에 따라 최고/최저 입찰을 선택한다 (PHP getHighestBid — 정방향 DESC / 역방향 ASC
     * LIMIT 1; 동률은 공급 순서 첫 행 = DB 스캔 순서의 충실 재현).
     */
    private fun selectHighestBid(bids: List<AuctionBidItem>, isReverse: Boolean): AuctionBidItem? {
        if (bids.isEmpty()) return null
        return if (isReverse) bids.minByOrNull { it.amount } else bids.maxByOrNull { it.amount }
    }

    /**
     * world state에서 turnterm을 해석한다 (분 단위).
     */
    private fun resolveTurnTerm(): Int {
        val state = world.getState()
        return (state.meta["turnterm"] as? Number)?.toInt() ?: (state.tickSeconds / 60)
    }

    /**
     * JPA [opensamguk.infra.entity.AuctionEntity] → logic [AuctionInfo].
     */
    private fun toAuctionInfo(entity: opensamguk.infra.entity.AuctionEntity): AuctionInfo {
        val detail = AuctionInfoDetail.fromArray(
            opensamguk.logic.util.jsonDecode(entity.detail)
        )
        return AuctionInfo(
            id = entity.id,
            type = entity.type,
            finished = entity.finished,
            target = entity.target,
            hostGeneralId = entity.hostGeneralId,
            reqResource = entity.reqResource,
            openDate = entity.openDate.toString(),
            closeDate = entity.closeDate.toString(),
            detail = detail,
        )
    }

    /**
     * JPA [opensamguk.infra.entity.AuctionBidEntity] → logic [AuctionBidItem].
     */
    private fun toAuctionBidItem(entity: opensamguk.infra.entity.AuctionBidEntity): AuctionBidItem {
        val aux = AuctionBidItemData.fromArray(
            opensamguk.logic.util.jsonDecode(entity.aux)
        )
        return AuctionBidItem(
            no = entity.no,
            auctionId = entity.auctionId,
            owner = entity.owner,
            generalId = entity.generalId,
            amount = entity.amount,
            date = entity.date.toString(),
            aux = aux,
        )
    }

    companion object {
        /** hiddenSeed 부재 시 입찰자 중립화 placeholder — 실명 누출 금지(AuctionOpenHandler '(상인)' 동형). */
        private const val NEUTRAL_BIDDER_NAME = "(상인)"
    }
}
