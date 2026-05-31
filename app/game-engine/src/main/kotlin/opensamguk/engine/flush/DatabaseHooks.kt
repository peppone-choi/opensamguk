package opensamguk.engine.flush

import opensamguk.engine.turn.ChangeRecorder
import opensamguk.engine.turn.DiplomacyRowPatch
import opensamguk.engine.turn.DirtyState
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.KvKey
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.RankDelta
import opensamguk.engine.turn.TurnWorldState
import opensamguk.infra.persistence.DiplomacyUpdate
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.KvWrite
import opensamguk.infra.persistence.LogRow
import opensamguk.infra.persistence.RankFlushOp
import opensamguk.infra.persistence.RankWrite

/**
 * Flush STUB recording the exact write ORDER of `databaseHooks.ts` `flushChanges`.
 * NO real SQL. The recorder ([FlushOpRecorder]) is the only sink the daemon flush
 * path writes through — this is how design §0.1 #3 (no JPA EntityManager in the write
 * path) is enforced structurally; [opensamguk.engine.flush.DatabaseHooks] takes only a
 * recorder. P1 replaces the recorder with a JDBC-batch executor (never JPA).
 *
 * Per design §7 the inheritance / storage / hall / dynasty tables are NOT in this op list —
 * they sit outside the world+flush boundary. The single archive write performed here is
 * `ng_old_nations` (the per-season game-table snapshot of a collapsed nation), NOT the
 * cross-season dynasty table.
 *
 * Exact order (faithful to `databaseHooks.ts:309-568`):
 *  1. worldState.update (always)
 *  2. ng_old_nations.upsert — one per deletedNationSnapshot
 *  3. createMany: general, nation, troop, diplomacy
 *  4. deleteMany troop (deletedTroops)
 *  5. kill() delete: general, general_turn, then rank_data (deletedGenerals)
 *  6. nation cascade: deleteMany diplomacy, nation_turn, nation (deletedNations)
 *  7. updates: general (excl created), city, nation upsert (excl created),
 *     troop (excl created), diplomacy (excl created)
 *  8. rank_data.upsert — RANK_ROWS_PER_GENERAL per rank target (created + dirty generals)
 *  9. log_entry.createMany
 * 10. reservedTurns.flush
 */
object DatabaseHooks {
    /**
     * The number of rank_data rows pre-seeded per general — `RankColumn.entries.size` (= 37,
     * VERIFIED against the PHP `sammo\Enums\RankColumn` enum, `RankColumn::cases()` = 37). FF2
     * reconciles the stale `40` placeholder; the order-test stub still models step-8 as a full
     * `rank_data` write of `RANK_ROWS_PER_GENERAL` rows per target (the real [JdbcFlushExecutor]
     * UPDATEs only the affected rows).
     */
    val RANK_ROWS_PER_GENERAL: Int = RankColumn.entries.size

    fun flushChanges(world: InMemoryTurnWorld, recorder: FlushOpRecorder) {
        val dirty = world.consumeDirtyState()

        // 1. worldState.update — always fires.
        recorder.recordAlways("world_state", FlushOp.Verb.UPDATE, 1)

        // 2. ng_old_nations.upsert per deleted-nation snapshot.
        repeat(dirty.deletedNationSnapshots.size) {
            recorder.record("ng_old_nations", FlushOp.Verb.UPSERT, 1)
        }

        // 3. createMany general, nation, troop, diplomacy (each guarded on > 0).
        recorder.record("general", FlushOp.Verb.CREATE_MANY, dirty.createdGenerals.size)
        recorder.record("nation", FlushOp.Verb.CREATE_MANY, dirty.createdNations.size)
        recorder.record("troop", FlushOp.Verb.CREATE_MANY, dirty.createdTroops.size)
        recorder.record("diplomacy", FlushOp.Verb.CREATE_MANY, dirty.createdDiplomacy.size)

        // 4. deleteMany troop.
        recorder.record("troop", FlushOp.Verb.DELETE_MANY, dirty.deletedTroops.size)

        // 5. kill()'s ported delete: general, general_turn, then rank_data (`General.php:92-95`;
        //    general_access_log is not ported to the V1 schema). All guarded on deletedGenerals > 0.
        if (dirty.deletedGenerals.isNotEmpty()) {
            recorder.record("general", FlushOp.Verb.DELETE_MANY, dirty.deletedGenerals.size)
            recorder.record("general_turn", FlushOp.Verb.DELETE_MANY, dirty.deletedGenerals.size)
            recorder.record("rank_data", FlushOp.Verb.DELETE_MANY, dirty.deletedGenerals.size)
        }

        // 6. nation cascade: diplomacy, nation_turn, nation (all guarded on deletedNations > 0).
        if (dirty.deletedNations.isNotEmpty()) {
            recorder.record("diplomacy", FlushOp.Verb.DELETE_MANY, dirty.deletedNations.size)
            recorder.record("nation_turn", FlushOp.Verb.DELETE_MANY, dirty.deletedNations.size)
            recorder.record("nation", FlushOp.Verb.DELETE_MANY, dirty.deletedNations.size)
        }

        // 7. updates — exclude ids created this tick.
        val createdGeneralIds = dirty.createdGenerals.map { it.id }.toSet()
        val createdNationIds = dirty.createdNations.map { it.id }.toSet()
        val createdTroopIds = dirty.createdTroops.map { it.id }.toSet()
        val createdDiplomacyKeys = dirty.createdDiplomacy.map { "${it.fromNationId}:${it.toNationId}" }.toSet()

        recorder.record("general", FlushOp.Verb.UPDATE, dirty.generals.count { it.id !in createdGeneralIds })
        recorder.record("city", FlushOp.Verb.UPDATE, dirty.cities.size)
        recorder.record("nation", FlushOp.Verb.UPSERT, dirty.nations.count { it.id !in createdNationIds })
        recorder.record("troop", FlushOp.Verb.UPDATE, dirty.troops.count { it.id !in createdTroopIds })
        recorder.record(
            "diplomacy",
            FlushOp.Verb.UPDATE,
            dirty.diplomacy.count { "${it.fromNationId}:${it.toNationId}" !in createdDiplomacyKeys },
        )

        // 8. rank_data.upsert — RANK_ROWS_PER_GENERAL rows per rank target (created + dirty).
        val rankTargets = dirty.createdGenerals.size + dirty.generals.size
        recorder.record("rank_data", FlushOp.Verb.UPSERT, rankTargets * RANK_ROWS_PER_GENERAL)

        // 9. log_entry.createMany.
        recorder.record("log_entry", FlushOp.Verb.CREATE_MANY, dirty.logs.size)

        // 10. reservedTurns.flush — represented as a recorded op tag.
        recorder.record("reserved_turns", FlushOp.Verb.UPDATE, 1)
    }

    /**
     * The REAL P1 write path: drain the world's dirty set, map it to an [FlushPayload], and hand it
     * to the injected [JdbcFlushExecutor] which runs the EXACT ordered contract above as plain JDBC
     * inside ONE transaction (design §0.1 #3 — no `EntityManager`). The recorder-based overload
     * stays for the order tests; this overload is what `TurnRunService` (Task F5) calls.
     *
     * The op ORDER is preserved by the executor itself (it implements steps 1→10); this method only
     * builds the payload. P1 exercises steps 1 (world_state), 7 (general+city UPDATE), 9 (log_entry),
     * 10 (reserved_turns) — the other steps are no-ops on the empty created/deleted lists.
     */
    fun flushChanges(world: InMemoryTurnWorld, executor: JdbcFlushExecutor) {
        val dirty = world.consumeDirtyState()
        executor.flush(toFlushPayload(world.getState(), dirty))
    }

    /**
     * Map the engine [DirtyState] → infra [FlushPayload]. Reuses the SAME engine→logic conversion as
     * the read/resolve path ([PerTurnOverlay.toLogicGeneral]/[PerTurnOverlay.toLogicCity]) so the
     * flushed rows are byte-identical to what the resolver produced. Created-this-tick ids are
     * excluded from the UPDATE batch (step-7 contract); P1 never creates, so this is identity here.
     */
    internal fun toFlushPayload(state: TurnWorldState, dirty: DirtyState): FlushPayload {
        val createdGeneralIds = dirty.createdGenerals.map { it.id }.toSet()
        val createdNationIds = dirty.createdNations.map { it.id }.toSet()
        val updatedGenerals = dirty.generals
            .filter { it.id !in createdGeneralIds }
            .map { PerTurnOverlay.toLogicGeneral(it) }
        val updatedCities = dirty.cities.map { PerTurnOverlay.toLogicCity(it) }
        val updatedNations = dirty.nations
            .filter { it.id !in createdNationIds }
            .map { PerTurnOverlay.toLogicNation(it) }
        val createdNations = dirty.createdNations.map { PerTurnOverlay.toLogicNation(it) }
        val createdDiplomacy = dirty.createdDiplomacy.map { PerTurnOverlay.toLogicDiplomacy(it) }
        val logEntries = dirty.logs.map { toLogRow(it, state.currentYear, state.currentMonth) }

        // P2 satellite write-set: thread the rank/nationTurn dirty sets into the payload. The
        // rank map is flattened increments-before-sets (matching the General.applyDB iteration:
        // rankVarIncrease first, then rankVarSet) per general, in DirtyState insertion order.
        val rankWrites = dirty.rankDirty.flatMap { (generalId, deltas) ->
            val increments = deltas.entries.filter { it.value is RankDelta.Increment }
            val sets = deltas.entries.filter { it.value is RankDelta.Set }
            (increments + sets).map { (column, delta) ->
                RankWrite(
                    generalId = generalId,
                    type = column.column,
                    op = when (delta) {
                        is RankDelta.Increment -> RankFlushOp.Increment(delta.value)
                        is RankDelta.Set -> RankFlushOp.Set(delta.value)
                    },
                )
            }
        }

        // F3 (FF2): wire the tombstone seam — DirtyState.deleted* → FlushPayload.deleted*. The infra
        // delete-sets (step-5 general/general_turn/rank_data, step-6 nation cascade, step-2
        // ng_old_nations) already exist; this is the emitter that makes the payload non-empty so they
        // fire. The DeletedNationSnapshot → Map carries the nation id + its archived general ids
        // (the ng_old_nations archive write).
        val deletedNationSnapshots = dirty.deletedNationSnapshots.map { snap ->
            linkedMapOf<String, Any?>(
                "nation" to snap.nation.id,
                "general_ids" to snap.generalIds,
            )
        }

        return FlushPayload(
            worldStateUpdate = linkedMapOf(
                "id" to state.id,
                "current_year" to state.currentYear,
                "current_month" to state.currentMonth,
            ),
            updatedGenerals = updatedGenerals,
            updatedCities = updatedCities,
            updatedNations = updatedNations,
            createdNations = createdNations,
            createdNationTurns = dirty.nationTurnDirty,
            createdDiplomacy = createdDiplomacy,
            logEntries = logEntries,
            rankWrites = rankWrites,
            kvWrites = toKvWrites(dirty.kvDirty),
            deletedGenerals = dirty.deletedGenerals,
            deletedNations = dirty.deletedNations,
            deletedNationSnapshots = deletedNationSnapshots,
        )
    }

    /**
     * T0.3 — flatten a recorder's rank delta map into the executor [RankWrite] list: increments
     * before sets per general (matching `General.applyDB`: rankVarIncrease first, then rankVarSet),
     * in the map's insertion order.
     */
    internal fun toRankWrites(rankDirty: Map<Int, Map<RankColumn, RankDelta>>): List<RankWrite> =
        rankDirty.flatMap { (generalId, deltas) ->
            val increments = deltas.entries.filter { it.value is RankDelta.Increment }
            val sets = deltas.entries.filter { it.value is RankDelta.Set }
            (increments + sets).map { (column, delta) ->
                RankWrite(
                    generalId = generalId,
                    type = column.column,
                    op = when (delta) {
                        is RankDelta.Increment -> RankFlushOp.Increment(delta.value)
                        is RankDelta.Set -> RankFlushOp.Set(delta.value)
                    },
                )
            }
        }

    /**
     * T0.3 — map the recorder's `(table, namespace, key) → value` KV delta channel to the executor
     * [KvWrite] list. The value is passed through verbatim (a null is a delete-on-null; a raw json
     * String is bound as-is by the executor; an Int/Map/List is MetaJson-encoded at flush). Insertion
     * order is preserved so the writes flush in the order the resolver produced them.
     */
    internal fun toKvWrites(kvDirty: Map<KvKey, Any?>): List<KvWrite> =
        kvDirty.map { (k, v) -> KvWrite(table = k.table, namespace = k.namespace, key = k.key, value = v) }

    /**
     * T0.3 CONVERGENCE — the single superset payload builder for the daemon write path. The
     * authoritative dirty source is the [ChangeRecorder] (design Risk #4: one dirty truth): dirty
     * general/city/nation IDs + rank + KV deltas come from the recorder, resolved against the world's
     * already-applied post-state rows. The world's [DirtyState] supplies created/deleted nation/general/
     * troop/diplomacy + logs (the world-level lifecycle effects). This is what `TurnRunService` now
     * routes through, so a nation/rank/kv/diplomacy delta no longer vanishes at flush.
     */
    internal fun toFlushPayload(
        world: InMemoryTurnWorld,
        recorder: ChangeRecorder,
        dirty: DirtyState,
    ): FlushPayload {
        val state = world.getState()
        val createdGeneralIds = dirty.createdGenerals.map { it.id }.toSet()
        val createdNationIds = dirty.createdNations.map { it.id }.toSet()

        // Dirty rows from the recorder (the lone dirty source), resolved to the world's post-state.
        val updatedGenerals = recorder.dirtyGeneralIds()
            .filter { it !in createdGeneralIds }
            .mapNotNull { world.getGeneralById(it) }
            .map { PerTurnOverlay.toLogicGeneral(it) }
        val updatedCities = recorder.dirtyCityIds()
            .mapNotNull { world.getCityById(it) }
            .map { PerTurnOverlay.toLogicCity(it) }
        val updatedNations = recorder.dirtyNationIds()
            .filter { it !in createdNationIds }
            .mapNotNull { world.getNationById(it) }
            .map { PerTurnOverlay.toLogicNation(it) }

        val createdNations = dirty.createdNations.map { PerTurnOverlay.toLogicNation(it) }
        val createdDiplomacy = dirty.createdDiplomacy.map { PerTurnOverlay.toLogicDiplomacy(it) }
        val logEntries = dirty.logs.map { toLogRow(it, state.currentYear, state.currentMonth) }

        val deletedNationSnapshots = dirty.deletedNationSnapshots.map { snap ->
            linkedMapOf<String, Any?>(
                "nation" to snap.nation.id,
                "general_ids" to snap.generalIds,
            )
        }

        return FlushPayload(
            worldStateUpdate = linkedMapOf(
                "id" to state.id,
                "current_year" to state.currentYear,
                "current_month" to state.currentMonth,
            ),
            updatedGenerals = updatedGenerals,
            updatedCities = updatedCities,
            updatedNations = updatedNations,
            createdNations = createdNations,
            createdNationTurns = dirty.nationTurnDirty,
            createdDiplomacy = createdDiplomacy,
            updatedDiplomacy = toDiplomacyUpdates(recorder.diplomacyUpdateDirty()),
            logEntries = logEntries,
            rankWrites = toRankWrites(recorder.rankPatches()),
            kvWrites = toKvWrites(recorder.kvDirty()),
            deletedGenerals = dirty.deletedGenerals,
            deletedNations = dirty.deletedNations,
            deletedNationSnapshots = deletedNationSnapshots,
        )
    }

    /** T0.4 — map the recorder's per-command diplomacy patches to the executor [DiplomacyUpdate] list. */
    internal fun toDiplomacyUpdates(patches: List<DiplomacyRowPatch>): List<DiplomacyUpdate> =
        patches.map {
            DiplomacyUpdate(
                fromNationId = it.fromNationId,
                toNationId = it.toNationId,
                state = it.state,
                term = it.term,
                dead = it.dead,
            )
        }

    /**
     * Finalize an engine [LogEntryDraft] into an infra [LogRow]: stamp the year/month from world
     * state (the draft does not carry them) and uppercase `scope`/`category` to the PG enum literals
     * (`log_scope`/`log_category`) the INSERT casts to. `meta` defaults to an empty insertion-ordered
     * map when the draft carries none.
     */
    private fun toLogRow(draft: LogEntryDraft, year: Int, month: Int): LogRow = LogRow(
        scope = draft.scope.uppercase(),
        category = draft.category.uppercase(),
        text = draft.text,
        year = year,
        month = month,
        subType = draft.subType,
        generalId = draft.generalId,
        nationId = draft.nationId,
        userId = draft.userId,
        meta = draft.meta ?: linkedMapOf(),
    )
}
