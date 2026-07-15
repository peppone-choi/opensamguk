package opensamguk.logic.world

import opensamguk.common.josa.JosaUtil

/**
 * Q14 `checkEmperior` — 천하통일(전토통일) 탐지. PHP `func_gamerule.php:696-939`(:430 호출, POST3
 * refreshNationStaticInfo 뒤 / triggerTournament 앞) 충실 포팅.
 *
 * 매월 `postUpdateMonthly` 꼬리 Q14 슬롯에서 호출되며 **RNG draw가 전혀 없다**(no-rng — 월 draw
 * 스트림 불변, `PostUpdateMonthlyTailTest` 의 SC-3 단언과 일치). `level>0` 국가가 정확히 1국이고 그
 * 국가가 전 도시를 소유하면 PHP :725-939 tail을 statement order로 실행한다.
 *
 * 판정/효과 순서(PHP 그대로):
 *  1. `isunited != 0` → no-op. (:702-704)
 *  2. `SELECT nation FROM nation WHERE level>0 LIMIT 2` 가 정확히 1국이 아니면 no-op. (:706-710)
 *  3. 그 국가의 도시 수가 0이거나 `count(CityConst::all())`(전체 도시 수)와 다르면 no-op. (:716-723)
 *  4. `checkStatistic()` 최종기록. (:725)
 *  5. 전토통일 국가사 로그 push: `<D><b>{name}</b></>{조사이} 전토를 통일`. (:727-733)
 *  6. 유니크 경매 종료(:735-743)·상속 unifier +2000(:745-753)·United 이벤트(:755)·
 *     상속 merge/apply(:757-760).
 *  7. `isunited = 2`, `refreshLimit *= 100`, CheckHall. (:762-767)
 *  8. 승리 archive/emperior/yearbook/messages tail. (:769-939)
 *
 * 로그 포맷: 본 함수는 PHP `pushNationalHistoryLog`에 넘기는 inner-text만 만든다. 엔진 월드 컨텍스트는
 * PHP 기본 formatType=YEAR_MONTH처럼 `<C>●</>{year}년 {month}월:` 접두를 적용한다
 * (`ActionLogger.php:183,195,241-243`).
 *
 * `InvaderEndingAction` 과 동일하게 tick/daemon 이 풍부한 [CheckEmperiorContext] 를 공급하며, 컨텍스트
 * 없이도 byte-match 핵심 로직은 본 함수의 단위 테스트로 검증한다.
 */
fun checkEmperior(ctx: CheckEmperiorContext) {
    // (1) isunited != 0 → no-op. (:702-704)
    if (ctx.isunited() != 0) return

    // (2) level>0 국가가 정확히 1국이 아니면 no-op. (:706-710)
    val activeNations = ctx.activeNationIds()
    if (activeNations.size != 1) return
    val nationId = activeNations[0]

    // (3) 전 도시 소유 체크 — 도시 수 0 또는 전체와 불일치면 no-op. (:716-723)
    val cityCnt = ctx.cityCountOf(nationId)
    if (cityCnt == 0) return
    if (cityCnt != ctx.totalCityCount()) return

    // (4) checkStatistic() 최종기록. (:725)
    ctx.checkStatistic()

    val nationName = ctx.nationName(nationId) ?: return
    val josaYi = JosaUtil.pick(nationName, "이")

    ctx.pushNationalHistoryLog(nationId, "<D><b>$nationName</b></>${josaYi} 전토를 통일")

    ctx.closeActiveUniqueAuctions()
    ctx.grantUnifierInheritancePoint(nationId, 2000)
    ctx.runUnitedEvent()
    ctx.mergeAndApplyInheritance()

    ctx.setIsunited(2)
    ctx.multiplyRefreshLimit(100)
    ctx.checkHallForEligibleUserGenerals()

    ctx.persistUnificationArchive(nationId, josaYi)
    ctx.pushPreformattedGlobalHistoryLog(
        "<C>●</>${ctx.year()}년 ${ctx.month()}월:<Y><b>【통일】</b></><D><b>$nationName</b></>${josaYi} 전토를 통일하였습니다. " +
            "<span class='hidden_but_copyable'>(서버시드: ${ctx.hiddenSeed()})</span>",
    )
    ctx.logHistory()
    ctx.raiseInvaderMessages()
}

/**
 * `checkEmperior` 가 읽고 쓰는 월드 시임(tick/daemon 공급). `InvaderEndingContext` 와 동일 패턴.
 *
 *  - [isunited] — game_env `isunited`(0=평시, 1=침략자 이벤트, 2=천하통일, 3=엔딩).
 *  - [activeNationIds] — `SELECT nation FROM nation WHERE level>0`(PHP 는 LIMIT 2 로 count!=1 만 판정).
 *  - [cityCountOf] — `SELECT count(city) FROM city WHERE nation=%i`.
 *  - [totalCityCount] — `count(CityConst::all())`(시나리오 전체 도시 수).
 *  - [nationName] — `SELECT name FROM nation WHERE nation=%i`(없으면 null).
 *  - [pushNationalHistoryLog] — `ActionLogger(0, nationID, year, month)->pushNationalHistoryLog`.
 *  - [setIsunited] — `$gameStor->isunited = value`.
 */
interface CheckEmperiorContext {
    fun year(): Int
    fun month(): Int
    fun hiddenSeed(): String
    fun isunited(): Int
    fun activeNationIds(): List<Int>
    fun cityCountOf(nationId: Int): Int
    fun totalCityCount(): Int
    fun nationName(nationId: Int): String?
    fun checkStatistic()
    fun pushNationalHistoryLog(nationId: Int, msg: String)
    fun pushPreformattedGlobalHistoryLog(msg: String)
    fun closeActiveUniqueAuctions()
    fun grantUnifierInheritancePoint(nationId: Int, points: Int)
    fun runUnitedEvent()
    fun mergeAndApplyInheritance()
    fun setIsunited(value: Int)
    fun multiplyRefreshLimit(factor: Int)
    fun checkHallForEligibleUserGenerals()
    fun persistUnificationArchive(nationId: Int, josaYi: String)
    fun logHistory()
    fun raiseInvaderMessages()
}
