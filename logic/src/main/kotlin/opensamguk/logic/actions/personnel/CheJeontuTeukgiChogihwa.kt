package opensamguk.logic.actions.personnel

import opensamguk.common.constants.GameConst
import opensamguk.logic.actions.GeneralActionDefinition
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.constraints.Constraint
import opensamguk.logic.constraints.ConstraintContext
import opensamguk.logic.constraints.ConstraintResult
import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.constraints.StateView
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_전투특기초기화 — faithful port of
 * `legacy/devsam-core/hwe/sammo/Command/General/che_전투특기초기화.php`.
 *
 * 전투특기(specialWar / `special2`) 슬롯을 초기화한다(=`None`). 5년마다 1회(getPreReqTurn=1,
 * getPostReqTurn=60), 비용 없음(getCost=[0,0] — 골든). InheritResets / InheritResetHandler
 * (유산포인트 reset / inherit-action)와는 **별개의 예약형 커맨드 서브시스템**이며, 내정특기 형제인
 * che_내정특기초기화(`special`)와 대상 특기 종류만 다른 위임형 짝이다.
 *
 * PHP 정적 멤버(class 상수):
 *   - actionName='전투 특기 초기화'  (unique-item-lottery seed 토큰)
 *   - specialType='special2'        (대상 var 키)
 *   - speicalAge='specage2'         (대상 적성연령 var 키 — PHP 오타 그대로)
 *   - specialText='전투 특기'        (로그/제약 닉네임)
 *
 * argTest (che_전투특기초기화.php:27-30): `arg = null` → 항상 true (무인자).
 *
 * fullConditionConstraints (che_전투특기초기화.php:37-39), PHP 순서(first-deny-wins):
 *   [ReqGeneralValue('special2', '전투 특기', '!=', 'None', '특기가 없습니다.')]
 * errMsg가 non-null('특기가 없습니다.')이므로 fail reason = '특기가 없습니다.'
 * (ReqGeneralValue.php:109-111 — errMsg가 있으면 비교 결과 닉네임 메시지 대신 errMsg를 그대로 사용).
 * minConditionConstraints도 동일(che_전투특기초기화.php:33-35).
 *
 * run() (che_전투특기초기화.php:73-111) — draw 0 (deterministic):
 *   1. oldSpecialList = getAuxVar('prev_types_special2') ?? []           (php:89)
 *   2. oldSpecialList[] = getVar('special2')                             (php:90)  — 현재 특기를 누적
 *   3. if specialType=='special2' && count == count(availableSpecialWar) (=20)     (php:94-96)
 *        → oldSpecialList = [getVar('special2')]                                    — 다 돌면 리셋
 *      (specialType=='special'(내정) 분기는 이 전투 커맨드에선 미적용 — php:91-93)
 *   4. setAuxVar('prev_types_special2', oldSpecialList)                  (php:97)
 *   5. setVar('special2', 'None')                                       (php:99)
 *   6. setVar('specage2', getVar('age') + 1)                            (php:100)
 *   7. pushGeneralActionLog("새로운 전투 특기를 가질 준비가 되었습니다. <1>$date</>")  (php:104)
 *   8. setResultTurn(LastTurn(name))                                    (php:106)
 *
 * (php:88 `$yearMonth = Util::joinYearMonth(...)` 는 run 본문에서 미사용 dead-assignment → 미복제.
 *  php:107 StaticEventHandler / php:108 tryUniqueItemLottery 는 0-draw 게이트 밖의 다운스트림 훅
 *  이므로 이 def에서는 미복제. lotteryActionName 토큰만 actionName='전투 특기 초기화'로 핀.)
 *
 * `special2`/`specage2`/`age`/`aux` 는 logic General 의 dedicated 필드가 아니라 `general.meta` 에
 * 실린다(엔진 어댑터 WorldActionContext/AiTurnAdapter 가 meta 키로 스테이징; AssignGeneralSpeciality·
 * InheritResetHandler 와 동일 키 규약). prev_types_special2 는 `meta['aux']` 의 중첩 키.
 */
open class CheJeontuTeukgiChogihwa(
    @Suppress("UNUSED_PARAMETER") private val pipeline: GeneralActionPipeline,
) : GeneralActionDefinition {
    override val key: String get() = "che_전투특기초기화"
    override val name: String get() = "전투 특기 초기화"
    override val category: String get() = "인사"

    /** PHP `static::$actionName` = '전투 특기 초기화' — unique-item-lottery seed 토큰(= name). */
    override val lotteryActionName: String get() = name

    /** 대상 var 키들 (PHP 정적 멤버). specialType/speicalAge(오타 그대로)/specialText. */
    protected open val specialType: String get() = "special2"
    protected open val specialAge: String get() = "specage2"
    protected open val specialText: String get() = "전투 특기"

    /** 적성 종류 수 — 다 돌면 prev 리스트 리셋(php:94). 전투=availableSpecialWar(20). */
    protected open val availableSpecialCount: Int get() = GameConst.availableSpecialWar.size

    /**
     * fullConditionConstraints (che_전투특기초기화.php:37-39): ReqGeneralValue(special2,'전투 특기','!=','None',errMsg).
     * minConditionConstraints 도 동일(php:33-35) — buildMinConstraints 기본값(=buildConstraints) 사용.
     */
    override fun buildConstraints(ctx: ConstraintContext): List<Constraint> = listOf(reqHasSpecial())

    /** che_전투특기초기화.php:27-30 argTest — 무인자. */
    override fun parseArgs(raw: Map<String, Any?>): Map<String, Any?> = emptyMap()

    /**
     * ReqGeneralValue(specialType, specialText, '!=', 'None', '특기가 없습니다.') 의 충실 포트
     * (ReqGeneralValue.php). general[specialType] != 'None' → Allow; 아니면 errMsg('특기가 없습니다.').
     * special2 는 meta['special2'] (없으면 GameConst.defaultSpecialWar='None')에서 읽는다.
     */
    private fun reqHasSpecial() = object : Constraint {
        override val name = "ReqGeneralValue"
        override fun requires(ctx: ConstraintContext) = listOf(RequirementKey.General(ctx.actorId))
        override fun test(ctx: ConstraintContext, view: StateView): ConstraintResult {
            val g = view.get(RequirementKey.General(ctx.actorId)) as? General
                ?: return ConstraintResult.Unknown(requires(ctx))
            val cur = (g.meta[specialType] as? String) ?: GameConst.defaultSpecialWar
            return if (cur != "None") ConstraintResult.Allow else ConstraintResult.Deny("특기가 없습니다.")
        }
    }

    override fun resolve(context: GeneralActionResolveContext) {
        val d = context.draft
        val g0 = d.general

        // 현재 aux + prev_types 리스트 (insertion-order LinkedHashMap 보존)
        @Suppress("UNCHECKED_CAST")
        val oldAux = (g0.meta["aux"] as? Map<String, Any?>) ?: emptyMap()
        val oldTypeKey = "prev_types_$specialType"           // = prev_types_special2
        @Suppress("UNCHECKED_CAST")
        val prevRaw = (oldAux[oldTypeKey] as? List<Any?>) ?: emptyList()

        // 현재 특기(special2) — 없으면 'None'
        val curSpecial = (g0.meta[specialType] as? String) ?: GameConst.defaultSpecialWar

        // oldSpecialList[] = curSpecial  (php:90)
        var newList: List<Any?> = prevRaw + curSpecial
        // 다 돌면 [curSpecial] 로 리셋 (php:94-96; 전투=availableSpecialWar 20)
        if (newList.size == availableSpecialCount) {
            newList = listOf(curSpecial)
        }

        // setAuxVar(prev_types_special2, newList) — aux 중첩맵 갱신(insertion order 보존, php:97)
        val newAux = LinkedHashMap(oldAux)
        newAux[oldTypeKey] = newList

        // age → specage2 = age + 1 (php:100)
        val age = (g0.meta["age"] as? Number)?.toInt() ?: 0

        val newMeta = LinkedHashMap(g0.meta)
        newMeta["aux"] = newAux
        newMeta[specialType] = "None"                        // setVar('special2','None') (php:99)
        newMeta[specialAge] = age + 1                        // setVar('specage2', age+1) (php:100)

        d.general = g0.copy(meta = newMeta, lastTurn = LastTurn(name))

        // pushGeneralActionLog (php:104) — byte-exact (specialText='전투 특기')
        context.addLog("새로운 ${specialText}를 가질 준비가 되었습니다. <1>${context.date}</>")
    }
}
