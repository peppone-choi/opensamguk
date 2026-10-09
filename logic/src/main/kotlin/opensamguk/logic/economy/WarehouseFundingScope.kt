package opensamguk.logic.economy

/** The existing card-location funding rule. Callbacks stay lazy and preserve warehouse read order. */
object WarehouseFundingScope {
    fun <C> countiesFor(
        payerNationId: Int, locationCityId: Int, city: (Int) -> C?, capital: () -> Int?, cities: () -> List<C>,
        id: (C) -> Int, nation: (C) -> Int, supplied: (C) -> Boolean, hasWarehouse: (Int) -> Boolean,
    ): List<Int> {
        if (payerNationId <= 0) return emptyList()
        val location = city(locationCityId)?.takeIf { nation(it) == payerNationId } ?: return emptyList()
        if (!supplied(location)) return listOf(id(location)).filter(hasWarehouse)
        val capitalId = capital()
        return cities().filter { nation(it) == payerNationId && supplied(it) && hasWarehouse(id(it)) }
            .sortedWith(compareBy({ id(it) != capitalId }, { id(it) })).map(id)
    }
}
