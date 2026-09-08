package opensamguk.engine.turn

import opensamguk.infra.seed.HistoricalBattlefieldCatalog
import opensamguk.logic.world.GeneralPositionAssessment
import opensamguk.logic.world.GeneralPositionChangeResult
import opensamguk.logic.world.StrategicNodeRef

/** Keep an existing, non-deployed position aligned when ordinary city movement changes its anchor. */
fun applyPositionAwareGeneral(
    world: InMemoryTurnWorld,
    recorder: ChangeRecorder,
    next: TurnGeneral,
    anchors: () -> Map<Int, StrategicNodeRef> = HistoricalBattlefieldCatalog::cityAnchors,
) {
    val previous = world.getGeneralById(next.id)
    val position = world.generalPositionSnapshot()?.stateFor(next.id)
    if (previous != null && position != null) {
        val relocated = previous.cityId != next.cityId
        val released = previous.nationId != next.nationId || previous.userId != next.userId
        if (position.battlefield != null && (relocated || released)) {
            // Ordinary actors/cascades are excluded upstream. Forced release/admin relocation revokes deployment.
            recorder.removeGeneralPosition(world, next.id)
        } else if (relocated) {
            val node = anchors()[next.cityId]
            if (node == null || position.revision == Long.MAX_VALUE) {
                // External enclaves have no verified province. City location remains real; discard obsolete qualifier.
                recorder.removeGeneralPosition(world, next.id)
            } else {
                val changed = recorder.applyGeneralPositionAssessment(world, position.revision, GeneralPositionAssessment(
                    position.topologyRevision, position.topologyHash, next.id, node,
                ))
                check(changed !is GeneralPositionChangeResult.Denied) { "City arrival position was rejected" }
            }
        }
    }
    // Revocation may have added a durable revision tombstone after the caller built next.
    val floor = maxOf(
        (world.getGeneralById(next.id)?.meta?.get("spatialPositionRevisionFloor") as? Number)?.toLong() ?: 0L,
        (next.meta["spatialPositionRevisionFloor"] as? Number)?.toLong() ?: 0L,
    )
    world.applyGeneralDirtyFree(if (floor > 0) next.copy(meta = next.meta + ("spatialPositionRevisionFloor" to floor)) else next)
}
