package opensamguk.logic.event

import kotlinx.serialization.json.JsonPrimitive
import opensamguk.logic.betting.BettingEngine
import opensamguk.logic.betting.BettingInfo
import opensamguk.logic.betting.BettingItem
import opensamguk.logic.betting.BettingWorldView

/**
 * 국가 강약 베팅 마감 이벤트 액션 — PHP `sammo\Event\Action\FinishNationBetting` 의 Kotlin 포팅.
 *
 * 동작:
 * 1. KV 스토리지에서 [BettingInfo] 조회
 * 2. 타입 검증 (`bettingNation`)
 * 3. 남은 국가 수 확인 → `selectCnt` 와 일치 여부 판정
 * 4. nation_id → betting_type 인덱스 매핑
 * 5. [BettingEngine.calcReward] + [BettingEngine.giveReward] 로 보상 분배
 * 6. 글로벌 히스토리 로그 푸시
 *
 * Factory args:
 * - args[0]: bettingId (Int — PHP `int $bettingID`)
 *
 * 등록:
 * ```kotlin
 * factory.register(FinishNationBettingAction.NAME) { args ->
 *     val bettingId = (args[0] as JsonPrimitive).content.toInt()
 *     FinishNationBettingAction(bettingId)
 * }
 * ```
 */
class FinishNationBettingAction(
    private val bettingId: Int,
) : EventAction {

    override fun run(ctx: EventActionContext) {
        val world = ctx as? FinishNationBettingContext
            ?: error("FinishNationBettingAction requires a FinishNationBettingContext")

        // 1. KV 스토리지에서 BettingInfo 조회
        val bettingInfo = world.loadBettingInfo(bettingId)
            ?: return  // NotFound — 조용히 반환

        // 2. 타입 검증
        if (bettingInfo.type != "bettingNation") {
            return
        }

        // 3. 남은 국가 수 확인
        val winnerNations = world.aliveNationIds()
        if (winnerNations.size != bettingInfo.selectCnt) {
            return
        }

        // 4. nation_id 와 betting_type 인덱스 매핑
        val nationIDMap = mutableMapOf<Int, Int>()
        for ((idx, candidate) in bettingInfo.candidates) {
            val aux = candidate.aux
                ?: return
            val nationId = (aux["nation"] as? Number)?.toInt() ?: return
            nationIDMap[nationId] = idx
        }

        val winnerTypes = mutableListOf<Int>()
        var notInBettingWinner = 0
        for (winnerNationID in winnerNations) {
            if (winnerNationID in nationIDMap) {
                winnerTypes.add(nationIDMap[winnerNationID]!!)
            } else {
                // 혹시라도 베팅 시점 이후에 생성된 국가라 없을 수 있다.
                winnerTypes.add(nationIDMap.size + notInBettingWinner)
                notInBettingWinner += 1
            }
        }

        // 5. BettingEngine으로 보상 계산/지급
        val engine = BettingEngine(bettingInfo)
        val purifiedWinnerTypes = engine.purifyBettingKey(winnerTypes, noValidate = notInBettingWinner > 0)
        val allBets = world.loadBettingItems(bettingId)

        engine.giveReward(purifiedWinnerTypes, allBets, world)

        // 6. 글로벌 히스토리 로그
        val (openYear, openMonth) = parseYearMonth(bettingInfo.openYearMonth)
        world.pushGlobalHistoryLog(
            "<B><b>【내기】</b></> ${openYear}년 ${openMonth}월에 열렸던 ${bettingInfo.name} 내기의 결과가 나왔습니다!",
            LightActionWorld.EVENT_YEAR_MONTH,
        )
    }

    companion object {
        const val NAME = "FinishNationBetting"

        /** BettingInfo KV 키 접두사 */
        const val KV_KEY_PREFIX = "betting:"

        private fun parseYearMonth(yearMonth: Int): Pair<Int, Int> {
            val year = yearMonth / 12
            val month = yearMonth % 12 + 1
            return year to month
        }

        /**
         * [EventActionFactory]에 `FinishNationBetting` 리프를 등록한다.
         *
         * Args: [bettingId(Int)]
         */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args ->
                val bettingId = (args[0] as JsonPrimitive).content.toInt()
                FinishNationBettingAction(bettingId)
            }
    }
}

/**
 * [FinishNationBettingAction]이 필요로 하는 rich context.
 * 데몬 측 [InMemoryTurnWorld] 어댑터가 [EventActionContext] + [BettingWorldView] 를 구현한다.
 */
interface FinishNationBettingContext : EventActionContext, BettingWorldView {
    /** KV 스토리지에서 [BettingInfo] 조회. */
    fun loadBettingInfo(bettingId: Int): BettingInfo?

    /** 살아있는 국가 ID 목록 (level > 0). */
    fun aliveNationIds(): List<Int>

    /** 해당 베팅의 전체 참여 레코드 조회. */
    fun loadBettingItems(bettingId: Int): List<BettingItem>

    /** 글로벌 히스토리 로그 푸시. */
    fun pushGlobalHistoryLog(msg: String, type: Int)
}
