package opensamguk.logic.actions.military

import opensamguk.common.josa.JosaUtil
import opensamguk.common.rng.RandUtil
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.util.valueFit

/**
 * SabotageInjury — faithful port of `legacy/devsam-core/hwe/func.php:2169-2197`.
 *
 * 계략(화계/파괴) 성공 시 dest 도시의 적 장수들에게 부상을 입힌다.
 * 각 장수마다:
 *   1. injuryProb = 0.3 → onCalcStat('injuryProb', injuryProb) pipeline fold
 *   2. rng.nextBool(injuryProb) → true면 부상 적용
 *   3. 해당 장수의 action-log에 "<M>{reason}</>로 인해 <R>부상</>을 당했습니다." 기록
 *   4. injury += rng.nextRangeInt(1, 16), clamped [0, 80]
 *   5. crew *= 0.98, atmos *= 0.98, train *= 0.98
 *   6. 변경된 장수를 context.draft.cascadeGenerals에 append (ChangeRecorder dirty 채널)
 *
 * @param rng RNG draw-for-draw 타겟 (che_화계/che_파괴가 빌드한 단일 RandUtil)
 * @param cityGeneralList 부상 대상 장수 리스트 (dest 도시의 적 장수들)
 * @param reason 부상 원인 문자열 (e.g. "화계", "파괴")
 * @param context GeneralActionResolveContext — dest 장수 dirty 기록용 (cascadeGenerals)
 * @param pipeline onCalcStat('injuryProb') fold용 (ChePagoe/CheHwahye 계산 시 이미 주입됨)
 * @return 실제 부상을 입은 장수 수
 */
fun sabotageInjury(
    rng: RandUtil,
    cityGeneralList: List<General>,
    reason: String,
    context: GeneralActionResolveContext,
    pipeline: GeneralActionPipeline,
): Int {
    var injuryCount = 0
    val josaRo = JosaUtil.pick(reason, "로")
    val text = "<M>$reason</>${josaRo} 인해 <R>부상</>을 당했습니다."

    for (general in cityGeneralList) {
        var injuryProb = 0.3
        injuryProb = pipeline.onCalcStat(general, "injuryProb", injuryProb)
        if (!rng.nextBool(injuryProb)) continue

        context.addLogTo(general.id, text)

        val newInjury = valueFit(
            general.injury + rng.nextRangeInt(1, 16).toDouble(),
            0.0,
            80.0,
        ).toInt()

        val mutated = general.copy(
            injury = newInjury,
            crew = (general.crew * 0.98).toInt(),
            atmos = general.atmos * 0.98,
            train = general.train * 0.98,
        )

        context.draft.cascadeGenerals.add(mutated)
        injuryCount += 1
    }

    return injuryCount
}
