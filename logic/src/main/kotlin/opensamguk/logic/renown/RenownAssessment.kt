package opensamguk.logic.renown

/**
 * 월단평 — 매월 상순 명망을 갱신하고 순위를 매기며, 코스트 상한을 넘은 휘하의 이탈을 판정한다.
 *
 * 정본 설계 §2.8(명망·월단평), §5.2(순 경계 4번: … 재해 → 월단평 → 코스트 상한을 넘은 장수의 휘하
 * 이탈 판정), §6.6(휘하 규칙). 갱신 방식은 **사건 누적식**이다(2026-09-22 사용자 결정): 지난 달에
 * 일어난 사건을 세어 두고 월단평에서 한 번 적용한다. 사건 집계는 [RenownEvents](한 달에 종류당 한 번). 가감값·상하한은
 * `data/curated/han/hwiha-renown-assessment-v1.json` 에 있고 이 객체는 그 값을 받아 쓴다 — 수치를
 * 코드에 박지 않는다.
 *
 * 명망은 **초기화하지 않는다**(§2.8) — 월단평은 기존 값을 보존하며 갱신한다.
 */
object RenownAssessment {
    /** 마지막 월단평 도장(`YYYY-MM`) — `game_env` 키. 엔진이 쓰고 game-api 조회가 읽는다. */
    const val STAMP_KEY = "hwihaRenownAssessmentStamp"

    /** 마지막 월단평 순위(장수 id 목록, 명망 내림차순) — `game_env` 키. */
    const val RANKING_KEY = "hwihaRenownRanking"

    /**
     * 마지막 월단평이 적용한 사유 — `game_env` 키. `{"stamp":"YYYY-MM","byGeneral":{"<id>":[{"kind","count","amount"}]}}`.
     * 종류·건수·증감만 싣는다(§2.8 발표에 실리는 공개 정보). 사건 원인은 싣지 않는다.
     */
    const val REASONS_KEY = "hwihaRenownReasons"

    /** 한 장수의 지난 달 사건 집계. 어떤 사건이 어디에 해당하는지는 호출부가 정한다. */
    data class Tally(
        val warMerit: Int = 0,
        val domesticMerit: Int = 0,
        val office: Int = 0,
        val bondEvent: Int = 0,
        val defeat: Int = 0,
        val betrayal: Int = 0,
        val misrule: Int = 0,
        val dispatchRefusal: Int = 0,
    ) {
        init {
            require(
                listOf(warMerit, domesticMerit, office, bondEvent, defeat, betrayal, misrule, dispatchRefusal)
                    .all { it >= 0 },
            ) { "event counts must be nonnegative" }
        }

        val isEmpty: Boolean get() = warMerit == 0 && domesticMerit == 0 && office == 0 && bondEvent == 0 &&
            defeat == 0 && betrayal == 0 && misrule == 0 && dispatchRefusal == 0
    }

    /**
     * 사건별 가감값과 상하한. 정본 데이터 파일에서 읽어 넘긴다.
     *
     * 오르는 경로는 양수, 떨어지는 경로는 음수로 담는다 — 부호를 여기서 한 번만 정해 두면 호출부가
     * 「깎는 값」의 부호를 헷갈릴 자리가 없다.
     */
    data class Curve(
        val warMerit: Int,
        val domesticMerit: Int,
        val office: Int,
        val bondEvent: Int,
        val defeat: Int,
        val betrayal: Int,
        val misrule: Int,
        val dispatchRefusal: Int,
        val floor: Int,
        val ceiling: Int,
    ) {
        init {
            require(floor >= 1) { "floor must be at least 1 so a general can still hold one person, was $floor" }
            require(ceiling >= floor) { "ceiling $ceiling must not be below floor $floor" }
            require(listOf(warMerit, domesticMerit, office, bondEvent).all { it >= 0 }) {
                "rising events must be nonnegative"
            }
            require(listOf(defeat, betrayal, misrule, dispatchRefusal).all { it <= 0 }) {
                "falling events must be nonpositive"
            }
        }
    }

    /**
     * 정본 곡선 — `data/curated/han/hwiha-renown-assessment-v1.json` 을 그대로 옮긴 값이다.
     *
     * Kotlin 런타임이 `data/curated` 를 읽지 않는 것이 이 저장소의 방식이므로(포위 사기도 같다)
     * 값을 여기 둔다. 두 곳이 갈라지지 않는지는 `RenownAssessmentTest` 가 파일을 읽어 대조한다.
     */
    val CANON: Curve = Curve(
        warMerit = 3, domesticMerit = 2, office = 4, bondEvent = 2,
        defeat = -3, betrayal = -8, misrule = -2, dispatchRefusal = -4,
        floor = 10, ceiling = 200,
    )

    /** 휘하 한 장. 이탈 판정은 충성이 낮은 쪽부터 본다. */
    data class RetainerCard(val retainerId: Int, val cost: Int, val loyalty: Int)

    /**
     * 한 장수의 월단평 결과.
     *
     * @property renown 갱신된 명망. 상하한으로 자른 값이다.
     * @property delta 실제로 적용된 증감(자른 뒤 기준) — 발표·로그에 쓴다.
     * @property released 코스트 상한을 넘겨 이탈 판정을 받은 휘하. 충성 낮은 쪽부터다.
     * @property retainedCost 이탈 뒤 남은 휘하 코스트 합.
     */
    data class Outcome(
        val generalId: Int,
        val renown: Int,
        val delta: Int,
        val released: List<Int>,
        val retainedCost: Int,
    )

    /**
     * 명망만 갱신한다(순위·이탈 없이).
     *
     * @return 상하한으로 자른 새 명망.
     */
    fun updatedRenown(renown: Int, tally: Tally, curve: Curve): Int {
        val raw = renown.toLong() +
            tally.warMerit.toLong() * curve.warMerit +
            tally.domesticMerit.toLong() * curve.domesticMerit +
            tally.office.toLong() * curve.office +
            tally.bondEvent.toLong() * curve.bondEvent +
            tally.defeat.toLong() * curve.defeat +
            tally.betrayal.toLong() * curve.betrayal +
            tally.misrule.toLong() * curve.misrule +
            tally.dispatchRefusal.toLong() * curve.dispatchRefusal
        return raw.coerceIn(curve.floor.toLong(), curve.ceiling.toLong()).toInt()
    }

    /**
     * 한 장수를 월단평한다 — 명망을 갱신하고, 갱신된 명망을 넘는 휘하를 충성 낮은 쪽부터 이탈시킨다.
     *
     * 동점 충성은 `retainerId` 가 큰 쪽을 먼저 놓는다. 순서를 정해 두지 않으면 같은 입력이 실행마다
     * 다른 사람을 내보내 리플레이가 갈라진다.
     */
    fun assess(
        generalId: Int,
        renown: Int,
        tally: Tally,
        curve: Curve,
        retinue: List<RetainerCard>,
    ): Outcome {
        require(retinue.all { it.cost >= 0 }) { "retainer cost must be nonnegative" }
        require(retinue.map { it.retainerId }.toSet().size == retinue.size) { "duplicate retainerId in retinue" }
        val next = updatedRenown(renown, tally, curve)
        val released = departures(next, retinue)
        val releasedIds = released.toSet()
        val cost = retinue.filter { it.retainerId !in releasedIds }.sumOf { it.cost.toLong() }
        return Outcome(
            generalId = generalId,
            renown = next,
            delta = next - renown,
            released = released,
            retainedCost = cost.toInt(),
        )
    }

    /**
     * 명망 [capacity] 를 넘는 휘하가 이탈 판정을 받는 순서 — 충성 오름차순, 동점은 `retainerId` 내림차순.
     * 코스트 합이 [capacity] 이하가 되면 멈춘다. 넘지 않으면 빈 목록이다.
     *
     * [assess] 와 조회 화면(휘하 카드의 「이탈 순번」)이 같은 순서를 쓰도록 여기 한 곳에 둔다.
     */
    fun departures(capacity: Int, retinue: List<RetainerCard>): List<Int> {
        require(retinue.all { it.cost >= 0 }) { "retainer cost must be nonnegative" }
        require(retinue.map { it.retainerId }.toSet().size == retinue.size) { "duplicate retainerId in retinue" }
        // 충성 오름차순, 동점은 id 내림차순 — 결정적 순서.
        val shedOrder = retinue.sortedWith(compareBy({ it.loyalty }, { -it.retainerId }))
        var cost = retinue.sumOf { it.cost.toLong() }
        val released = ArrayList<Int>()
        for (card in shedOrder) {
            if (cost <= capacity) break
            released += card.retainerId
            cost -= card.cost
        }
        return released
    }

    /**
     * 순위를 매긴다 — 명망 내림차순, 동점은 `generalId` 오름차순.
     *
     * 발표용이므로 순서가 결정적이어야 한다. 동점을 id 로 가르지 않으면 같은 세계가 순마다 다른
     * 순위를 발표한다.
     */
    fun ranking(renownByGeneral: Map<Int, Int>): List<Int> =
        renownByGeneral.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
            .map { it.key }
}
