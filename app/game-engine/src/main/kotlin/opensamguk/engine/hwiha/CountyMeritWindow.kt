package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.input.RuleProfile
import opensamguk.logic.renown.DomesticMerit
import opensamguk.logic.renown.RenownEventSource
import opensamguk.logic.renown.RenownEvents
import opensamguk.logic.renown.RenownHooks

/**
 * 치적(관할 縣 지표 상승)의 한 달 창 — 2026-09-23 사용자 결정.
 *
 * - [open]: 월 경계의 월간 사건이 **끝난 뒤**, 관할 장수가 있는 縣 의 호구·전답·시장을 적어 둔다.
 * - [close]: 다음 월 경계의 월간 사건 **전**에, 적어 둔 값과 지금 값을 비교해 [DomesticMerit.risen] 이면
 *   그 縣 의 관할 장수([RenownHooks.countyHolderIds], 지금 소유 세력 기준)에게 치적을 쌓는다.
 *
 * 사이에 끼는 경계 사건(반기 도시 성장 등)은 창 밖이라 자연 증가가 치적이 되지 않는다. 치적은 창을 연 달의
 * 도장으로 쌓이므로 같은 경계의 월단평이 바로 적용한다.
 *
 * 창은 `game_env` [KEY] 한 줄이다: `{"stamp":"YYYY-MM","counties":{"<id>":[호구,전답,시장]}}`. 닫으면 지우고
 * 열면 새로 쓴다. 같은 달에 두 번 열거나 닫지 않는다(도장 비교).
 */
class CountyMeritWindow(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    /**
     * [year]-[month] 를 여는 월 경계에서, 그 전 달 창을 닫는다.
     * @return 치적이 새로 쌓인 장수 id(오름차순). 창이 없거나 이미 닫혔으면 빈 목록.
     */
    fun close(year: Int, month: Int): List<Int> {
        if (world.ruleProfile != RuleProfile.HWIHA) return emptyList()
        val window = read() ?: return emptyList()
        if (RenownEvents.monthOrdinal(window.stamp) >= RenownEvents.monthOrdinal(RenownEvents.stampOf(year, month)))
            return emptyList()
        val metaById = world.listGenerals().associate { it.id to it.meta }
        val merited = sortedSetOf<Int>()
        for ((countyId, open) in window.counties.toSortedMap()) {
            val city = world.getCityById(countyId) ?: continue
            if (city.nationId == 0) continue
            val close = DomesticMerit.Indicators(city.population, city.agriculture, city.commerce)
            val max = DomesticMerit.Indicators(city.populationMax, city.agricultureMax, city.commerceMax)
            if (!DomesticMerit.risen(open, close, max)) continue
            merited += RenownHooks.countyHolderIds(countyId, city.nationId, metaById)
        }
        val events = RenownEventRecorder(world, recorder)
        val recorded = merited.filter { events.record(it, RenownEventSource.COUNTY_INDICATOR_RISE, window.stamp) }
        world.setGameEnvValue(KEY, null)
        recorder.recordKv("game_env", "game_env", KEY, null)
        return recorded
    }

    /** [year]-[month] 창을 연다 — 지금 관할 장수가 있는 縣 만 적는다. @return 적은 縣 수. */
    fun open(year: Int, month: Int): Int {
        if (world.ruleProfile != RuleProfile.HWIHA) return 0
        val stamp = RenownEvents.stampOf(year, month)
        if (read()?.stamp == stamp) return 0
        val metaById = world.listGenerals().associate { it.id to it.meta }
        val counties = linkedMapOf<String, Any?>()
        for (countyId in world.administrativeCountyIds.sorted()) {
            val city = world.getCityById(countyId) ?: continue
            if (city.nationId == 0 || RenownHooks.countyHolderIds(countyId, city.nationId, metaById).isEmpty()) continue
            counties[countyId.toString()] = listOf(city.population, city.agriculture, city.commerce)
        }
        val value = linkedMapOf<String, Any?>("stamp" to stamp, "counties" to counties)
        world.setGameEnvValue(KEY, value)
        recorder.recordKv("game_env", "game_env", KEY, value)
        return counties.size
    }

    private data class Window(val stamp: String, val counties: Map<Int, DomesticMerit.Indicators>)

    /** 읽을 수 없으면 창이 없는 것으로 본다 — 월 경계에서 던지면 턴 루프가 영구히 멈춘다. */
    private fun read(): Window? {
        val raw = world.getState().meta[KEY] as? Map<*, *> ?: return null
        val stamp = (raw["stamp"] as? String)?.takeIf {
            runCatching { RenownEvents.monthOrdinal(it) }.isSuccess
        } ?: return null
        val counties = (raw["counties"] as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
            val id = key?.toString()?.toIntOrNull() ?: return@mapNotNull null
            val values = (value as? List<*>)?.map { (it as? Number)?.toInt() } ?: return@mapNotNull null
            if (values.size != 3 || values.any { it == null }) return@mapNotNull null
            id to DomesticMerit.Indicators(values[0]!!, values[1]!!, values[2]!!)
        }?.toMap() ?: return null
        return Window(stamp, counties)
    }

    companion object {
        const val KEY = "hwihaCountyMeritWindow"
    }
}
