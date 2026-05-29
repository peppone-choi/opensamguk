package opensamguk.engine.flush

import opensamguk.engine.turn.DirtyState
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LogEntryDraft
import opensamguk.engine.turn.PerTurnOverlay
import opensamguk.engine.turn.RankColumn
import opensamguk.engine.turn.RankDelta
import opensamguk.engine.turn.TurnWorldState
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.JdbcFlushExecutor
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
 *  5. deleteMany general, then deleteMany rank_data (deletedGenerals)
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

        // 5. deleteMany general, then rank_data (both guarded on deletedGenerals > 0).
        if (dirty.deletedGenerals.isNotEmpty()) {
            recorder.record("general", FlushOp.Verb.DELETE_MANY, dirty.deletedGenerals.size)
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
            logEntries = logEntries,
            rankWrites = rankWrites,
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
