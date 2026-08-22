package opensamguk.logic.actions.intake

import opensamguk.common.constants.GameConst

/**
 * Pure-logic ports of the 유산(inheritance) 포인트 구매 actions —
 * `sammo/API/InheritAction/{BuyHiddenBuff,BuyRandomUnique}.php`.
 *
 * 형제 [InheritResets]와 같은 계약을 따른다 — 각 resolver는 PHP `launch`가 읽는 env 입력
 * (previousPoint·현재 aux·isunited)을 받아 [InheritResetOutcome]를 돌려주고, 엔진 핸들러
 * ([opensamguk] InheritResetHandler)가 그 outcome을 적용한다(general aux mutate +
 * ChangeRecorder inheritance KV `previous` + rank `inherit_point_spent_dynamic` + inheritance_log).
 * RNG는 둘 다 **뽑지 않는다**(결정적). 로그 문자열 동결 회귀가 골든 타깃.
 *
 * 비결정성 주의(BuyRandomUnique): PHP는 `aux.inheritRandomUnique = TimeUtil::now()`로 **타임스탬프**를
 * 적재하지만, 그 값은 다음 턴 가드(`!== null`)의 non-null 마커로만 쓰이고 **로그·포인트 차감에는 들어가지
 * 않는다**(로그는 "{req} 포인트로 랜덤 유니크 구입"로 타임스탬프 미포함). 따라서 로그/차감은 결정적이고,
 * 마커 값만 핸들러가 데몬 시각으로 주입한다(표시·가드 전용, 패리티 무관 — 문서화).
 */
object InheritBuys {

    /** PHP `TriggerInheritBuff::MAX_STEP`. */
    const val MAX_STEP = 5

    /**
     * PHP `TriggerInheritBuff::BUFF_KEY_TEXT` (verbatim). **키는 const VALUE**(warAvoidRatio·
     * domesticSuccessProb·…)이며 `aux.inheritBuff`에 적재되는 `type`과 동일하다(BUFF_KEY_MAP의
     * 'success'/'fail' 매핑 VALUE가 아니다 — 그건 버프 적용 단계에서만 쓰임). 로그 문자열은 이 텍스트를
     * 쓰므로 동결 회귀 타깃. 삽입 순서는 PHP 배열 순서 보존(LinkedHashMap).
     */
    val BUFF_KEY_TEXT: Map<String, String> = linkedMapOf(
        "warAvoidRatio" to "회피 확률 증가",
        "warCriticalRatio" to "필살 확률 증가",
        "warMagicTrialProb" to "전투계략 시도 확률 증가",
        "domesticSuccessProb" to "내정 성공률 증가",
        "domesticFailProb" to "내정 실패율 감소",
        "warAvoidRatioOppose" to "상대 회피 확률 감소",
        "warCriticalRatioOppose" to "상대 필살 확률 감소",
        "warMagicTrialProbOppose" to "상대 전투계략 시도 확률 감소",
    )

    /**
     * BuyHiddenBuff.php. 히든 버프 레벨 구매(1~MAX_STEP). 비용은 **누적 차분**
     * `inheritBuffPoints[level] - inheritBuffPoints[prevLevel]`(PHP `BuyHiddenBuff.php:68`).
     * [GameConst.inheritBuffPoints] = `[0,200,600,1200,2000,3000]`은 level로 직접 인덱싱.
     * 뽑지 않음. 로그 = `"{req} 포인트로 {buffText} {level} 단계 {moreText}구입"`(moreText = prevLevel>0?"추가":"").
     *
     * @param type 버프 키(FE `buffKey`). PHP `type`. [BUFF_KEY_TEXT] 키여야 함.
     * @param level 요청 레벨(1..MAX_STEP).
     * @param currentBuffMap 현재 `aux.inheritBuff`(PHP `?? []`). prevLevel = `currentBuffMap[type] ?? 0`을
     *   **서버에서** 산출(클라가 보낸 prevLevel 무시 — 패리티+보안).
     * @param previousPoint inheritance `previous[0]` 잔액.
     * @param isUnited game_env isunited(천하통일 ⇒ deny).
     */
    fun buyHiddenBuff(
        type: String,
        level: Int,
        currentBuffMap: Map<String, Int>,
        previousPoint: Double,
        isUnited: Boolean,
    ): InheritResetOutcome {
        // validateArgs(PHP): type keyExists in BUFF_KEY_TEXT, level int 1..MAX_STEP.
        // FE는 항상 유효한 type/level만 전송(제약 UI) → 아래 두 deny는 FE-도달불가(validateArgs 경로).
        // 정확한 PHP Validator errorStr 바이트는 미검증(문서화); launch-레벨 deny 4종 + happy-path는 정확.
        if (type !in BUFF_KEY_TEXT) return InheritResetOutcome.Denied("'type' 항목이 올바르지 않습니다.")
        if (level < 1 || level > MAX_STEP) return InheritResetOutcome.Denied("'level' 항목이 올바르지 않습니다.")

        val prevLevel = currentBuffMap[type] ?: 0
        if (prevLevel == level) return InheritResetOutcome.Denied("이미 구입했습니다.")
        if (prevLevel > level) return InheritResetOutcome.Denied("이미 더 높은 등급을 구입했습니다.")

        val reqAmount = GameConst.inheritBuffPoints[level] - GameConst.inheritBuffPoints[prevLevel]
        if (isUnited) return InheritResetOutcome.Denied("이미 천하가 통일되었습니다.")
        if (previousPoint < reqAmount) return InheritResetOutcome.Denied("충분한 유산 포인트를 가지고 있지 않습니다.")

        val buffText = BUFF_KEY_TEXT.getValue(type)
        val moreText = if (prevLevel > 0) "추가" else ""
        val log = "$reqAmount 포인트로 $buffText $level 단계 ${moreText}구입"

        // aux.inheritBuff[type] = level — 기존 맵 위에 갱신(삽입 순서 보존).
        val nextBuffMap = LinkedHashMap(currentBuffMap)
        nextBuffMap[type] = level

        return InheritResetOutcome.Applied(
            spent = reqAmount,
            remainingPrevious = previousPoint - reqAmount,
            auxUpdates = linkedMapOf("inheritBuff" to nextBuffMap),
            varUpdates = linkedMapOf(),
            log = log,
        )
    }

    /**
     * BuyRandomUnique.php. 유니크 아이템 확정 드롭 플래그 구매. 비용 [GameConst.inheritItemRandomPoint](3000).
     * 뽑지 않음. 로그 = `"{req} 포인트로 랜덤 유니크 구입"`. aux `inheritRandomUnique` 마커 적재(가드 전용).
     *
     * @param alreadyOrdered `aux.inheritRandomUnique !== null`(이미 이번/다음 턴 구입 예약 ⇒ deny).
     * @param previousPoint inheritance `previous[0]` 잔액.
     * @param isUnited game_env isunited.
     * @param nowMarker aux 플래그에 적재할 non-null 마커(데몬 시각). 값 자체는 패리티 무관(가드/표시 전용).
     */
    fun buyRandomUnique(
        alreadyOrdered: Boolean,
        previousPoint: Double,
        isUnited: Boolean,
        nowMarker: Any,
    ): InheritResetOutcome {
        if (alreadyOrdered) return InheritResetOutcome.Denied("이미 구입 명령을 내렸습니다. 다음 턴까지 기다려주세요.")
        val reqAmount = GameConst.inheritItemRandomPoint
        if (isUnited) return InheritResetOutcome.Denied("이미 천하가 통일되었습니다.")
        if (previousPoint < reqAmount) return InheritResetOutcome.Denied("충분한 유산 포인트를 가지고 있지 않습니다.")

        return InheritResetOutcome.Applied(
            spent = reqAmount,
            remainingPrevious = previousPoint - reqAmount,
            auxUpdates = linkedMapOf("inheritRandomUnique" to nowMarker),
            varUpdates = linkedMapOf(),
            log = "$reqAmount 포인트로 랜덤 유니크 구입",
        )
    }
}
