package opensamguk.gameapi.creation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CreationResultProjectionTest {
    private val uuid = "92d9244b-6eb5-4f89-971d-d1b1247e0ff6"
    private val receipt = CreationReceiptView(7, 1, uuid, "internal-7")
    private val success = CreationTerminalView("internal-7", 12, 127, null, null)

    private fun project(accountId: Long = 7, worldId: Int = 1,
        receiptView: CreationReceiptView? = receipt, terminal: CreationTerminalView? = success,
        owner: CreationOwnedGeneralView? = CreationOwnedGeneralView(1, 127, 7)) =
        CreationResultProjector.project(accountId, worldId, uuid, receiptView, terminal, owner)

    @Test fun foreignWorldAndMissingReceiptsHaveOneNotFoundResult() {
        assertEquals(CreationResultProjection.NotFound, project(accountId = 8))
        assertEquals(CreationResultProjection.NotFound, project(worldId = 2))
        assertEquals(CreationResultProjection.NotFound, project(receiptView = null))
    }

    @Test fun createdRequiresCommittedResultAndLiveOwner() {
        assertEquals("PENDING", assertIs<CreationResultProjection.Visible>(project(terminal = null)).result.status)
        assertEquals("PENDING", assertIs<CreationResultProjection.Visible>(project(owner = null)).result.status)
        assertEquals("PENDING", assertIs<CreationResultProjection.Visible>(project(
            owner = CreationOwnedGeneralView(1, 127, 8))).result.status)
        assertEquals("CREATED", assertIs<CreationResultProjection.Visible>(project()).result.status)
    }

    @Test fun rejectionDoesNotRequireOwnershipAndCannotClaimSuccess() {
        val rejected = CreationTerminalView("internal-7", 12, null, "HISTORICAL_PERSON_UNAVAILABLE", "선점")
        val result = assertIs<CreationResultProjection.Visible>(project(terminal = rejected, owner = null)).result
        assertEquals("REJECTED", result.status)
        assertEquals(null, result.generalId)
        assertEquals("HISTORICAL_PERSON_UNAVAILABLE", result.error?.code)
    }

    @Test fun foreignReceiptNeverQueriesInternalResult() {
        var terminalReads = 0
        var ownerReads = 0
        val sources = object : CreationResultSources {
            override fun receipt(accountId: Long, worldId: Int, clientRequestId: String): CreationReceiptView? =
                this@CreationResultProjectionTest.receipt.takeIf { it.accountId == accountId && it.worldId == worldId }
            override fun terminal(worldId: Int, internalCommandRequestId: String): CreationTerminalView? {
                terminalReads++
                return success
            }
            override fun owner(worldId: Int, generalId: Int): CreationOwnedGeneralView? {
                ownerReads++
                return CreationOwnedGeneralView(1, 127, 7)
            }
        }
        assertEquals(CreationResultProjection.NotFound, CreationResultReader(sources).read(8, 1, uuid))
        assertEquals(0, terminalReads)
        assertEquals(0, ownerReads)
    }
}
