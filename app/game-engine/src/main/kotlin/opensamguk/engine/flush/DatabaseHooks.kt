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
import opensamguk.engine.turn.Troop
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.infra.persistence.AuctionBidInsertRow
import opensamguk.infra.persistence.AuctionUpsertRow
import opensamguk.infra.persistence.BettingInsertRow
import opensamguk.infra.persistence.BoardCommentInsertRow
import opensamguk.infra.persistence.BoardPostInsertRow
import opensamguk.infra.persistence.CreatedMessageRow
import opensamguk.infra.persistence.DiplomacyLetterInsertRow
import opensamguk.infra.persistence.DiplomacyUpdate
import opensamguk.infra.persistence.FlushPayload
import opensamguk.infra.persistence.GeneralCreateRow
import opensamguk.infra.persistence.GeneralAccessLogWriteRow
import opensamguk.infra.persistence.InheritanceLogRow
import opensamguk.infra.persistence.JdbcFlushExecutor
import opensamguk.infra.persistence.KvWrite
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.persistence.MessageInvalidateRow
import opensamguk.infra.persistence.LogRow
import opensamguk.logic.inheritance.InheritanceResultRow
import opensamguk.infra.persistence.RankFlushOp
import opensamguk.infra.persistence.RankWrite
import opensamguk.infra.persistence.StatisticInsertRow
import opensamguk.infra.persistence.TroopRow
import opensamguk.infra.persistence.VoteCommentInsertRow
import opensamguk.infra.persistence.VoteInsertRow
import opensamguk.infra.persistence.VotePollInsertRow
import opensamguk.infra.persistence.YearbookInsertRow
import opensamguk.logic.tick.ServerClock

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
        val createdTroopIds = dirty.createdTroops.map { it.id }.toSet()
        val updatedGenerals = dirty.generals
            .filter { it.id !in createdGeneralIds }
            .map { PerTurnOverlay.toLogicGeneral(it) }
        val updatedCities = dirty.cities.map { PerTurnOverlay.toLogicCity(it) }
        val updatedNations = dirty.nations
            .filter { it.id !in createdNationIds }
            .map { PerTurnOverlay.toLogicNation(it) }
        val createdNations = dirty.createdNations.map { PerTurnOverlay.toLogicNation(it) }
        val createdDiplomacy = dirty.createdDiplomacy.map { PerTurnOverlay.toLogicDiplomacy(it) }
        val logEntries = dirty.logs.map { toLogRow(it, state.currentYear, state.currentMonth, state.currentPhase) }

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
                "current_phase" to state.currentPhase,
                "status" to state.status,
                "tick_seconds" to state.tickSeconds,
                "config" to state.config,
                // 매 틱 lastTurnTime 영속화 — 부재 시 재기동마다 start_time 폴백으로 월드 시작부터
                // 전 월 재생(이중 적용) 사고가 난다. WorldSnapshotLoader meta['lastTurnTime'] 의 쓰기 짝.
                "last_turn_time" to state.lastTurnTime.toString(),
                // isunited 영속화 — 천하통일/엔딩 상태가 재기동 시 유실되지 않도록 컬럼에 동기화.
                "isunited" to ((state.meta["isunited"] as? Number)?.toInt() ?: 0),
                // Persistent monotonic high-water marks for engine-assigned ids (nation.id/general.id are
                // NOT serial). Prevents cross-tick id reuse after restart.
                "max_nation_id" to ((state.meta["maxNationId"] as? Number)?.toInt() ?: 0),
                "max_general_id" to ((state.meta["maxGeneralId"] as? Number)?.toInt() ?: 0),
            ),
            updatedGenerals = updatedGenerals,
            updatedCities = updatedCities,
            updatedNations = updatedNations,
            // B1 장수생성 foundation — world DirtyState.createdGenerals → general 행 + 30 turn + 37 rank.
            createdGenerals = dirty.createdGenerals.map { toGeneralCreateRow(it) },
            createdNations = createdNations,
            createdNationTurns = dirty.nationTurnDirty,
            createdDiplomacy = createdDiplomacy,
            createdTroops = dirty.createdTroops.map { toTroopRow(it) },
            deletedTroops = dirty.deletedTroops,
            updatedTroops = dirty.troops.filter { it.id !in createdTroopIds }.map { toTroopRow(it) },
            logEntries = logEntries,
            rankWrites = rankWrites,
            kvWrites = toKvWrites(dirty.kvDirty),
            deletedGenerals = dirty.deletedGenerals,
            deletedNations = dirty.deletedNations,
            deletedNationSnapshots = deletedNationSnapshots,
            inheritanceKvWrites = dirty.inheritanceKvWrites,
            inheritanceLogInserts = dirty.inheritanceLogInserts.map {
                InheritanceLogRow(it.ownerID, state.currentYear, state.currentMonth, it.text, it.tag, it.date)
            },
            inheritanceResultInserts = dirty.inheritanceResultInserts,
            statisticInserts = dirty.statisticInserts.map {
                StatisticInsertRow(it.columns)
            },
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

    /** Engine [Troop] → infra [TroopRow]. The troop's id IS its `troop_leader` (PK). */
    private fun toTroopRow(t: Troop): TroopRow = TroopRow(troopLeader = t.id, nation = t.nationId, name = t.name)

    /**
     * 엔진 [TurnGeneral] → infra [GeneralCreateRow] (B1 장수생성 foundation). 신규 장수 INSERT의 컬럼맵을
     * 만든다 — ScenarioImporter.insertGenerals의 V1+V6 컬럼 집합을 정확히 미러링한다. `general` 행에만
     * 있고 [TurnGeneral] 스칼라 필드엔 없는 칼럼(affinity/born_year/dead_year/picture/image_server/
     * start_age/officer_city/last_turn/penalty/special2_code/personal_code/special_code)은 `meta`에서
     * 끌어온다(엔진 모델은 이들을 meta 봉투에 싣는다 — toLogicGeneral/toLogicNation의 meta-verbatim 규약).
     * meta에 없으면 ScenarioImporter와 동일한 기본값으로 떨어진다(picture='default.jpg', image_server=0,
     * affinity=null, *_code='None', officer_city=0, last_turn/penalty='{}').
     *
     * 주의: `last_turn`/`meta`/`penalty`는 jsonb 컬럼이라 [MetaJson]로 인코딩한 String을 싣는다(executor가
     * [GeneralCreateRow] 컬럼맵에서 raw json String을 꺼내 PGobject로 바인딩). `meta` 자체는 general.meta
     * jsonb로 통째로 다시 인코딩된다(envelope 키 born_year 등은 ScenarioImporter가 별도 컬럼으로 빼므로,
     * meta jsonb에는 일반적으로 별도 컬럼으로 빠지지 않은 잔여 키만 남도록 caller가 정리할 수 있다 — foundation은
     * 보수적으로 meta 전체를 그대로 직렬화한다).
     */
    internal fun toGeneralCreateRow(g: TurnGeneral): GeneralCreateRow {
        val meta = g.meta
        fun mInt(key: String, default: Int): Int = (meta[key] as? Number)?.toInt() ?: default
        fun mIntOrNull(key: String): Int? = (meta[key] as? Number)?.toInt()
        fun mStr(key: String, default: String): String = (meta[key] as? String) ?: default

        return GeneralCreateRow(
            columns = linkedMapOf(
                "id" to g.id,
                "user_id" to g.userId,
                "name" to g.name,
                "nation_id" to g.nationId,
                "city_id" to g.cityId,
                "troop_id" to g.troopId,
                "npc_state" to g.npcState,
                // affinity는 nullable 컬럼 — meta에 없으면 null(PHP는 B에서 RNG-pick; A/foundation은 null).
                "affinity" to mIntOrNull("affinity"),
                "born_year" to mInt("born_year", 180),
                "dead_year" to mInt("dead_year", 300),
                "picture" to mStr("picture", "default.jpg"),
                "image_server" to mInt("image_server", 0),
                "leadership" to g.stats.leadership,
                "strength" to g.stats.strength,
                "intel" to g.stats.intelligence,
                "politics" to g.stats.politics,
                "charm" to g.stats.charm,
                "injury" to g.injury,
                "experience" to g.experience,
                "dedication" to g.dedication,
                "officer_level" to g.officerLevel,
                "gold" to g.gold,
                "rice" to g.rice,
                "crew" to g.crew,
                "crew_type_id" to g.crewTypeId,
                "train" to g.train,
                "atmos" to g.atmos,
                // 장비/특기 코드 — role에 우선, 없으면 'None'.
                "weapon_code" to (g.role.items.weapon ?: "None"),
                "book_code" to (g.role.items.book ?: "None"),
                "horse_code" to (g.role.items.horse ?: "None"),
                "item_code" to (g.role.items.item ?: "None"),
                "turn_time" to g.turnTime.toString(),
                "age" to g.age,
                "start_age" to mInt("start_age", g.age),
                "personal_code" to (g.role.personality ?: mStr("personal_code", "None")),
                "special_code" to (g.role.specialDomestic ?: mStr("special_code", "None")),
                "special2_code" to (g.role.specialWar ?: mStr("special2_code", "None")),
                "officer_city" to mInt("officer_city", 0),
                // jsonb 컬럼 — raw json String으로 싣는다(executor가 PGobject 바인딩). meta는 통째로 재직렬화.
                "last_turn" to MetaJson.encode((meta["last_turn"] as? Map<*, *>) ?: emptyMap<String, Any?>()),
                "meta" to MetaJson.encode(meta),
                "penalty" to MetaJson.encode((meta["penalty"] as? Map<*, *>) ?: emptyMap<String, Any?>()),
            ),
        )
    }

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
        val createdTroopIds = dirty.createdTroops.map { it.id }.toSet()

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
        val logEntries = dirty.logs.map { toLogRow(it, state.currentYear, state.currentMonth, state.currentPhase) }

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
                "current_phase" to state.currentPhase,
                "status" to state.status,
                "tick_seconds" to state.tickSeconds,
                "config" to state.config,
                // 매 틱 lastTurnTime 영속화 — 부재 시 재기동마다 start_time 폴백으로 월드 시작부터
                // 전 월 재생(이중 적용) 사고가 난다. WorldSnapshotLoader meta['lastTurnTime'] 의 쓰기 짝.
                "last_turn_time" to state.lastTurnTime.toString(),
                // isunited 영속화 — 천하통일/엔딩 상태가 재기동 시 유실되지 않도록 컬럼에 동기화.
                "isunited" to ((state.meta["isunited"] as? Number)?.toInt() ?: 0),
                // Persistent monotonic high-water marks for engine-assigned ids (nation.id/general.id are
                // NOT serial). Prevents cross-tick id reuse after restart.
                "max_nation_id" to ((state.meta["maxNationId"] as? Number)?.toInt() ?: 0),
                "max_general_id" to ((state.meta["maxGeneralId"] as? Number)?.toInt() ?: 0),
            ),
            updatedGenerals = updatedGenerals,
            updatedCities = updatedCities,
            updatedNations = updatedNations,
            // B1 장수생성 foundation — world DirtyState.createdGenerals → general 행 + 30 turn + 37 rank.
            // (생성은 world-lifecycle 효과 — recorder 채널이 아니라 world DirtyState가 dirty 소스다. 생성된
            // 장수 id는 위 updatedGenerals 산출에서 createdGeneralIds로 이미 step-7 UPDATE에서 제외됐다.)
            createdGenerals = dirty.createdGenerals.map { toGeneralCreateRow(it) },
            generalAccessLogUpserts = recorder.accessLogUpserts().map {
                GeneralAccessLogWriteRow(
                    generalId = it.generalId,
                    userId = it.userId,
                    lastRefresh = it.lastRefresh,
                    refresh = it.refresh,
                    refreshTotal = it.refreshTotal,
                    refreshScore = it.refreshScore,
                    refreshScoreTotal = it.refreshScoreTotal,
                )
            },
            generalAccessLogDeletes = (recorder.accessLogDeletes() + dirty.deletedGenerals).toList(),
            createdNations = createdNations,
            createdNationTurns = dirty.nationTurnDirty,
            createdDiplomacy = createdDiplomacy,
            updatedDiplomacy = toDiplomacyUpdates(recorder.diplomacyUpdateDirty()),
            // Troop create/delete/rename are world-lifecycle effects (NOT a recorder channel) — the
            // troop table has no general-row coupling, so the world DirtyState is the dirty source.
            createdTroops = dirty.createdTroops.map { toTroopRow(it) },
            deletedTroops = dirty.deletedTroops,
            updatedTroops = dirty.troops.filter { it.id !in createdTroopIds }.map { toTroopRow(it) },
            logEntries = logEntries,
            rankWrites = toRankWrites(recorder.rankPatches()),
            kvWrites = toKvWrites(recorder.kvDirty()),
            createdMessages = recorder.createdMessages().map {
                CreatedMessageRow(it.id, it.mailbox, it.type, it.srcId, it.destId, it.time, it.validUntil, it.bodyJson)
            },
            messageInvalidates = recorder.messageInvalidates().map {
                MessageInvalidateRow(it.id, it.validUntil, it.bodyJson)
            },
            // W5d 외교 서신 — diplomacy_letter INSERT(발송, id 선할당) + UPDATE(회수/파기/대체) (recorder 채널,
            // world-state 효과 아님). INSERT-먼저-UPDATE 순서는 step-8f에서 보존된다(같은 tick의 prev→replaced
            // UPDATE 대상이 먼저 존재). letterNo는 recorder가 선할당한 id(allocatedId)를 그대로 싣는다.
            diplomacyLetterInserts = recorder.diplomacyLetterInserts().map {
                DiplomacyLetterInsertRow(it.allocatedId, it.columns)
            },
            diplomacyLetterUpdates = LinkedHashMap<Int, LinkedHashMap<String, Any?>>().apply {
                recorder.diplomacyLetterUpdates().forEach { (letterNo, columns) -> put(letterNo, LinkedHashMap(columns)) }
            },
            auctionUpserts = recorder.auctionUpserts().map { AuctionUpsertRow(it.id, it.allocatedId, it.columns) },
            auctionBidInserts = recorder.auctionBidInserts().map { AuctionBidInsertRow(it.columns) },
            bettingInserts = recorder.bettingInserts().map { BettingInsertRow(it.columns) },
            // F4 Wave C2 슬라이스 C — 게시판(회의실/기밀실) 소셜-콘텐츠 INSERT (recorder 채널, betting과
            // 동일; world-state 효과 아님). 글-먼저-댓글 순서는 step-8d에서 보존된다.
            boardPostInserts = recorder.boardPostInserts().map { BoardPostInsertRow(it.columns) },
            boardCommentInserts = recorder.boardCommentInserts().map { BoardCommentInsertRow(it.columns) },
            // F4 Wave 투표 — 설문조사(vote_poll/vote/vote_comment) INSERT (recorder 채널, board와 동일;
            // world-state 효과 아님). vote_poll-먼저-vote/vote_comment 순서는 step-8e에서 보존된다.
            votePollInserts = recorder.votePollInserts().map { VotePollInsertRow(it.columns) },
            voteInserts = recorder.voteInserts().map { VoteInsertRow(it.columns) },
            voteCommentInserts = recorder.voteCommentInserts().map { VoteCommentInsertRow(it.columns) },
            // F4 Wave 투표 — vote_poll UPDATE 채널(설문 닫기 closeOldVote / 자연 만료). INSERT 채널과 별개
            // 행(기존 행 UPDATE). recorder가 유일한 DELTA 방출자이고, 명시적으로 기록된 컬럼만 SET한다. infra
            // FlushPayload는 pollId → 컬럼맵의 LinkedHashMap을 그대로 받는다(삽입 순서 보존, diplomacy 패턴).
            votePollUpdates = LinkedHashMap<Int, LinkedHashMap<String, Any?>>().apply {
                recorder.votePollUpdates().forEach { (pollId, columns) -> put(pollId, LinkedHashMap(columns)) }
            },
            deletedGenerals = dirty.deletedGenerals,
            deletedNations = dirty.deletedNations,
            deletedNationSnapshots = deletedNationSnapshots,
            inheritanceKvWrites = recorder.inheritanceKvWrites(),
            inheritanceLogInserts = recorder.inheritanceLogInserts().map {
                InheritanceLogRow(it.ownerID, state.currentYear, state.currentMonth, it.text, it.tag, it.date)
            },
            inheritanceResultInserts = recorder.inheritanceResultInserts(),
            // W0-8 버그픽스: recorder statistic 채널 → payload 매핑이 누락돼 있었다 —
            // DaemonLoopConfig가 연경계에 기록한 checkStatistic INSERT가 flush에서 조용히 사라졌다.
            statisticInserts = recorder.statisticInserts().map { StatisticInsertRow(it.columns) },
            // W0-8 연감 채널 — LogHistory 월별 스냅샷(P0-20)의 yearbook_history UPSERT (기록은 W1-I).
            yearbookInserts = recorder.yearbookInserts().map { YearbookInsertRow(it.columns) },
            selectPoolMutations = recorder.selectPoolMutations(),
            eventInserts = recorder.eventInserts(),
            eventDeletes = recorder.eventDeletes(),
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
     * Finalize an engine [LogEntryDraft] into an infra [LogRow]: stamp the year/month/phase from world
     * state (the draft does not carry them) and uppercase `scope`/`category` to the PG enum literals
     * (`log_scope`/`log_category`) the INSERT casts to. `meta` defaults to an empty insertion-ordered
     * map when the draft carries none.
     */
    private fun toLogRow(draft: LogEntryDraft, year: Int, month: Int, phase: Int): LogRow {
        val rowYear = draft.year ?: year
        val rowMonth = draft.month ?: month
        val rowPhase = draft.phase ?: phase
        return LogRow(
        scope = scopeLiteral(draft.scope),
        category = draft.category.uppercase(),
        text = stampPhaseInLogText(draft.text, rowPhase),
        year = rowYear,
        month = rowMonth,
        phase = rowPhase,
        subType = draft.subType,
        generalId = draft.generalId,
        nationId = draft.nationId,
        userId = draft.userId,
        meta = draft.meta ?: linkedMapOf(),
    )
    }

    private fun stampPhaseInLogText(text: String, phase: Int): String {
        val phaseText = ServerClock.turnPhaseText(phase)
        val yearMonth = Regex("""^(<[^>]+>[^<]+</>)(\d+년 \d+월)(?!\s*(?:상순|중순|하순)):""")
        val monthOnly = Regex("""^(<[^>]+>[^<]+</>)(\d+월)(?!\s*(?:상순|중순|하순)):""")
        return when {
            yearMonth.containsMatchIn(text) -> yearMonth.replaceFirst(text, "\$1\$2 $phaseText:")
            monthOnly.containsMatchIn(text) -> monthOnly.replaceFirst(text, "\$1\$2 $phaseText:")
            else -> text
        }
    }

    /**
     * 엔진 [LogEntryDraft]의 문자열 scope 어휘 → PG enum `log_scope` 리터럴(SYSTEM/NATION/GENERAL/USER).
     *
     * 단순 `uppercase()`면 엔진이 쓰는 `"global"`이 `"GLOBAL"`이 되는데 enum에 GLOBAL이 없어
     * 월경계 정산 로그(ProcessIncome 봉록 지급 등) flush가 `BatchUpdateException: invalid input value
     * for enum log_scope: "GLOBAL"`로 터지고 → 틱 롤백 → prod 턴 동결이었다. PHP/common 로그 커널은
     * global history를 SYSTEM scope로 본다(`ActionLogger.pushGlobalHistoryLog` → `LogScope.SYSTEM`)
     * → 엔진의 `"global"`을 `SYSTEM`으로 번역한다.
     *
     * NOTE: betting/auction 핸들러의 scope `"action"`(+ category `"betting"|"auction"`) 위조 로그는
     * 바퀴 20/23에서 전부 제거됐다(PHP 무로그 + enum 불일치 flush 크래시). else-가지의 "미지(예: action)
     * 보존"은 방어적 잔존 — 새 비-enum scope를 들이지 말 것.
     */
    private fun scopeLiteral(scope: String): String = when (scope.lowercase()) {
        "global", "system" -> "SYSTEM"
        "nation" -> "NATION"
        "general" -> "GENERAL"
        "user" -> "USER"
        else -> scope.uppercase() // 이미 enum 리터럴이거나 미지(예: action) — 기존 동작 보존
    }
}
