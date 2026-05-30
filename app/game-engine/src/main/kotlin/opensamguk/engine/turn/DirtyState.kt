package opensamguk.engine.turn

import opensamguk.logic.domain.NationTurn
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
    /**
     * P2 satellite write-set (Task FF1):
     *  - [rankDirty]: per-general rank_data deltas — at most one [RankDelta] per [RankColumn]
     *    (the 3-Map collapse). Flushed in step-8 (rankVarIncrease then rankVarSet).
     *  - [nationTurnDirty]: reserved nation-command rows to (re)write (step-3 createMany / step-7).
     *  - [kvDirty]: nation_env key → json | `null`-deletes (step-10; delete-on-null, KVStorage.php).
     */
    val rankDirty: Map<Int, Map<RankColumn, RankDelta>> = emptyMap(),
    val nationTurnDirty: List<NationTurn> = emptyList(),
    val kvDirty: Map<String, Any?> = emptyMap(),
)
