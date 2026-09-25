package opensamguk.engine.campaign

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.RulerSuccessionHandler

/** Re-root a surviving nation after its capital falls; a landless nation uses the existing extinction cascade. */
internal class CapitalAfterCapture(private val world: InMemoryTurnWorld, private val recorder: ChangeRecorder) {
    fun settle(previousOwnerId: Int, capturedCountyId: Int) {
        if (previousOwnerId <= 0) return
        val nation = world.getNationById(previousOwnerId) ?: return
        val remaining = world.administrativeCountyIds.mapNotNull(world::getCityById)
            .filter { it.nationId == previousOwnerId }
            .sortedWith(compareByDescending<opensamguk.engine.turn.City> { it.population }.thenBy { it.id })
        val successor = remaining.firstOrNull()
        if (successor == null) {
            RulerSuccessionHandler(world, recorder, "").destroyLandlessNation(previousOwnerId)
            return
        }
        if (nation.capitalCityId != capturedCountyId && remaining.any { it.id == nation.capitalCityId }) return
        val after = nation.copy(capitalCityId = successor.id)
        recorder.diffNation(PerTurnOverlay.toLogicNation(nation), PerTurnOverlay.toLogicNation(after))
        world.updateNation(after)
        world.pushLog(opensamguk.engine.turn.LogEntryDraft(scope = "nation", category = "history",
            text = "수도를 ${successor.name}(으)로 옮겼습니다.", nationId = previousOwnerId))
    }
}
