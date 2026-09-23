package opensamguk.logic.input

/** 시야원 종류(§7 「시야: 정찰 배치·망루·봉화」). 망루봉화는 §8.2 가 한 건물로 적은 그대로 한 종류다. */
enum class HwihaVisionKind { SCOUT, WATCHTOWER_BEACON }

/**
 * 시야원 하나. [provinceId] 는 육상 省 id, [countyId] 는 망루봉화가 선 縣治 城(정찰은 null),
 * [nationId] 는 시야를 누리는 세력(정찰은 카드 장수의 소속, 망루봉화는 지금 縣 소유자), [ownerGeneralId] 는 정찰 카드의 주인.
 */
data class HwihaVisionSource(
    val kind: HwihaVisionKind,
    val provinceId: String,
    val nationId: Int,
    val countyId: Int?,
    val ownerGeneralId: Int?,
    val sourceGeneralId: Int?,
    val since: HwihaPhase,
)

data class HwihaVisionGeneral(val id: Int, val nationId: Int, val meta: Map<String, Any?>)
data class HwihaVisionCounty(val id: Int, val nationId: Int, val provinceId: String?, val meta: Map<String, Any?>)

/**
 * 시야 소비자(비전 스트림)가 읽는 순수 reader. 저장 키:
 * - 정찰: 카드 장수 meta `hwihaPlacement.active` 의 `order.post == "SCOUT"`, `order.target.provinceId`, `arrivedAt != null`(부임한 뒤만)
 * - 망루봉화: 縣治 城 meta `hwihaCountyWorks.completed[*].work == "WATCHTOWER_BEACON"`, 그 城의 省
 * 오염된 저장값은 시야가 없는 것으로 바꾸지 않고 [Result.unreadableGeneralIds]·[Result.unreadableCountyIds] 로 돌려준다.
 * 출력은 (kind, provinceId, countyId, sourceGeneralId) 순으로 정렬된다.
 */
object HwihaVisionSources {
    data class Result(val sources: List<HwihaVisionSource>, val unreadableGeneralIds: List<Int>, val unreadableCountyIds: List<Int>)

    fun read(generals: List<HwihaVisionGeneral>, counties: List<HwihaVisionCounty>): Result {
        val sources = mutableListOf<HwihaVisionSource>()
        val badGenerals = mutableListOf<Int>()
        val badCounties = mutableListOf<Int>()
        for (general in generals.sortedBy { it.id }) {
            val active = try { HwihaPlacementState.read(general.meta)?.active } catch (_: IllegalArgumentException) {
                badGenerals += general.id; continue
            } ?: continue
            val target = active.order.target as? PlacementTarget.Province ?: continue
            val arrived = active.arrivedAt ?: continue
            if (active.order.post != PlacementPost.SCOUT) continue
            sources += HwihaVisionSource(HwihaVisionKind.SCOUT, target.provinceId, general.nationId, null,
                active.order.ownerGeneralId, general.id, arrived)
        }
        for (county in counties.sortedBy { it.id }) {
            val works = try { HwihaCountyWorks.read(county.meta) } catch (_: IllegalArgumentException) {
                badCounties += county.id; continue
            } ?: continue
            val tower = works.completed.firstOrNull { it.work == DomesticWork.WATCHTOWER_BEACON } ?: continue
            val province = county.provinceId ?: run { badCounties += county.id; null } ?: continue
            sources += HwihaVisionSource(HwihaVisionKind.WATCHTOWER_BEACON, province, county.nationId, county.id, null, null,
                tower.completedAt)
        }
        return Result(sources.sortedWith(compareBy({ it.kind.ordinal }, { it.provinceId }, { it.countyId ?: 0 }, { it.sourceGeneralId ?: 0 })),
            badGenerals, badCounties)
    }
}
