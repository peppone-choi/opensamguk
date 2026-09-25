package opensamguk.logic.vassal

import opensamguk.logic.economy.Resources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VassalContractTest {
    private val rules = VassalRules.loadClasspath()
    private val contract = VassalContract(
        id = "contract-1", sovereignLordId = 1, vassalLordId = 2, nationId = 7,
        fiefCountyIds = setOf(10, 11), tributePercent = rules.defaultTributePercent,
        reinforcementTroops = rules.defaultReinforcementTroops,
        autonomy = setOf(VassalAutonomy.COUNTY_POLICY), diplomacyRight = VassalDiplomacyRight.WITH_APPROVAL,
        breachConditions = VassalBreachKind.entries.toSet(), loyalty = 60, signedTurn = 5,
    )

    @Test
    fun `vassal is a lord in the ruler nation but not a human retinue card`() {
        val lords = listOf(VassalLord(1, 7, true, true), VassalLord(2, 7, true, false))
        VassalContracts.validate(listOf(contract), lords, mapOf(10 to 7, 11 to 7), mapOf(1 to 1, 2 to 2), rules, atTurn = 12)
        assertFailsWith<IllegalArgumentException> {
            VassalContracts.validate(listOf(contract), lords, mapOf(10 to 7, 11 to 7), mapOf(1 to 1, 2 to 1), rules, atTurn = 12)
        }
        assertFailsWith<IllegalArgumentException> {
            VassalContracts.validate(listOf(contract), lords, mapOf(10 to 7, 11 to 8), mapOf(1 to 1, 2 to 2), rules, atTurn = 12)
        }
    }

    @Test
    fun `nested vassals are rejected`() {
        val nested = contract.copy(id = "nested", sovereignLordId = 2, vassalLordId = 3, fiefCountyIds = setOf(12))
        assertFailsWith<IllegalArgumentException> {
            VassalContracts.validate(listOf(contract, nested), listOf(
                VassalLord(1, 7, true, true), VassalLord(2, 7, true, false), VassalLord(3, 7, true, false)),
                mapOf(10 to 7, 11 to 7, 12 to 7), mapOf(1 to 1, 2 to 2, 3 to 3), rules, atTurn = 12)
        }
    }

    @Test
    fun `tribute transfers only within connected warehouse fragments and conserves stock`() {
        val income = mapOf(10 to Resources(money = 100, grain = 200), 11 to Resources(money = 50, grain = 100))
        val warehouses = listOf(
            VassalWarehouse(10, "connected", Resources(money = 150, grain = 250)),
            VassalWarehouse(11, "cut-off", Resources(money = 70, grain = 120)),
            VassalWarehouse(20, "connected", Resources(money = 5, grain = 7)),
        )
        val result = VassalTribute.settle(contract, income, warehouses, setOf(20), atTurn = 12)
        val paidFromConnected = Resources(money = 100L * contract.tributePercent / 100, grain = 200L * contract.tributePercent / 100)
        val unpaidFromDisconnected = Resources(money = 50L * contract.tributePercent / 100, grain = 100L * contract.tributePercent / 100)
        assertEquals(paidFromConnected.credit(unpaidFromDisconnected), result.due)
        assertEquals(paidFromConnected, result.paid)
        assertEquals(unpaidFromDisconnected, result.unpaid)
        assertEquals(listOf(TributeTransfer(10, 20, paidFromConnected)), result.transfers)
        assertFalse(result.fulfilled)
        val before = warehouses.map { it.stock }.reduce(Resources::credit)
        val after = result.nextStockByCounty.values.reduce(Resources::credit)
        assertEquals(before, after)
    }

    @Test
    fun `reinforcement accepts delays and reductions but late refusal breaches`() {
        val request = ReinforcementRequest(contract.id, "operation-1", rules.defaultReinforcementTroops, 12)
        val minimumReduced = (request.requestedTroops.toLong() * rules.minimumReducedPercent + 99) / 100
        assertEquals(ReinforcementOutcome.PENDING, VassalReinforcement.assess(contract, request, null, 13, rules).outcome)
        assertEquals(ReinforcementOutcome.DELAYED, VassalReinforcement.assess(contract, request, ReinforcementResponse(ReinforcementReply.DELAY, request.requestedTroops, 13), 13, rules).outcome)
        assertEquals(ReinforcementOutcome.BREACH, VassalReinforcement.assess(contract, request, ReinforcementResponse(ReinforcementReply.ACCEPT, request.requestedTroops - 1, 13), 13, rules).outcome)
        assertEquals(ReinforcementOutcome.BREACH, VassalReinforcement.assess(contract, request, ReinforcementResponse(ReinforcementReply.DELAY, request.requestedTroops - 1, 13), 13, rules).outcome)
        assertEquals(ReinforcementOutcome.REDUCED, VassalReinforcement.assess(contract, request, ReinforcementResponse(ReinforcementReply.REDUCE, minimumReduced.toInt(), 13), 13, rules).outcome)
        assertEquals(ReinforcementOutcome.BREACH, VassalReinforcement.assess(contract, request, ReinforcementResponse(ReinforcementReply.REFUSE, 0, 13), 13, rules).outcome)
        assertEquals(ReinforcementOutcome.BREACH, VassalReinforcement.assess(contract, request, null, request.issuedTurn + rules.reinforcementReplyTurns + 1, rules).outcome)
    }

    @Test
    fun `breach changes loyalty but does not itself end the contract`() {
        val kinds = VassalContracts.breachKinds(contract, rules.missedTributeMonthsForBreach, false, false, rules)
        assertEquals(setOf(VassalBreachKind.MISSED_TRIBUTE), kinds)
        assertEquals(60 - rules.loyaltyLossOnBreach, VassalContracts.loyaltyAfterBreach(contract, kinds, rules))
        assertTrue(contract.active)
    }

    @Test
    fun `state codec preserves contract and tribute history and rejects orphans`() {
        val receipt = VassalTributeReceipt(contract.id, 196, 1, Resources(money = 30), Resources(money = 20), Resources(money = 10))
        val state = VassalState(listOf(contract), listOf(receipt))
        val wire = VassalStateCodec.encode(state)
        assertEquals(state, VassalStateCodec.decode(wire))
        assertEquals(listOf(receipt), VassalStateCodec.view(state, contract.id)?.tributeHistory)
        assertFailsWith<IllegalArgumentException> { VassalStateCodec.decode(wire.replace("\"version\":1", "\"version\":9")) }
        assertFailsWith<IllegalArgumentException> { VassalState(listOf(contract), listOf(receipt.copy(contractId = "missing"))) }
    }
}
