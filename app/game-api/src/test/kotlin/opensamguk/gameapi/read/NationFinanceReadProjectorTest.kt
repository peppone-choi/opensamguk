package opensamguk.gameapi.read

import opensamguk.logic.domain.City
import opensamguk.logic.domain.Nation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NationFinanceReadProjectorTest {
    private val projector = NationFinanceReadProjector()
    private val city = City(
        id = 1,
        nationId = 1,
        level = 5,
        commerce = 1000,
        commerceMax = 2000,
        agriculture = 1500,
        agricultureMax = 2000,
        supplyState = 1,
        frontState = 0,
        trust = 100.0,
        security = 1000,
        securityMax = 2000,
        defense = 1000,
        wall = 1000,
        wallMax = 2000,
        population = 30000,
        populationMax = 100000,
    )

    @Test
    fun `city projection applies rate over twenty to all three streams`() {
        val nation = Nation(1, 5, null, gold = 1000, rice = 2000, meta = mapOf("rate" to 20))

        val result = projector.city(city, nation, 0)

        assertEquals(525, result?.goldIncome)
        assertEquals(788, result?.riceIncome)
        assertEquals(175, result?.farmIncome)
    }

    @Test
    fun `nation projection exposes totals upkeep and budgets`() {
        val nation = Nation(1, 5, null, gold = 1000, rice = 2000, meta = mapOf("rate" to 20, "bill" to 100))

        val result = projector.nation(listOf(city), nation, emptyMap(), listOf(0.0))

        assertEquals(525, result.goldIncome)
        assertEquals(0, result.warIncome)
        assertEquals(788, result.riceIncome)
        assertEquals(175, result.farmIncome)
        assertEquals(400, result.outcome)
        assertEquals(1125, result.goldBudget)
        assertEquals(125, result.goldBudgetDiff)
        assertEquals(2563, result.riceBudget)
        assertEquals(563, result.riceBudgetDiff)
    }

    @Test
    fun `missing policy values keep finance fields null`() {
        val nation = Nation(1, 5, 1)

        assertNull(projector.city(city, nation, 0))
        val result = projector.nation(listOf(city), nation, emptyMap(), emptyList())
        assertNull(result.goldIncome)
        assertNull(result.outcome)
        assertNull(result.goldBudget)
    }
}
