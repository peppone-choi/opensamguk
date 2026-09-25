package opensamguk.logic.imperial

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImperialEdictProjectionTest {
    private val proposal = ImperialEdictProposal("secret-1", "later_han", 50, 1, 8, "公開建議")

    private fun dispatchedSecret(): ImperialEdict {
        val reviewed = ImperialEdictPipeline.review(ImperialEdict(proposal),
            EmperorEdictDecision.SECRET_ORDER, CourtFactionDecision.OPPOSE, 1, "密詔內容")
        val registered = ImperialEdictPipeline.register(reviewed, 3, "register-1")
        val sealed = ImperialEdictPipeline.seal(registered, "seal-1", true)
        return ImperialEdictPipeline.dispatch(sealed, 5)
    }

    @Test
    fun `secret order does not leak to another faction recipient or proposer before delivery`() {
        val edict = dispatchedSecret()
        assertNull(ImperialEdictProjection.forViewer(edict, 99, 9, emptySet()))
        assertNull(ImperialEdictProjection.forViewer(edict, null, 8, emptySet()))
        val proposer = ImperialEdictProjection.forViewer(edict, 50, 10, emptySet())
        assertEquals(EdictViewScope.PROPOSAL_ONLY, proposer?.scope)
        assertEquals(EdictStage.PROPOSED, proposer?.stage)
        assertEquals("公開建議", proposer?.text)
        val courier = ImperialEdictProjection.forViewer(edict, 5, 10, emptySet())
        assertEquals(EdictViewScope.COURIER_ENVELOPE, courier?.scope)
        assertNull(courier?.text)
        assertEquals("密詔內容", ImperialEdictProjection.forViewer(edict, 1, null, emptySet())?.text)
        assertEquals("密詔內容", ImperialEdictProjection.forViewer(edict, 7, null, setOf(7))?.text)
    }

    @Test
    fun `recipient learns secret content only after delivery and outsiders remain blind`() {
        val delivered = ImperialEdictPipeline.deliver(dispatchedSecret(), 8)
        assertEquals("密詔內容", ImperialEdictProjection.forViewer(delivered, null, 8, emptySet())?.text)
        assertNull(ImperialEdictProjection.forViewer(delivered, null, 9, emptySet()))
    }
}
