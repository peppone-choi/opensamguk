package opensamguk.engine.turn

import java.time.Instant

/**
 * Snapshot of a removed nation, captured for the per-season `ng_old_nations` archive
 * write (mirrors `inMemoryWorld.ts` `deletedNationSnapshots`).
 */
data class DeletedNationSnapshot(
    val nation: Nation,
    val generalIds: List<Int>,
    val removedAt: Instant,
)

/**
 * Exact return shape of `InMemoryTurnWorld.consumeDirtyState()`
 * (faithful to `inMemoryWorld.ts:697-775`). Single-shot: produced once per flush,
 * then all source sets are cleared.
 */
data class DirtyState(
    val generals: List<TurnGeneral>,
    val cities: List<City>,
    val nations: List<Nation>,
    val troops: List<Troop>,
    val deletedTroops: List<Int>,
    val deletedGenerals: List<Int>,
    val deletedNations: List<Int>,
    val deletedNationSnapshots: List<DeletedNationSnapshot>,
    val diplomacy: List<TurnDiplomacy>,
    val logs: List<LogEntryDraft>,
    val createdGenerals: List<TurnGeneral>,
    val createdNations: List<Nation>,
    val createdTroops: List<Troop>,
    val createdDiplomacy: List<TurnDiplomacy>,
)
