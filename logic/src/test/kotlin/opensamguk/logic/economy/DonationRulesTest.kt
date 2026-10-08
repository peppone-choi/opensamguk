package opensamguk.logic.economy

import kotlin.test.*
import opensamguk.logic.domestic.*
import opensamguk.logic.input.*

class DonationRulesTest {
    private val actor = DomesticPerson(7, "기부자", 2, true, 0, 1, 60, 60, 60, 60, 60,
        "p", false, emptyMap(), gold = 100, rice = 200)
    private val warehouse = CountyWarehouse(11, 3, Resources(10, 20, 30, 40, 50))
    private fun state(stock: CountyWarehouse? = warehouse) = DomesticProjection(RuleProfile.HWIHA,
        Phase(200, 1, 1), listOf(actor), emptyList(), listOf(DomesticCounty(11, "수령현", 1, "p", null,
            stock?.let { mapOf(CountyWarehouse.META_KEY to it.toMetaValue()) }.orEmpty())),
        listOf(DomesticNation(1, "수령국", 11, emptyMap(), gold = 900, rice = 800),
            DomesticNation(2, "무영토", null, emptyMap())), setOf("p"))
    private fun request(resource: TransferResource = TransferResource.MONEY, amount: Int = 30) =
        TransferRequest(actor.id, TransferInput.DONATE, resource, amount)
    private fun denied(reason: TransferFailure, request: TransferRequest = request(), state: DomesticProjection = state()) {
        assertEquals(reason, assertIs<TransferAssessment.Rejected>(TransferRules.assess(request, state)).reason)
    }

    @Test fun `landless donor pays the actual local owner's warehouse snapshot`() {
        for (resource in listOf(TransferResource.MONEY, TransferResource.GRAIN)) {
            val ready = assertIs<TransferAssessment.Eligible>(TransferRules.assess(request(resource), state()))
            assertEquals(1, ready.nation!!.id)
            assertEquals(11, ready.county!!.id)
            assertEquals(warehouse, ready.warehouse)
            assertEquals(warehouse.stock, ready.receivedStock)
            assertEquals(Resources(100, 200), ready.donorStock)
        }
    }
    @Test fun `warehouse long balances are not replaced by legacy national columns`() {
        val large = warehouse.copy(stock = Resources(money = Int.MAX_VALUE.toLong() + 5))
        val ready = assertIs<TransferAssessment.Eligible>(TransferRules.assess(request(), state(large)))
        assertEquals(large.stock, ready.receivedStock)
    }
    @Test fun `missing invalid and overflowing warehouses deny without synthesizing stock`() {
        denied(TransferFailure.STATE_UNAVAILABLE, state = state(null))
        val invalid = state().let { it.copy(counties = listOf(it.counties.single().copy(
            meta = mapOf(CountyWarehouse.META_KEY to mapOf("version" to 1))))) }
        denied(TransferFailure.STATE_UNAVAILABLE, state = invalid)
        denied(TransferFailure.STOCK_OVERFLOW, state = state(warehouse.copy(stock = Resources(money = Long.MAX_VALUE))))
        denied(TransferFailure.STOCK_OVERFLOW, state = state(warehouse.copy(revision = Long.MAX_VALUE)))
    }
    @Test fun `amount ownership of destination position and battle are rechecked`() {
        denied(TransferFailure.INSUFFICIENT_STOCK, request(amount = 101))
        denied(TransferFailure.INVALID_INPUT, request(amount = 0))
        denied(TransferFailure.INVALID_INPUT, request(TransferResource.IRON))
        denied(TransferFailure.INVALID_INPUT, request().copy(targetGeneralId = 8))
        denied(TransferFailure.STATE_UNAVAILABLE, state = state().copy(people = listOf(actor.copy(
            meta = mapOf(CaptiveState.META_KEY to null)))))
        denied(TransferFailure.BATTLE_PENDING, state = state().copy(people = listOf(actor.copy(inBattle = true))))
        denied(TransferFailure.POSITION_UNAVAILABLE, state = state().copy(people = listOf(actor.copy(node = null))))
        denied(TransferFailure.COUNTY_UNAVAILABLE, state = state().copy(counties = emptyList()))
        denied(TransferFailure.NATION_UNAVAILABLE, state = state().copy(nations = emptyList()))
        val moved = state().let { it.copy(counties = listOf(it.counties.single().copy(nationId = 2))) }
        assertEquals(2, assertIs<TransferAssessment.Eligible>(TransferRules.assess(request(), moved)).nation!!.id)
    }
}
