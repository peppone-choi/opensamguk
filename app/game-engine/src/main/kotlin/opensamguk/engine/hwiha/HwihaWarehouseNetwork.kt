package opensamguk.engine.hwiha

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.logic.economy.HwihaCountyWarehouse
import opensamguk.logic.economy.HwihaResources

/**
 * 「카드가 있는 곳의 보급망」(재설계 spec §9.2) — 녹봉·상사가 금을 내는 창고 목록. 임시 규칙
 * (`hwiha-s3-provisional-v1.json` salary.note):
 *
 * - 카드 인물의 기준 城이 지불자 세력의 **보급된** 縣이면: 그 세력의 보급된 縣 창고 전체(수도 먼저, 그다음 id 순).
 * - 지불자 세력의 縣이지만 보급이 끊겼으면(고립): 그 縣 창고만.
 * - 그 밖(타국·무주 땅, 재야 지불자): 낼 창고가 없다.
 *
 * 전액을 낼 수 있을 때만 차례로 뺀다(부분 지급 없음). 차감은 [HwihaWarehouseSettlement] 로만 한다.
 */
class HwihaWarehouseNetwork(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    fun countiesFor(payerNationId: Int, locationCityId: Int): List<Int> {
        if (payerNationId <= 0) return emptyList()
        val location = world.getCityById(locationCityId)?.takeIf { it.nationId == payerNationId } ?: return emptyList()
        if (location.supplyState == 0) return listOf(location.id).filter(::hasWarehouse)
        val capital = world.getNationById(payerNationId)?.capitalCityId
        return world.listCities().filter { it.nationId == payerNationId && it.supplyState != 0 && hasWarehouse(it.id) }
            .sortedWith(compareBy({ it.id != capital }, { it.id })).map { it.id }
    }

    /** @return 전액을 뺐으면 true. 모자라면 아무것도 빼지 않고 false. */
    fun payMoney(payerNationId: Int, counties: List<Int>, amount: Long): Boolean {
        require(amount >= 0)
        if (amount == 0L) return true
        val stocks = counties.mapNotNull { id -> warehouse(id)?.let { id to it } }
        if (stocks.sumOf { it.second.stock.money } < amount) return false
        var remaining = amount
        for ((county, warehouse) in stocks) {
            if (remaining == 0L) break
            val take = minOf(remaining, warehouse.stock.money)
            if (take == 0L) continue
            val result = HwihaWarehouseSettlement(world, recorder).settle(county, payerNationId, warehouse.revision,
                HwihaResources(money = take))
            check(result == HwihaWarehouseSettlement.Result.APPLIED) { "Validated warehouse payment was rejected: $result" }
            remaining -= take
        }
        return true
    }

    private fun hasWarehouse(countyId: Int) = warehouse(countyId) != null

    private fun warehouse(countyId: Int): HwihaCountyWarehouse? {
        if (countyId !in world.administrativeCountyIds) return null
        val city = world.getCityById(countyId) ?: return null
        return try { HwihaCountyWarehouse.read(city.meta, countyId) } catch (_: IllegalArgumentException) { null }
    }
}
