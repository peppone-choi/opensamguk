package opensamguk.logic.actions.personnel

import opensamguk.common.constants.GameConst
import opensamguk.logic.stats.GeneralActionPipeline

/**
 * che_내정특기초기화 — faithful port of `che_내정특기초기화.php`.
 *
 * PHP는 23줄 박형 위임 클래스다 (`class che_내정특기초기화 extends che_전투특기초기화`): 부모
 * `che_전투특기초기화`의 run()/제약/비용/턴 로직을 그대로 상속하고, 대상 특기 "종류"를 가리키는 정적
 * 프로퍼티 4개만 전투(special2/specage2/availableSpecialWar)에서 내정(special/specage/
 * availableSpecialDomestic)으로 교체한다. 이 Kotlin 포트도 동일하게 형제 [CheJeontuTeukgiChogihwa]를
 * 상속하고 오버라이드 지점만 내정으로 바꾼다 (로직 100% 공유 — append-additive 이력, 한 바퀴-리셋,
 * special='None', specage=age+1, MONTH 로그, setResultTurn, 말미 unique-로터리 seam 모두 부모와 동일).
 *
 * PHP 정적 프로퍼티 매핑 (che_내정특기초기화.php:18-23):
 *   - actionName  = '내정 특기 초기화'   → [name] / [lotteryActionName]
 *   - specialType = 'special'           → [specialType]  (meta 키)
 *   - speicalAge  = 'specage'            → [specialAge]   (PHP 오타 그대로)
 *   - specialText = '내정 특기'          → [specialText]  (로그 임베드 문자열)
 *
 * 적성 종류 수는 부모(availableSpecialWar=20) 대신 내정 특기 수(availableSpecialDomestic=8)로 교체
 * (che_전투특기초기화.php:91 — specialType=='special' 분기). run()은 전달된 rng를 한 번도 소모하지 않는다
 * (DRAW=0); 말미 tryUniqueItemLottery는 별도 unique RNG seam(부모 주석 참조).
 */
class CheNaejeongTeukgiChogihwa(
    pipeline: GeneralActionPipeline,
) : CheJeontuTeukgiChogihwa(pipeline) {

    override val key: String get() = "che_내정특기초기화"
    override val name: String get() = "내정 특기 초기화"

    // 부모 che_전투특기초기화 와 함께 PHP availableGeneralCommand['개인'] 그룹 — 형제와 동일 카테고리.
    override val category: String get() = "인사"

    // 대상 var 키들 — PHP 정적 멤버 오버라이드 (특기 종류만 내정으로 교체).
    override val specialType: String get() = "special"      // PHP $specialType='special'
    override val specialAge: String get() = "specage"       // PHP $speicalAge='specage' (원문 오타 유지)
    override val specialText: String get() = "내정 특기"     // PHP $specialText='내정 특기'

    // 적성 종류 수 — 다 돌면 prev 리스트 리셋(che_전투특기초기화.php:91 'special' 분기 = availableSpecialDomestic).
    override val availableSpecialCount: Int get() = GameConst.availableSpecialDomestic.size
}
