package opensamguk.engine.flush

import opensamguk.engine.turn.InMemoryTurnWorld

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
    const val RANK_ROWS_PER_GENERAL: Int = 40

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
}
