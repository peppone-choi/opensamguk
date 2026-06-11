package opensamguk.engine.betting

import opensamguk.common.constants.GameConst
import opensamguk.common.wire.PlaceBetFail
import opensamguk.common.wire.PlaceBetOk
import opensamguk.common.wire.TurnDaemonCommand
import opensamguk.common.wire.TurnDaemonCommandResult
import opensamguk.engine.auction.TurnDaemonCommandHandler
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.RankColumn
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.util.jsonEncode

/**
 * 베팅 참여 핸들러 — [TurnDaemonCommand.PlaceBet] 명령 처리.
 *
 * PHP `Betting::bet()`(Betting.php:100-183) + 생성자 마스터 조회(:40-49) +
 * `purifyBettingKey`(:56-74)의 충실 포팅. 검증/부수효과 순서는 PHP와 동일:
 *  1. 베팅 마스터 조회 — 부재 시 '해당 베팅이 없습니다: {id}' (:45-47)
 *  2. finished 검사 — '이미 종료된 베팅입니다' (:104-110, PHP는 동일 검사 2회 — 행동 동일하므로 1회)
 *  3. 마감/미시작 검사 — `closeYearMonth <= ym` / `openYearMonth > ym`,
 *     ym = `Util::joinYearMonth(year,month)` = `year*12+month-1` (:114-124)
 *  4. 선택 수 검사 — `count != selectCnt` → '필요한 선택 수를 채우지 못했습니다.' (:126-128)
 *  5. purifyBettingKey — sort+unique 후 수 불일치 '중복된 값이 있습니다.', 후보 외 키
 *     '올바른 후보가 아닙니다.'+print_r, 키 = Json::encode(정렬배열) (:56-74,131)
 *  6. 누적 한도 — `sum(amount) WHERE betting_id AND user_id` + 신규 > 1000 →
 *     '{잔여}{유산포인트|금}까지만 베팅 가능합니다.' (:135-139). DB 합 + 동일 런 pending
 *     [ChangeRecorder.bettingInserts]를 합산(PHP는 즉시 INSERT라 다음 bet이 곧바로 본다).
 *  7. 재원 검사 — 유산포인트면 `previous[0] < amount` → '유산포인트가 충분하지 않습니다.',
 *     금이면 `gold < minGoldRequiredWhenBetting(500) + amount` → '금이 부족합니다.' (:141-151)
 *  8. `ng_betting` insertUpdate(재베팅 시 amount += — flush가 ON CONFLICT UPSERT) (:162-166)
 *  9. 부수효과 — 유산포인트: previous=[잔여-amount,null] + UserLogger '{amount} 포인트를 베팅에 사용'
 *     (tag inheritPoint) + rank `inherit_spent_dyn` += amount / 금: general.gold -= amount +
 *     rank `betgold` += amount (:167-182)
 *
 * PHP에는 장수 액션 로그 push가 없다 — 비패러티 로그를 만들지 않는다(P0-07).
 * `amount >= 10` 최소액은 PHP API 레이어(Bet.php:30 validator `min 10`) 게이트 — 인테이크가
 * precheck 없이 202-수락하는 본 아키텍처에선 핸들러 선두(0단계)가 등가 지점이다.
 *
 * read seam 3종은 [InheritResetHandler]의 reader-주입 패턴을 따른다(테스트는 fake, prod는
 * [TurnDaemonCommandDispatcher]가 infra read repo로 배선).
 */
class PlaceBetHandler(
    private val world: InMemoryTurnWorld,
    private val recorder: ChangeRecorder,
    /** 베팅 마스터 read seam — game_kv(table='betting')에서 id 일치 행의 [BettingInfo]. null = 마스터 부재. */
    private val bettingInfoReader: (bettingId: Int) -> BettingInfo? = { null },
    /** ng_betting 누적 합 read seam — PHP `SELECT sum(amount) … WHERE betting_id = ? AND user_id = ?`(:135). */
    private val prevBetAmountDbReader: (bettingId: Int, userId: Int) -> Int = { _, _ -> 0 },
    /** inheritance_{owner} `previous[0]` read seam — 기본은 world meta `inheritancePrevious` 스냅샷
     *  ([InheritResetHandler.previousPointReader]와 동일 기본). */
    private val previousPointReader: (ownerId: Int) -> Double = { ownerId ->
        ((world.getState().meta["inheritancePrevious"] as? Map<*, *>)?.get(ownerId) as? Number)?.toDouble() ?: 0.0
    },
) : TurnDaemonCommandHandler<TurnDaemonCommand.PlaceBet> {

    override fun handle(command: TurnDaemonCommand.PlaceBet): TurnDaemonCommandResult {
        val bettingId = command.bettingId
        val generalId = command.generalId
        val amount = command.amount

        fun fail(reason: String) = PlaceBetFail(bettingId = bettingId, reason = reason)

        // ── 0. API validator `min 10` (Bet.php:30) — PHP는 API 레이어가 bet() 도달 전에 거른다.
        // 본 아키텍처는 인테이크가 precheck 없이 202-수락하므로 핸들러 선두(마스터 조회보다 앞 =
        // PHP 관측 순서)가 등가 지점. 메시지 = valitron rule() '{field} ' prepend(Validator.php:1434-1439)
        // + lang/ko.php 'min' + ucwords('amount') 추적치. 음수 금 채굴 차단 게이트이기도 하다.
        if (amount < 10) return fail("Amount 은(는) 10 이상이어야 합니다.")

        // ── 1. 베팅 마스터 조회 (PHP Betting 생성자 :45-47) ─────────────────────
        val info = bettingInfoReader(bettingId)
            ?: return fail("해당 베팅이 없습니다: $bettingId")

        // 장수 조회 — PHP는 세션이 보장해 검사 없음(도달 불가 경로의 데몬 크래시 가드).
        val general = world.getGeneralById(generalId)
            ?: return fail("장수가 존재하지 않습니다.")
        // 소유 유저 = TurnGeneral.userId(typed 채널 — MakeGeneralHandler가 기록, loader가 적재).
        // PHP `$session->userID`(API/Betting/Bet.php:54) 동형. NPC는 null.
        val userId = general.userId?.toIntOrNull()

        // ── 2. finished (:104-110) ──────────────────────────────────────────────
        if (info.finished) return fail("이미 종료된 베팅입니다")

        // ── 3. 마감/미시작 (:114-124) — joinYearMonth = year*12 + month - 1 ────
        val state = world.getState()
        val yearMonth = state.currentYear * 12 + state.currentMonth - 1
        if (info.closeYearMonth <= yearMonth) return fail("이미 마감된 베팅입니다")
        if (info.openYearMonth > yearMonth) return fail("아직 시작되지 않은 베팅입니다")

        // ── 4. 선택 수 (:126-128) ───────────────────────────────────────────────
        if (command.bettingType.size != info.selectCnt) {
            return fail("필요한 선택 수를 채우지 못했습니다.")
        }

        val resKey = if (info.reqInheritancePoint) "유산포인트" else "금"

        // ── 5. purifyBettingKey (:56-74) → Json::encode 키 (:51-54) ────────────
        val purified = command.bettingType.sorted().distinct()
        if (purified.size != info.selectCnt) return fail("중복된 값이 있습니다.")
        for (key in purified) {
            if (!info.candidates.containsKey(key)) {
                return fail("올바른 후보가 아닙니다." + printR(purified))
            }
        }
        val bettingTypeKey = jsonEncode(purified)

        // ── 6. 누적 1000 한도 (:135-139) — PHP `user_id = NULL`은 어떤 행과도 매치 안 됨 ──
        val prevBetAmount = if (userId == null) 0 else {
            prevBetAmountDbReader(bettingId, userId) + recorder.bettingInserts()
                .filter {
                    (it.columns["betting_id"] as? Number)?.toInt() == bettingId &&
                        (it.columns["user_id"] as? Number)?.toInt() == userId
                }
                .sumOf { (it.columns["amount"] as? Number)?.toInt() ?: 0 }
        }
        if (prevBetAmount + amount > 1000) {
            return fail("${1000 - prevBetAmount}${resKey}까지만 베팅 가능합니다.")
        }

        // ── 7. 재원 검사 (:141-151) ─────────────────────────────────────────────
        val remainPoint: Double
        if (info.reqInheritancePoint) {
            remainPoint = userId?.let { effectivePreviousPoint(it) } ?: 0.0
            // userId null = PHP라면 API validator/세션이 막는 도달 불가 경로 — 동일 메시지로 deny.
            if (userId == null || remainPoint < amount) {
                return fail("유산포인트가 충분하지 않습니다.")
            }
        } else {
            remainPoint = general.gold.toDouble()
            if (general.gold < GameConst.minGoldRequiredWhenBetting + amount) {
                return fail("금이 부족합니다.")
            }
        }

        // ── 8. ng_betting insertUpdate (:162-166) — flush가 (general,betting,type) UPSERT amount += ──
        recorder.recordBettingInsert(
            linkedMapOf(
                "betting_id" to bettingId,
                "general_id" to generalId,
                "user_id" to userId,
                "betting_type" to bettingTypeKey,
                "amount" to amount,
            )
        )

        // ── 9. 부수효과 (:167-182) ──────────────────────────────────────────────
        if (info.reqInheritancePoint) {
            recorder.recordInheritancePointSet(userId!!, "previous", remainPoint - amount, null)
            recorder.recordInheritanceLog(userId, "$amount 포인트를 베팅에 사용", "inheritPoint")
            recorder.recordRankIncrease(generalId, RankColumn.INHERIT_SPENT_DYN, amount)
        } else {
            world.updateGeneral(general.copy(gold = general.gold - amount))
            recorder.recordRankIncrease(generalId, RankColumn.BETGOLD, amount)
        }

        return PlaceBetOk(
            bettingId = bettingId,
            generalId = generalId,
            amount = amount,
        )
    }

    /**
     * `previous[0]` 유효값 — 같은 런의 pending [ChangeRecorder.inheritanceKvWrites]가 있으면 마지막
     * 쓰기가 우선(PHP setValue는 즉시 가시 — Betting.php:168→:142). 없으면 [previousPointReader].
     * 1000 한도의 pending [ChangeRecorder.bettingInserts] 합산과 같은 이유의 same-run 정합 장치다.
     */
    private fun effectivePreviousPoint(ownerId: Int): Double {
        val pending = recorder.inheritanceKvWrites()
            .lastOrNull { it.namespace == "inheritance_$ownerId" && it.key == "previous" }
        if (pending != null) {
            return ((pending.value as? List<*>)?.getOrNull(0) as? Number)?.toDouble() ?: 0.0
        }
        return previousPointReader(ownerId)
    }

    /** PHP `print_r($arr, true)` — '올바른 후보가 아닙니다.' 뒤에 붙는 배열 덤프 byte-동형. */
    private fun printR(values: List<Int>): String = buildString {
        append("Array\n(\n")
        values.forEachIndexed { i, v -> append("    [$i] => $v\n") }
        append(")\n")
    }
}
