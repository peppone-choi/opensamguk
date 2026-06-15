package opensamguk.logic.world

import opensamguk.common.josa.JosaUtil

/**
 * Q14 `checkEmperior` — 천하통일(전토통일) 탐지. PHP `func_gamerule.php:696-769`(:430 호출, POST3
 * refreshNationStaticInfo 뒤 / triggerTournament 앞) 충실 포팅.
 *
 * 매월 `postUpdateMonthly` 꼬리 Q14 슬롯에서 호출되며 **RNG draw가 전혀 없다**(no-rng — 월 draw
 * 스트림 불변, `PostUpdateMonthlyTailTest` 의 SC-3 단언과 일치). `level>0` 국가가 정확히 1국이고 그
 * 국가가 전 도시를 소유하면 천하통일로 판정해 `isunited=2` 로 전이하고 전토통일 국가사 로그를 1줄
 * 남긴다.
 *
 * 판정/효과 순서(PHP 그대로):
 *  1. `isunited != 0` → no-op. (:702-704)
 *  2. `SELECT nation FROM nation WHERE level>0 LIMIT 2` 가 정확히 1국이 아니면 no-op. (:706-710)
 *  3. 그 국가의 도시 수가 0이거나 `count(CityConst::all())`(전체 도시 수)와 다르면 no-op. (:716-723)
 *  4. **[격리]** `checkStatistic()` 최종기록. (:725)
 *  5. 전토통일 국가사 로그 push: `<D><b>{name}</b></>{조사이} 전토를 통일`. (:727-733)
 *  6. **[격리]** 유니크 경매 종료(:735-743)·상속 unifier +2000(:745-753)·United 이벤트(:755)·
 *     상속 merge/apply(:757-760).
 *  7. `isunited = 2`. (:762)
 *  8. **[격리]** `refreshLimit *= 100`(:763)·CheckHall(:765-767).
 *
 * **격리(quarantine, 위조 금지 — PHP 라인 인용):** 4·6·8의 1회성 부수효과는 천하통일 "최종턴"에서만
 * 의미가 있으며 장기-시뮬 패러티 게이트(`docs/superpowers/plans/2026-06-15-long-sim-parity-gate-plan.md`)
 * Phase 4 가 천하통일까지 윈도를 확장할 때 PHP 라인 그대로 포팅한다. **isunited DB 영속**도 별도 갭이다:
 * `JdbcFlushExecutor` 의 world_state UPDATE 가 `isunited` 컬럼을 SET 하지 않고(turn-rewind/flush-3col 와
 * 동일 부류) `WorldSnapshotLoader` 도 컬럼→meta 적재가 없어, in-memory 전이가 재기동 시 유실된다. 둘 다
 * 별도 바퀴 + 실DB IT 로 닫는다(LEDGER 백로그). 본 바퀴는 detection + in-memory 전이 + 로그까지만 닫는다.
 *
 * **[격리] 로그 YEAR_MONTH 접두:** PHP `pushNationalHistoryLog` 기본 formatType=YEAR_MONTH 가 store
 * 시점에 `<C>●</>{year}년 {month}월:` 접두를 붙인다(`ActionLogger.php:183,195,241-243`). 본 함수가 emit
 * 하는 inner-text(`<D><b>{name}</b></>{조사} 전토를 통일`)에는 아직 접두가 없다 — 월 파이프라인 history
 * 로그 전반(개전/종전 Q6/Q7 `func_gamerule.php:360,384` 포함)이 공유하는 **선존재 엔진-광역 로그포맷
 * 갭**(`LogEntryDraft.format` 미소비)이며 별도 바퀴에서 일괄 적용한다(LEDGER 백로그).
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

    // (4) [격리] checkStatistic() 최종기록 — Phase 4 백로그. (:725)

    // (5) 전토통일 국가사 로그. (:727-733)
    val nationName = ctx.nationName(nationId) ?: return
    val josaYi = JosaUtil.pick(nationName, "이")
    ctx.pushNationalHistoryLog(nationId, "<D><b>$nationName</b></>$josaYi 전토를 통일")

    // (6) [격리] 경매 종료 / 상속 unifier+2000 / United 이벤트 / 상속 merge·apply — Phase 4 백로그. (:735-760)

    // (7) isunited = 2. (:762)
    ctx.setIsunited(2)

    // (8) [격리] refreshLimit *= 100 / CheckHall — Phase 4 백로그. (:763-767)
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
    fun isunited(): Int
    fun activeNationIds(): List<Int>
    fun cityCountOf(nationId: Int): Int
    fun totalCityCount(): Int
    fun nationName(nationId: Int): String?
    fun pushNationalHistoryLog(nationId: Int, msg: String)
    fun setIsunited(value: Int)
}
