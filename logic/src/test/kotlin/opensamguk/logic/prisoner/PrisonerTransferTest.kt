package opensamguk.logic.prisoner

import kotlin.test.Test
import kotlin.test.assertEquals

class PrisonerTransferTest {
    @Test fun `explicit mixed transfer is atomic preserves source and replays once`() {
        val initial = captured()
        val result = PrisonerTransfer.transfer(initial, "transfer", 10, 11, setOf(ref(1), ref(4)), setOf(20), true)
        assertEquals(PrisonerCode.APPLIED, result.code)
        assertEquals(5, result.state.groups().sumOf { it.count })
        assertEquals(11L, result.state.soldiers.getValue(ref(1)).ownerGeneralId)
        assertEquals(10L, result.state.soldiers.getValue(ref(2)).ownerGeneralId)
        assertEquals(11L, result.state.generals.getValue(20).ownerGeneralId)
        assertEquals(sourceB, result.state.soldiers.getValue(ref(4)).source)
        assertEquals(PrisonerCode.ALREADY_COMPLETED,
            PrisonerTransfer.transfer(result.state, "transfer", 10, 11, setOf(ref(4), ref(1)), setOf(20), true).code)
        assertEquals(PrisonerCode.INVALID_REQUEST,
            PrisonerTransfer.transfer(result.state, "transfer", 10, 12, setOf(ref(1), ref(4)), setOf(20), true).code)
    }

    @Test fun `ownership and missing policies are enforced before any writes`() {
        val state = captured()
        val results = listOf(
            PrisonerTransfer.transfer(state, "a", 99, 11, setOf(ref(1)), conditionsApproved = true) to PrisonerCode.NOT_OWNER,
            PrisonerTransfer.transfer(state, "b", 10, 11, setOf(ref(1)), conditionsApproved = null) to PrisonerCode.NOT_DECIDED,
            PrisonerTransfer.transfer(state, "c", 10, 11, setOf(ref(1)), conditionsApproved = false) to PrisonerCode.ACTION_NOT_ALLOWED,
            PrisonerTransfer.transfer(state, "d", 10, 11, setOf(ref(1), ref(99)), conditionsApproved = true) to PrisonerCode.NOT_FOUND,
        )
        results.forEach { (result, code) -> assertEquals(code, result.code); assertEquals(state, result.state) }
    }

    @Test fun `training prisoners cannot be transferred or released`() {
        val initial = captured().copy(stocks = mapOf(10L to mapOf("grain" to 10L)))
        val state = Incorporation.enqueue(initial, "train", 10, setOf(ref(1)), 0, IncorporationTerms(mapOf("grain" to 2), 5)).state
        assertEquals(PrisonerCode.ACTION_NOT_ALLOWED,
            PrisonerTransfer.transfer(state, "move", 10, 11, setOf(ref(1)), conditionsApproved = true).code)
        assertEquals(PrisonerCode.ACTION_NOT_ALLOWED,
            PrisonerRecords.departSoldiers(state, "release", 10, setOf(ref(1)), SoldierCaptivity.RELEASED, true).code)
    }
}
