package opensamguk.engine.campaign

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.Nation
import opensamguk.logic.input.QueuedDispatch

class CourtExecutionCheckpointTest {
    @Test
    fun `failed issuer suffix is removed without losing earlier deferred result`() {
        val fixture = CampaignWorldFixture()
        val route = fixture.route()
        fun queued(id: String) = QueuedDispatch(id, 42, 502, route.destinationCounty)
        val ruler = fixture.person(501, 1, route.startCity, userId = "42").let {
            it.copy(meta = it.meta + (QueuedDispatch.META_KEY to queued("before").toMetaValue()))
        }
        val world = fixture.world(listOf(ruler to route.start), nations = listOf(
            Nation(1, "N1", "#111111", capitalCityId = route.startCity)))
        val handler = CourtHandler(world, ChangeRecorder())
        handler.onIssuerTurn(501)
        val checkpoint = handler.checkpointExecutions()
        val current = checkNotNull(world.getGeneralById(501))
        world.updateGeneral(current.copy(meta = current.meta + (QueuedDispatch.META_KEY to queued("failed").toMetaValue())))
        handler.onIssuerTurn(501)

        handler.restoreExecutions(checkpoint)
        assertEquals(listOf("before"), handler.takeExecutions().map { it.requestId })
        assertTrue(handler.takeExecutions().isEmpty())
    }
}
