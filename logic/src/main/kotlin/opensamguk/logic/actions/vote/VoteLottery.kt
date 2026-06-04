package opensamguk.logic.actions.vote

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * voteUnique 유니크-아이템 추첨 — RNG 패러티 CORE.
 *
 * PHP grand truth (legacy/devsam-core):
 *   - hwe/sammo/API/Vote/Vote.php:111-117  추첨 전용 RNG 시드 =
 *       new RandUtil(new LiteHashDRBG(Util::simpleSerialize(UniqueConst::$hiddenSeed,'voteUnique',$voteID,$generalID)))
 *   - hwe/func.php:1611-1703  tryUniqueItemLottery — '설문조사' 분기 prob 공식 + 0.25 clamp + /sqrt(7)
 *       + moreProb = 10^(1/4) 에스컬레이션 + maxCnt 루프 + npc>=2 조기 반환 가드.
 *   - hwe/func.php:1487-1609  giveRandomUniqueItem — choiceUsingWeightPair($availableUnique) + GameConst::$allItems 가중 풀.
 *
 * 추첨은 per-action "generalCommand"/"nationCommand" 시드와 분리된 전용 RNG(컴포넌트-2 리터럴 "voteUnique")를
 * 타므로, action draw 스트림을 절대 재배열하지 않는다. npc>=2 가드는 RNG를 건드리기 전에 반환하므로 NPC 추첨은
 * 0 draw 비용이다. (Vote 라우트는 REQ_GAME_LOGIN — scenario_1010 은 플레이어 장수가 0명이라 가드는 reachability
 * 게이트로만 작동하고, 추첨 수학 자체는 100% 실제 PHP 경로다.)
 *
 * draw-for-draw 패러티: maxCnt 루프 안에서 매 반복 nextBool(prob) → 미스 시 prob*=moreProb. 첫 성공에서 break하고
 * 단 한 번 choiceUsingWeightPair 로 아이템을 뽑는다. draw의 ORDER/COUNT/ARGS 모두 패러티 타깃이다.
 */

// ── GameConst 상수 (hwe/sammo/GameConstBase.php) — 입력 파생에 필요한 추첨 계수 ─────────────────────────
/** GameConstBase.php:225 $uniqueTrialCoef = 1 — prob *= 이 계수. */
const val UNIQUE_TRIAL_COEF: Double = 1.0

/** GameConstBase.php:226 $maxUniqueTrialProb = 0.25 — prob 의 상한 clamp. */
const val MAX_UNIQUE_TRIAL_PROB: Double = 0.25

/** func.php:1679 moreProb = pow(10, 1/4) — 매 미스마다 prob 에스컬레이션 배수. */
val MORE_PROB: Double = 10.0.pow(0.25)

/**
 * 추첨 전용 RNG 시드 문자열 — Vote.php:111-116 의 simpleSerialize(hiddenSeed,'voteUnique',voteID,generalID).
 * 기존 'generalCommand' 6-컴포넌트 시드 빌더(serializeSeed)와 동일 메커니즘을 mirror하되, 컴포넌트는 4개다.
 */
fun voteUniqueSeed(hiddenSeed: String, voteId: Int, generalId: Int): String =
    serializeSeed(hiddenSeed, "voteUnique", voteId, generalId)

/** voteUniqueSeed 로 시딩한 추첨 전용 RandUtil 을 만든다 (production 진입점). */
fun voteUniqueRng(hiddenSeed: String, voteId: Int, generalId: Int): RandUtil =
    RandUtil(LiteHashDrbg(voteUniqueSeed(hiddenSeed, voteId, generalId)))

/** 가중 아이템 풀의 한 항목 — [[itemType, itemCode], weight] (PHP $availableUnique 의 한 원소). */
data class UniqueItemEntry(val itemType: String, val itemCode: String, val weight: Int)

/**
 * 추첨에 들어가는 PINNED 입력 — 골든이 고정한 값을 그대로 주입할 수 있게 한 묶음.
 * production 경로는 [deriveSurveyLotteryInputs] 로 이 값을 계산하지만, 골든 테스트는 고정값을 먹인다.
 */
data class VoteLotteryInputs(
    val genCount: Int,
    val itemTypeCnt: Int,
    val maxCnt: Int,
    val prob0: Double,
    val moreProb: Double,
    val itemPool: List<UniqueItemEntry>,
)

/** 추첨 결과 — 당첨 여부 + (당첨 시) 뽑힌 아이템. */
data class LotteryResult(
    val won: Boolean,
    val itemType: String? = null,
    val itemCode: String? = null,
)

/**
 * func.php:1611-1703 tryUniqueItemLottery — '설문조사'(Vote) 경로의 draw 시퀀스를 그대로 미러.
 *
 * 시퀀스 (정확히 PHP 순서):
 *   prob = inputs.prob0  (이미 '설문조사' 공식 + coef + 0.25 clamp + /sqrt(7) 까지 적용된 값)
 *   foreach (Util::range(maxCnt)):           // line 1689
 *       if (rng->nextBool(prob)) { result=true; break }   // line 1690-1693
 *       prob *= moreProb                                    // line 1694
 *   if (!result) return false                               // line 1696-1699
 *   return giveRandomUniqueItem(rng, ...)                   // line 1702 → 당첨 시 단 한 번 choiceUsingWeightPair
 *
 * @param inertNpc npc>=2 가드(line 1616-1618). true면 RNG 를 건드리지 않고 즉시 미당첨 (0 draw).
 */
fun tryUniqueItemLottery(
    rng: RandUtil,
    inputs: VoteLotteryInputs,
    inertNpc: Boolean = false,
): LotteryResult {
    // npc>=2 단락 — RNG 사용 전에 반환해야 action 스트림이 절대 교란되지 않는다.
    if (inertNpc) return LotteryResult(won = false)

    var prob = inputs.prob0
    var won = false
    // PHP: foreach (Util::range($maxCnt) as $_idx) — maxCnt 회 반복하되 첫 nextBool 성공에서 break.
    // (return@repeat 은 break 가 아니라 continue 이므로 쓰면 안 됨 — 당첨 후에도 잔여 nextBool draw 가 새어
    //  draw COUNT 가 desync된다. 반드시 명시적 break 가능한 루프를 쓴다.)
    for (idx in 0 until inputs.maxCnt) {
        if (rng.nextBool(prob)) {
            won = true
            break // PHP: $result = true; break;
        }
        prob *= inputs.moreProb // PHP: $prob *= $moreProb;
    }
    if (!won) return LotteryResult(won = false)

    // 당첨 시 단 한 번 choiceUsingWeightPair (giveRandomUniqueItem).
    val (itemType, itemCode) = giveRandomUniqueItem(rng, inputs.itemPool)
    return LotteryResult(won = true, itemType = itemType, itemCode = itemCode)
}

/**
 * func.php:1487-1609 giveRandomUniqueItem (아이템 선택 부분만) — 가중 풀에서 choiceUsingWeightPair 로 1개 선택.
 *
 * PHP line 1588: [$itemType, $itemCode] = $rng->choiceUsingWeightPair($availableUnique);
 * 풀은 [[itemType,itemCode], weight] 들의 삽입-순서 리스트다 (LinkedHashMap 의미 보존).
 *
 * @return 뽑힌 (itemType, itemCode)
 */
fun giveRandomUniqueItem(rng: RandUtil, itemPool: List<UniqueItemEntry>): Pair<String, String> {
    require(itemPool.isNotEmpty()) { "availableUnique is empty" }
    // RandUtil.choiceUsingWeightPair(List<Pair<T, Double>>) 를 그대로 사용 — PHP RandUtil::choiceUsingWeightPair 와
    // draw-for-draw 동일(단일 nextFloat1, 가중 누적 스캔, <= 비교).
    val pairs: List<Pair<Pair<String, String>, Double>> =
        itemPool.map { (it.itemType to it.itemCode) to it.weight.toDouble() }
    return rng.choiceUsingWeightPair(pairs)
}

// ── 입력 파생 (production 계산용; 골든 테스트는 PINNED 값을 주입) ──────────────────────────────────────

/**
 * '설문조사'(Vote) 경로의 prob0 파생 — func.php:1659-1684 의 정확한 공식.
 *
 *   scenario>=100 기본:  prob = 1 / (genCount * itemTypeCnt)
 *   acquireType=='설문조사': prob = 1 / (genCount * itemTypeCnt * 0.7 / 3)   // 투표율 70%, 1회 2~3개 등장
 *   prob *= uniqueTrialCoef
 *   prob = valueFit(prob, null, maxUniqueTrialProb)   // 0.25 상한 clamp
 *   prob /= sqrt(7)
 *
 * (건국/inheritRandomUnique 의 prob=1 분기는 Vote 경로에선 도달 불가 — 여기서는 다루지 않는다.)
 */
fun deriveSurveyProb0(
    genCount: Int,
    itemTypeCnt: Int,
    coef: Double = UNIQUE_TRIAL_COEF,
    maxProb: Double = MAX_UNIQUE_TRIAL_PROB,
): Double {
    // func.php:1669 '설문조사' 분기.
    var prob = 1.0 / (genCount * itemTypeCnt * 0.7 / 3)
    prob *= coef
    // func.php:1675 Util::valueFit(prob, null, maxUniqueTrialProb) — 상한 clamp (min 은 null).
    if (prob > maxProb) prob = maxProb
    // func.php:1678 prob /= sqrt(7).
    prob /= sqrt(7.0)
    return prob
}

/**
 * func.php:1546-1564 의 가중 풀 파생 (점유/경매/상속 차감을 무시한 baseline — golden 의 npc=0 scenario_1010
 * occupied-empty 상태를 그대로 반영) — GameConst::$allItems 를 삽입-순서로 훑어 weight==0 항목을 건너뛰고
 * [[itemType,itemCode], weight] 를 쌓는다.
 *
 * @param allItems itemType -> (itemCode -> weight) 의 삽입-순서 보존 맵 (GameConst::$allItems mirror).
 */
fun deriveItemPool(allItems: Map<String, Map<String, Int>>): List<UniqueItemEntry> {
    val pool = ArrayList<UniqueItemEntry>()
    for ((itemType, categories) in allItems) {
        for ((itemCode, cnt) in categories) {
            if (cnt == 0) continue // func.php:1551 if ($cnt == 0) continue;
            pool.add(UniqueItemEntry(itemType, itemCode, cnt))
        }
    }
    return pool
}
