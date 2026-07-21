package opensamguk.infra.persistence

import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.inheritance.InheritanceResultRow
import opensamguk.infra.seed.ScenarioImporter
import opensamguk.common.world.WorldId
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant

/**
 * The real `FlushOp` sink for the daemon write path (P1).
 *
 * Replaces the P0-B [opensamguk.engine.flush.FlushOpRecorder] stub with an actual
 * JDBC-batch executor that runs the EXACT `databaseHooks.ts:309-568` write order inside
 * ONE transaction. Design §0.1 #3: the daemon write path NEVER binds a JPA
 * `EntityManager`/persistence-context. To guarantee that structurally the [TransactionTemplate]
 * passed in MUST be built over a `DataSourceTransactionManager` (NOT the `JpaTransactionManager`
 * that `spring-boot-starter-data-jpa` autoconfig defaults to) — the executor speaks only
 * [NamedParameterJdbcTemplate] (plain JDBC), never `EntityManager.persist/merge`.
 *
 * Exact ordered contract (mirrors [opensamguk.engine.flush.DatabaseHooks]):
 *  1. world_state UPDATE (always)
 *  2. ng_old_nations UPSERT per deleted-nation snapshot
 *  3. createMany general/nation/troop/diplomacy (guarded > 0)
 *  4. deleteMany troop
 *  5. kill() delete: general, general_turn, then rank_data
 *  6. nation cascade: diplomacy, nation_turn, nation
 *  7. updates: general (excl created), city, nation upsert (excl created), troop, diplomacy
 *  8. rank_data upsert (RANK_ROWS_PER_GENERAL per target)
 *  9. log_entry createMany
 * 10. reserved_turns flush
 *
 * P1 only ever exercises steps 1, 7 (general+city UPDATE), 9 (log_entry), 10 — but the executor
 * implements the full ordered contract so later phases never reshape it. Multi-row steps use
 * `batchUpdate`; jsonb columns bind via [PGobject] with `type="jsonb"`.
 */
class JdbcFlushExecutor(
    private val jdbc: NamedParameterJdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) {
    /** Records the op sequence of the most recent [flush] (instrumentation for the IT). */
    private val lastOps = mutableListOf<FlushExecOp>()

    /** Op sequence of the last [flush] call, in execution order. */
    fun lastOps(): List<FlushExecOp> = lastOps.toList()

    fun flush(payload: FlushPayload) {
        transactionTemplate.execute {
            lastOps.clear()
            check(payload.worldStateUpdate["id"] == payload.worldId.value) {
                "FlushPayload worldStateUpdate.id must equal worldId=${payload.worldId.value}"
            }

            val (preArchiveLogs, regularLogs) = payload.logEntries.partition { it.flushBeforeArchive }
            val isUnificationFlush = payload.emperiorInserts.isNotEmpty()
            val nationHistoryLogs = regularLogs.filter { it.scope == "NATION" && it.category == "HISTORY" }
            val postEmperorLogs = regularLogs.filter { it.scope == "SYSTEM" && it.category == "HISTORY" }
            val middleLogs = regularLogs.filterNot { it in nationHistoryLogs || it in postEmperorLogs }
            val (invaderMessages, earlierMessages) = payload.createdMessages.partition {
                it.bodyJson.contains("\"action\":\"raiseInvader\"")
            }

            if (isUnificationFlush) {
                if (payload.statisticInserts.isNotEmpty()) statisticInsertMany(payload.worldId, payload.statisticInserts)
                if (nationHistoryLogs.isNotEmpty()) logEntryCreateMany(payload.worldId, nationHistoryLogs)
                if (payload.auctionUpserts.isNotEmpty()) auctionUpsertMany(payload.worldId, payload.auctionUpserts)
                if (payload.auctionBidInserts.isNotEmpty()) {
                    auctionBidInsertMany(payload.worldId, payload.auctionBidInserts)
                }
                if (payload.eventInserts.isNotEmpty()) eventInsertMany(payload.worldId, payload.eventInserts)
                if (payload.eventDeletes.isNotEmpty()) eventDeleteMany(payload.worldId, payload.eventDeletes)
                if (earlierMessages.isNotEmpty()) messageCreateMany(payload.worldId, earlierMessages)
                if (middleLogs.isNotEmpty()) logEntryCreateMany(payload.worldId, middleLogs)
                if (payload.inheritanceResultInserts.isNotEmpty()) {
                    inheritanceResultInsertMany(payload.worldId, payload.inheritanceResultInserts)
                }
                if (payload.inheritanceKvWrites.isNotEmpty()) {
                    kvWriteFlush(null, payload.inheritanceKvWrites)
                }
                if (payload.inheritanceLogInserts.isNotEmpty()) {
                    inheritanceLogInsertMany(payload.inheritanceLogInserts)
                }
            }

            worldStateUpdate(payload.worldId, payload.worldStateUpdate)
            if (isUnificationFlush && payload.kvWrites.isNotEmpty()) {
                kvWriteFlush(payload.worldId, payload.kvWrites)
            }

            if (!isUnificationFlush && preArchiveLogs.isNotEmpty()) {
                logEntryCreateMany(payload.worldId, preArchiveLogs)
            }

            if (payload.deletedNations.isNotEmpty()) {
                troopDeleteByNation(payload.worldId, payload.deletedNations)
            }
            if (!isUnificationFlush && payload.oldGeneralSnapshots.isNotEmpty()) {
                ngOldGeneralsUpsert(payload.worldId, payload.archiveServerId, payload.oldGeneralSnapshots)
            }
            if (!isUnificationFlush && payload.deletedNationSnapshots.isNotEmpty()) {
                ngOldNationsUpsert(payload.worldId, payload.archiveServerId, payload.deletedNationSnapshots)
            }

            // 3. createMany general → nation → nation_turn → diplomacy → troop (각 > 0 가드, 동결된
            //    step-3 contract 순서 general-먼저). 신규 장수 INSERT(B1 장수생성 foundation)는 general 행 +
            //    30 general_turn(휴식) + 37 rank_data(value 0)를 함께 쓴다 — ScenarioImporter.insertGenerals
            //    의 컬럼/행 모양과 정확히 일치(엔진 TurnGeneral은 컬럼맵 GeneralCreateRow로 운반돼 infra 결합 없음).
            if (payload.createdGenerals.isNotEmpty()) generalCreateMany(payload.worldId, payload.createdGenerals)
            if (payload.createdNations.isNotEmpty()) nationCreateMany(payload.worldId, payload.createdNations)
            if (payload.createdDiplomacy.isNotEmpty()) {
                diplomacyCreateMany(payload.worldId, payload.createdDiplomacy)
            }
            if (payload.createdNationTurns.isNotEmpty()) nationTurnCreateMany(payload.worldId, payload.createdNationTurns)
            if (payload.createdTroops.isNotEmpty()) troopCreateMany(payload.worldId, payload.createdTroops)
            if (payload.generalAccessLogUpserts.isNotEmpty()) {
                generalAccessLogUpsertMany(payload.worldId, payload.generalAccessLogUpserts)
            }

            // 4. deleteMany troop (by troop_leader; ExitTroop leader-disband + outright removal).
            if (payload.deletedTroops.isNotEmpty()) troopDeleteMany(payload.worldId, payload.deletedTroops)

            // 5. deleteMany general, then rank_data (both guarded on deletedGenerals > 0).
            if (payload.deletedGenerals.isNotEmpty()) {
                generalDeleteMany(payload.worldId, payload.deletedGenerals)
                rankDataDeleteMany(payload.worldId, payload.deletedGenerals)
            }
            if (payload.generalAccessLogDeletes.isNotEmpty()) {
                generalAccessLogDeleteMany(payload.worldId, payload.generalAccessLogDeletes)
            }

            // 6. nation cascade: diplomacy, nation_turn, nation (guarded on deletedNations > 0).
            if (payload.deletedNations.isNotEmpty()) {
                nationCascadeDelete(payload.worldId, payload.deletedNations)
            }

            // 7. updates: general (excl created), city, nation UPDATE (excl created).
            if (payload.updatedGenerals.isNotEmpty()) {
                generalUpdate(payload.worldId, payload.updatedGenerals)
            }
            if (payload.updatedCities.isNotEmpty()) {
                cityUpdate(payload.worldId, payload.updatedCities)
            }
            if (payload.updatedNations.isNotEmpty()) {
                nationUpdate(payload.worldId, payload.updatedNations)
            }
            if (payload.selectPoolMutations.isNotEmpty()) {
                selectPoolMutate(payload.worldId, payload.selectPoolMutations)
            }
            // 7c. troop UPDATE (rename via SetTroopName; created-this-tick troops are excluded upstream).
            if (payload.updatedTroops.isNotEmpty()) {
                troopUpdate(payload.worldId, payload.updatedTroops)
            }
            // 7d. per-command diplomacy UPDATE (T0.4) — distinct from the monthly TICK's bulk-SQL
            //     update; runs here in the per-command flush, the tick runs in its own boundary flush.
            if (payload.updatedDiplomacy.isNotEmpty()) {
                diplomacyUpdate(payload.worldId, payload.updatedDiplomacy)
            }

            // 8. rank_data UPDATE (rankVarIncrease then rankVarSet — General.php:727-744) + nation_id
            //    sync (when a general's nation changed, ALL its rank_data rows get the new nation_id).
            if (payload.rankWrites.isNotEmpty()) {
                rankDataUpdate(payload.worldId, payload.rankWrites)
            }
            if (payload.rankNationSync.isNotEmpty()) {
                rankDataNationSync(payload.worldId, payload.rankNationSync)
            }

            // 8b. auction channel (T0.7): ng_auction UPSERT (open INSERT / extend-finish UPDATE) then
            //     ng_auction_bid INSERT (INSERT-only — outbid rows are NEVER deleted, research §3).
            if (!isUnificationFlush && payload.auctionUpserts.isNotEmpty()) {
                auctionUpsertMany(payload.worldId, payload.auctionUpserts)
            }
            if (!isUnificationFlush && payload.auctionBidInserts.isNotEmpty()) {
                auctionBidInsertMany(payload.worldId, payload.auctionBidInserts)
            }
            if (payload.bettingInserts.isNotEmpty()) {
                // W0-8: PHP insertUpdate 패러티 — 동일 (general,betting,type) 재베팅은 amount 누적 UPSERT.
                bettingUpsertMany(payload.worldId, payload.bettingInserts)
            }
            if (payload.profileIconUpdates.isNotEmpty()) {
                // OPENSAM-94: general.picture/image_server 전용 컬럼 UPDATE (owner/npc 재-단언 predicate).
                profileIconUpdateMany(payload.worldId, payload.profileIconUpdates)
            }

            // 8d. 게시판 채널 (F4 C2 슬라이스 C): board_post INSERT 후 board_comment INSERT —
            //     부모-먼저-자식 순서라 댓글의 post_id FK 대상이 먼저 존재한다 (board_comment →
            //     board_post ON DELETE CASCADE). INSERT 전용 소셜 콘텐츠 (회의실/기밀실 글·댓글).
            if (payload.boardPostInserts.isNotEmpty()) {
                boardPostInsertMany(payload.worldId, payload.boardPostInserts)
            }
            if (payload.boardCommentInserts.isNotEmpty()) {
                boardCommentInsertMany(payload.worldId, payload.boardCommentInserts)
            }

            // 8e. 투표 채널 (F4 Wave 투표): vote_poll INSERT 후 vote / vote_comment INSERT —
            //     부모-먼저-자식 순서라 vote/vote_comment의 vote_id FK 대상(vote_poll)이 먼저 존재한다
            //     (둘 다 vote_poll ON DELETE CASCADE). INSERT 전용 (vote는 PHP insertIgnore →
            //     UNIQUE(vote_id,general_id) 중복은 DB가 무시).
            if (payload.votePollInserts.isNotEmpty()) {
                votePollInsertMany(payload.worldId, payload.votePollInserts)
            }
            if (payload.voteInserts.isNotEmpty()) {
                voteInsertMany(payload.worldId, payload.voteInserts)
            }
            if (payload.voteCommentInserts.isNotEmpty()) {
                voteCommentInsertMany(payload.worldId, payload.voteCommentInserts)
            }
            // vote_poll UPDATE (closeOldVote 마감) — INSERT 뒤에 와야 같은 tick에 개설+마감된 설문이 먼저
            // 존재한다(부모-먼저-갱신-나중). 빈 맵이면 no-op (diplomacy updatedDiplomacy 패턴과 동일).
            if (payload.votePollUpdates.isNotEmpty()) {
                votePollUpdateMany(payload.worldId, payload.votePollUpdates)
            }

            // 8c. mailbox channel (T0.5): message INSERT (append-additive, receiver-before-sender —
            //     the engine emits them in that order) then invalidate UPDATE (deleteMsg/sibling-sweep).
            if (!isUnificationFlush && payload.createdMessages.isNotEmpty()) {
                messageCreateMany(payload.worldId, payload.createdMessages)
            }
            if (payload.messageInvalidates.isNotEmpty()) {
                messageInvalidateMany(payload.worldId, payload.messageInvalidates)
            }

            // 8f. 외교 서신 채널 (W5d): diplomacy_letter INSERT(발송) 후 UPDATE(회수/파기/대체) —
            //     INSERT-먼저-UPDATE 순서라 같은 tick에 발송된 prev 서신을 'replaced'로 갱신하는 UPDATE 대상이
            //     먼저 존재한다(부모-먼저-갱신-나중). 빈 목록/맵이면 no-op.
            if (payload.diplomacyLetterInserts.isNotEmpty()) {
                diplomacyLetterInsertMany(payload.worldId, payload.diplomacyLetterInserts)
            }
            if (payload.diplomacyLetterUpdates.isNotEmpty()) {
                diplomacyLetterUpdateMany(payload.worldId, payload.diplomacyLetterUpdates)
            }

            if (!isUnificationFlush && payload.eventInserts.isNotEmpty()) {
                eventInsertMany(payload.worldId, payload.eventInserts)
            }
            if (!isUnificationFlush && payload.eventDeletes.isNotEmpty()) {
                eventDeleteMany(payload.worldId, payload.eventDeletes)
            }

            // 9. log_entry createMany.
            if (!isUnificationFlush && regularLogs.isNotEmpty()) {
                logEntryCreateMany(payload.worldId, regularLogs)
            }

            // 10. KV writes (nation_env int-ns + game_kv string-ns, delete-on-null) + reserved_turns
            //     flush (ring write via ReservedTurnRepository, recorded here for contract-order
            //     completeness).
            if (!isUnificationFlush && payload.kvWrites.isNotEmpty()) {
                kvWriteFlush(payload.worldId, payload.kvWrites)
            }

            // 11. Inheritance channel (T0.8) — KV writes, log inserts, result inserts.
            if (!isUnificationFlush && payload.inheritanceKvWrites.isNotEmpty()) {
                kvWriteFlush(null, payload.inheritanceKvWrites)
            }
            if (!isUnificationFlush && payload.inheritanceLogInserts.isNotEmpty()) {
                inheritanceLogInsertMany(payload.inheritanceLogInserts)
            }
            if (!isUnificationFlush && payload.inheritanceResultInserts.isNotEmpty()) {
                inheritanceResultInsertMany(payload.worldId, payload.inheritanceResultInserts)
            }
            // 12. Statistic channel (W1) — year-boundary statistic INSERT.
            if (!isUnificationFlush && payload.statisticInserts.isNotEmpty()) {
                statisticInsertMany(payload.worldId, payload.statisticInserts)
            }
            // 13. 연감 채널 (W0-8) — yearbook_history INSERT (P0-20 LogHistory 월별 스냅샷).
            if (!isUnificationFlush && payload.yearbookInserts.isNotEmpty()) {
                yearbookInsertMany(payload.worldId, payload.yearbookInserts)
            }
            if (!isUnificationFlush && payload.gameWinnerUpdates.isNotEmpty()) {
                gameWinnerUpdateMany(payload.worldId, payload.gameWinnerUpdates)
            }
            if (!isUnificationFlush && payload.emperiorInserts.isNotEmpty()) {
                emperiorInsertMany(payload.worldId, payload.emperiorInserts)
            }
            if (!isUnificationFlush && payload.hallUpserts.isNotEmpty()) {
                hallUpsertMany(payload.worldId, payload.hallUpserts)
            }
            if (isUnificationFlush) {
                if (payload.hallUpserts.isNotEmpty()) hallUpsertMany(payload.worldId, payload.hallUpserts)
                if (preArchiveLogs.isNotEmpty()) logEntryCreateMany(payload.worldId, preArchiveLogs)
                if (payload.oldGeneralSnapshots.isNotEmpty()) {
                    ngOldGeneralsUpsert(payload.worldId, payload.archiveServerId, payload.oldGeneralSnapshots)
                }
                if (payload.deletedNationSnapshots.isNotEmpty()) {
                    ngOldNationsUpsert(payload.worldId, payload.archiveServerId, payload.deletedNationSnapshots)
                }
                if (payload.gameWinnerUpdates.isNotEmpty()) {
                    gameWinnerUpdateMany(payload.worldId, payload.gameWinnerUpdates)
                }
                emperiorInsertMany(payload.worldId, payload.emperiorInserts)
                if (postEmperorLogs.isNotEmpty()) logEntryCreateMany(payload.worldId, postEmperorLogs)
                if (payload.yearbookInserts.isNotEmpty()) {
                    yearbookInsertMany(payload.worldId, payload.yearbookInserts)
                }
                if (invaderMessages.isNotEmpty()) messageCreateMany(payload.worldId, invaderMessages)
            }
            null
        }
    }

    // --- step 1 ---------------------------------------------------------------------------------

    private fun worldStateUpdate(worldId: WorldId, worldState: Map<String, Any?>) {
        val params = MapSqlParameterSource()
        params.addValue("id", worldId.value)
        params.addValue("current_year", worldState["current_year"])
        params.addValue("current_month", worldState["current_month"])
        params.addValue("current_phase", (worldState["current_phase"] as? Number)?.toInt()?.coerceIn(1, 3) ?: 1)
        params.addValue("status", worldState["status"] as? String)
        params.addValue("tick_seconds", (worldState["tick_seconds"] as? Number)?.toInt())
        params.addValue("config", (worldState["config"] as? Map<*, *>)?.let(MetaJson::encode))
        // lastTurnTime 영속화 — WorldSnapshotLoader 가 부팅 시 meta['lastTurnTime'] 을 1순위로 읽는데
        // 이 키를 쓰는 경로가 없어서 매 엔진 재기동마다 start_time 폴백 → MonthBoundaryDriver 가
        // 월드 시작부터 전 월을 재생(월수입/AI 이중 적용 + 로그 중복 INSERT)했다 (2026-06-12 s1 실증:
        // 19개월 재생, 월당 ~2분). meta 병합(||)이라 다른 meta 키는 보존된다.
        params.addValue("last_turn_time", worldState["last_turn_time"]?.toString())
        // isunited 영속화 — 천하통일/엔딩 상태가 재기동 시 유실되지 않도록 컬럼에 동기화.
        params.addValue("isunited", (worldState["isunited"] as? Number)?.toInt() ?: 0)
        // Persistent monotonic high-water marks for engine-assigned ids.
        params.addValue("max_nation_id", (worldState["max_nation_id"] as? Number)?.toInt() ?: 0)
        params.addValue("max_general_id", (worldState["max_general_id"] as? Number)?.toInt() ?: 0)
        // OPENSAM-131: optional CAS fence. When expected_world_version is present, require
        // matching (world_version, writer_epoch) and bump world_version by 1 atomically.
        val expectedVersion = (worldState["expected_world_version"] as? Number)?.toLong()
        val writerEpoch = (worldState["writer_epoch"] as? Number)?.toLong()
        val casEnabled = expectedVersion != null && writerEpoch != null
        if (casEnabled) {
            params.addValue("expected_world_version", expectedVersion)
            params.addValue("writer_epoch", writerEpoch)
        }
        val sql = if (casEnabled) {
            """
            UPDATE world_state
               SET current_year = :current_year,
                   current_month = :current_month,
                   current_phase = :current_phase,
                   status = COALESCE(:status, status),
                   tick_seconds = COALESCE(:tick_seconds, tick_seconds),
                   config = COALESCE(CAST(:config AS jsonb), config),
                   isunited = :isunited,
                   world_version = world_version + 1,
                   meta = meta || jsonb_build_object(
                       'lastTurnTime', CAST(:last_turn_time AS text),
                       'maxNationId', :max_nation_id,
                       'maxGeneralId', :max_general_id
                   ),
                   updated_at = now()
             WHERE id = :id
               AND world_version = :expected_world_version
               AND writer_epoch = :writer_epoch
            """.trimIndent()
        } else {
            """
            UPDATE world_state
               SET current_year = :current_year,
                   current_month = :current_month,
                   current_phase = :current_phase,
                   status = COALESCE(:status, status),
                   tick_seconds = COALESCE(:tick_seconds, tick_seconds),
                   config = COALESCE(CAST(:config AS jsonb), config),
                   isunited = :isunited,
                   meta = meta || jsonb_build_object(
                       'lastTurnTime', CAST(:last_turn_time AS text),
                       'maxNationId', :max_nation_id,
                       'maxGeneralId', :max_general_id
                   ),
                   updated_at = now()
             WHERE id = :id
            """.trimIndent()
        }
        val updated = jdbc.update(sql, params)
        if (casEnabled && updated == 0) {
            throw StaleWorldWriterException(
                worldId = worldId.value,
                expectedVersion = expectedVersion!!,
                writerEpoch = writerEpoch!!,
            )
        }
        check(updated == 1) { "world_state update missed configured world_id=${worldId.value}" }
        lastOps.add(FlushExecOp("world_state", FlushVerb.UPDATE, 1))
    }

    // --- step 2 ---------------------------------------------------------------------------------

    private fun ngOldNationsUpsert(
        worldId: WorldId,
        archiveServerId: String?,
        snapshots: List<Map<String, Any?>>,
    ) {
        val batch = snapshots.map { snapshot ->
            val serverId = snapshot["server_id"]?.toString()?.takeIf(String::isNotBlank)
                ?: archiveServerId?.takeIf(String::isNotBlank)
                ?: error("ng_old_nations archive write requires FlushPayload.archiveServerId")
            val nation = (snapshot["nation"] as? Number)?.toInt()
                ?: error("deleted nation snapshot is missing numeric nation id: $snapshot")
            val data = LinkedHashMap((snapshot["data"] as? Map<*, *>)?.entries?.associate { (key, value) ->
                key.toString() to value
            } ?: LinkedHashMap(snapshot).also {
                it.remove("server_id")
                it.remove("nation")
            })
            if (nation != 0) {
                data.putIfAbsent("history", historyRows(worldId, "NATION", nation))
            }
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("server_id", serverId)
                .addValue("nation", nation)
                .addValue("data", jsonb(MetaJson.encode(data)))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO ng_old_nations (world_id, server_id, nation, data)
            VALUES (:world_id, :server_id, :nation, :data)
            ON CONFLICT (world_id, server_id, nation) DO UPDATE
               SET data = EXCLUDED.data
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("ng_old_nations", FlushVerb.UPSERT, snapshots.size))
    }

    private fun ngOldGeneralsUpsert(
        worldId: WorldId,
        archiveServerId: String?,
        snapshots: List<OldGeneralArchiveRow>,
    ) {
        val batch = snapshots.map { snapshot ->
            val serverId = snapshot.serverId?.takeIf(String::isNotBlank)
                ?: archiveServerId?.takeIf(String::isNotBlank)
                ?: error("ng_old_generals archive write requires FlushPayload.archiveServerId")
            val data = LinkedHashMap(snapshot.data)
            data.putIfAbsent("history", historyRows(worldId, "GENERAL", snapshot.generalNo))
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("server_id", serverId)
                .addValue("general_no", snapshot.generalNo)
                .addValue("owner", snapshot.owner)
                .addValue("name", snapshot.name)
                .addValue("last_yearmonth", snapshot.lastYearMonth)
                .addValue("turntime", snapshot.turnTime.toString())
                .addValue("data", jsonb(MetaJson.encode(data)))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO ng_old_generals
                (world_id, server_id, general_no, owner, name, last_yearmonth, turntime, data)
            VALUES
                (:world_id, :server_id, :general_no, :owner, :name, :last_yearmonth,
                 CAST(:turntime AS timestamptz), :data)
            ON CONFLICT (world_id, server_id, general_no) DO UPDATE SET
                owner = EXCLUDED.owner,
                name = EXCLUDED.name,
                last_yearmonth = EXCLUDED.last_yearmonth,
                turntime = EXCLUDED.turntime,
                data = EXCLUDED.data
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("ng_old_generals", FlushVerb.UPSERT, snapshots.size))
    }

    private fun historyRows(worldId: WorldId, scope: String, id: Int): List<String> =
        jdbc.queryForList(
            """
            SELECT text
              FROM log_entry
             WHERE scope = CAST(:scope AS log_scope)
               AND category = 'HISTORY'
               AND world_id = :world_id
               AND ((general_id = :id AND :scope = 'GENERAL') OR (nation_id = :id AND :scope = 'NATION'))
             ORDER BY id DESC
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("scope", scope)
                .addValue("id", id),
            String::class.java,
        )

    // --- step 7: general UPDATE -----------------------------------------------------------------

    private fun generalUpdate(worldId: WorldId, generals: List<General>) {
        val batch: Array<SqlParameterSource> = generals.map { g ->
            val cols = GeneralRowMapper.toColumns(g)
            val src = MapSqlParameterSource()
            src.addValue("world_id", worldId.value)
            for ((k, v) in cols) {
                if (k == "meta" || k == "last_turn" || k == "penalty") {
                    src.addValue(k, jsonb(v as String?))
                } else {
                    src.addValue(k, v)
                }
            }
            src
        }.toTypedArray()
        // officer_city = :officer_city: ConquerCity 생존(미멸망) 분기에서 태수/군사/종사를 일반으로
        // 강등하며 officer_city=0, officer_level=1 을 쓴다(process_war.php:705-708 의 일괄 UPDATE).
        // GeneralRowMapper.toColumns 가 이미 officer_city 를 방출하고 ChangeRecorder.diffGeneral 이
        // officerCity 를 diff 하는데, 전용 typed 컬럼이 SET 절에서 빠져 있어 라이브 경로에서 누락됐다(#17).
        // (데몬은 meta 에도 officer_city 를 싣는다 — meta 쓰기는 유지하되 전용 컬럼도 일관되게 영속한다.)
        val affected = jdbc.batchUpdate(
            """
            UPDATE general
               SET nation_id = :nation_id,
                   user_id = :user_id,
                   city_id = :city_id,
                   leadership = :leadership,
                   strength = :strength,
                   intel = :intel,
                   injury = :injury,
                   experience = :experience,
                   dedication = :dedication,
                   officer_level = :officer_level,
                   gold = :gold,
                   rice = :rice,
                   crew = :crew,
                   train = :train,
                   atmos = :atmos,
                   crew_type_id = :crew_type_id,
                   troop_id = :troop_id,
                   weapon_code = :weapon_code,
                   book_code = :book_code,
                   horse_code = :horse_code,
                   item_code = :item_code,
                   npc_state = :npc_state,
                   officer_city = :officer_city,
                   last_turn = :last_turn,
                   penalty = :penalty,
                   meta = :meta,
                   politics = :politics,
                   charm = :charm,
                   age = COALESCE(:age, age),
                   turn_time = COALESCE(CAST(:turn_time AS timestamptz), turn_time),
                   updated_at = now()
             WHERE id = :id AND world_id = :world_id
            """.trimIndent(),
            batch,
        )
        requireExactlyOneAffected("general UPDATE", affected)
        lastOps.add(FlushExecOp("general", FlushVerb.UPDATE, generals.size))
    }

    // --- step 7: city UPDATE --------------------------------------------------------------------

    private fun cityUpdate(worldId: WorldId, cities: List<City>) {
        val batch: Array<SqlParameterSource> = cities.map { c ->
            val cols = CityRowMapper.toColumns(c)
            val src = MapSqlParameterSource()
            src.addValue("world_id", worldId.value)
            for ((k, v) in cols) {
                if (k == "meta") src.addValue(k, jsonb(v as String?)) else src.addValue(k, v)
            }
            src
        }.toTypedArray()
        val affected = jdbc.batchUpdate(
            """
            UPDATE city
               SET nation_id = :nation_id,
                   level = :level,
                   comm = :comm,
                   comm_max = :comm_max,
                   agri = :agri,
                   agri_max = :agri_max,
                   supply_state = :supply_state,
                   front_state = :front_state,
                   state = :state,
                   trust = :trust,
                   secu = :secu,
                   secu_max = :secu_max,
                   def = :def,
                   def_max = :def_max,
                   wall = :wall,
                   wall_max = :wall_max,
                   pop = :pop,
                   pop_max = :pop_max,
                   dead = :dead,
                   trade = :trade,
                   region = :region,
                   term = :term,
                   officer_set = :officer_set,
                   conflict = CAST(:conflict AS jsonb),
                   meta = :meta
             WHERE id = :id AND world_id = :world_id
            """.trimIndent(),
            batch,
        )
        requireExactlyOneAffected("city UPDATE", affected)
        lastOps.add(FlushExecOp("city", FlushVerb.UPDATE, cities.size))
    }

    // --- step 7: nation UPDATE ------------------------------------------------------------------

    private fun nationUpdate(worldId: WorldId, nations: List<Nation>) {
        val batch: Array<SqlParameterSource> = nations.map { n ->
            val cols = NationRowMapper.toColumns(n)
            val src = MapSqlParameterSource()
            src.addValue("world_id", worldId.value)
            for ((k, v) in cols) {
                if (k == "meta") src.addValue(k, jsonb(v as String?)) else src.addValue(k, v)
            }
            src
        }.toTypedArray()
        // power = :power: 월틱 Q4(func_gamerule.php:322-333)가 매월 nation.power 를 재산정·기록한다
        // (PostUpdateMonthly.kt:171,185 power = phpRound(rawPower * draw)). NationRowMapper.toColumns 가
        // 이미 power 를 방출하는데 SET 절에서 빠져 있어 라이브 수렴 경로에서 power 영속이 누락됐다(#9).
        val affected = jdbc.batchUpdate(
            """
            UPDATE nation
               SET name = :name,
                   color = :color,
                   capital_city_id = :capital_city_id,
                   gold = :gold,
                   rice = :rice,
                   tech = :tech,
                   level = :level,
                   type_code = :type_code,
                   power = :power,
                   meta = :meta
             WHERE id = :id AND world_id = :world_id
            """.trimIndent(),
            batch,
        )
        requireExactlyOneAffected("nation UPDATE", affected)
        // databaseHooks models nation as an UPSERT (createMany excludes these); the UPDATE op-tag is
        // recorded as UPSERT to match the contract / DatabaseHooksOrderTest expectation.
        lastOps.add(FlushExecOp("nation", FlushVerb.UPSERT, nations.size))
    }

    // --- F4 Wave C2 slice B: troop persistence -------------------------------------------------

    private fun troopCreateMany(worldId: WorldId, rows: List<TroopRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("troop_leader", r.troopLeader)
                .addValue("nation", r.nation)
                .addValue("name", r.name)
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO troop (world_id, troop_leader, nation, name)
            VALUES (:world_id, :troop_leader, :nation, :name)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("troop", FlushVerb.CREATE_MANY, rows.size))
    }

    private fun troopDeleteMany(worldId: WorldId, ids: List<Int>) {
        jdbc.update(
            "DELETE FROM troop WHERE world_id = :world_id AND troop_leader IN (:ids)",
            MapSqlParameterSource().addValue("world_id", worldId.value).addValue("ids", ids),
        )
        lastOps.add(FlushExecOp("troop", FlushVerb.DELETE_MANY, ids.size))
    }

    private fun troopDeleteByNation(worldId: WorldId, nationIds: List<Int>) {
        jdbc.update(
            "DELETE FROM troop WHERE world_id = :world_id AND nation IN (:ids)",
            MapSqlParameterSource().addValue("world_id", worldId.value).addValue("ids", nationIds),
        )
        lastOps.add(FlushExecOp("troop", FlushVerb.DELETE_MANY, nationIds.size))
    }

    private fun troopUpdate(worldId: WorldId, rows: List<TroopRow>) {
        // SetTroopName updates only `name` (`nation` is immutable for a troop's lifetime).
        val batch: Array<SqlParameterSource> = rows.map { r ->
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("troop_leader", r.troopLeader)
                .addValue("name", r.name)
        }.toTypedArray()
        val affected = jdbc.batchUpdate(
            """
            UPDATE troop
               SET name = :name
             WHERE world_id = :world_id AND troop_leader = :troop_leader
            """.trimIndent(),
            batch,
        )
        requireExactlyOneAffected("troop UPDATE", affected)
        lastOps.add(FlushExecOp("troop", FlushVerb.UPDATE, rows.size))
    }

    // --- step 7d: per-command diplomacy UPDATE (T0.4) -------------------------------------------

    /**
     * Faithful to the per-command `diplomacy` UPDATE (`che_선전포고`/`수락`/`파기`/`종전`): toggle
     * `state_code` + `term` (and `is_dead` when the patch carries it) for a single `(src, dest)` row.
     * Bidirectional transitions arrive as TWO patches (both directions). Batched. This is the DELTA
     * path — the monthly TICK's diplomacy bulk-SQL update is a separate write (P3 PostUpdateMonthly).
     */
    private fun diplomacyUpdate(worldId: WorldId, updates: List<DiplomacyUpdate>) {
        for (u in updates) {
            val src = MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("state_code", u.state)
                .addValue("term", u.term)
                .addValue("src_nation_id", u.fromNationId)
                .addValue("dest_nation_id", u.toNationId)
            if (u.dead == null) {
                val affected = jdbc.update(
                    """
                    UPDATE diplomacy SET state_code = :state_code, term = :term
                     WHERE world_id = :world_id
                       AND src_nation_id = :src_nation_id
                       AND dest_nation_id = :dest_nation_id
                    """.trimIndent(),
                    src,
                )
                check(affected == 1) { "diplomacy UPDATE affected $affected rows; expected exactly 1" }
            } else {
                src.addValue("is_dead", u.dead != 0)
                val affected = jdbc.update(
                    """
                    UPDATE diplomacy SET state_code = :state_code, term = :term, is_dead = :is_dead
                     WHERE world_id = :world_id
                       AND src_nation_id = :src_nation_id
                       AND dest_nation_id = :dest_nation_id
                    """.trimIndent(),
                    src,
                )
                check(affected == 1) { "diplomacy UPDATE affected $affected rows; expected exactly 1" }
            }
        }
        lastOps.add(FlushExecOp("diplomacy", FlushVerb.UPDATE, updates.size))
    }

    // --- step 3: general createMany (B1 장수생성 foundation) -------------------------------------

    /**
     * 신규 장수 INSERT (B1 장수생성 foundation). 장수 1명마다 3개 테이블 행을 쓴다 —
     * `ScenarioImporter.insertGenerals`/`insertGeneralTurns`/`insertRankData`와 byte-faithful 일치:
     *  1. `general` 행 — V1+V6 컬럼 38개. `id`는 명시(integer PK, NOT serial). jsonb 컬럼
     *     (`last_turn`/`meta`/`penalty`)은 [PGobject]로 바인딩. `affinity`는 nullable.
     *  2. `general_turn` 30행 — turn_idx 0..29, 모두 action_code/brief='휴식', arg='{}'
     *     (ScenarioImporter.MAX_GENERAL_TURNS = 30 ring buffer 풀시드).
     *  3. `rank_data` 37행 — RANK_COLUMNS 전체, value=0, nation_id=0 (장수 생성 시 미리 시드 →
     *     이후 rankVarIncrease/Set UPDATE의 대상; ScenarioImporter.insertRankData와 동일).
     */
    private fun generalCreateMany(worldId: WorldId, rows: List<GeneralCreateRow>) {
        // 1. general 행 INSERT (ScenarioImporter.insertGenerals 컬럼/순서 verbatim).
        val generalBatch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("id", c["id"])
                .addValue("user_id", c["user_id"])
                .addValue("name", c["name"])
                .addValue("nation_id", c["nation_id"])
                .addValue("city_id", c["city_id"])
                .addValue("troop_id", c["troop_id"])
                .addValue("npc_state", c["npc_state"])
                .addValue("affinity", c["affinity"])
                .addValue("born_year", c["born_year"])
                .addValue("dead_year", c["dead_year"])
                .addValue("picture", c["picture"])
                .addValue("image_server", c["image_server"])
                .addValue("leadership", c["leadership"])
                .addValue("strength", c["strength"])
                .addValue("intel", c["intel"])
                .addValue("politics", c["politics"])
                .addValue("charm", c["charm"])
                .addValue("injury", c["injury"])
                .addValue("experience", c["experience"])
                .addValue("dedication", c["dedication"])
                .addValue("officer_level", c["officer_level"])
                .addValue("gold", c["gold"])
                .addValue("rice", c["rice"])
                .addValue("crew", c["crew"])
                .addValue("crew_type_id", c["crew_type_id"])
                .addValue("train", c["train"])
                .addValue("atmos", c["atmos"])
                .addValue("weapon_code", c["weapon_code"])
                .addValue("book_code", c["book_code"])
                .addValue("horse_code", c["horse_code"])
                .addValue("item_code", c["item_code"])
                .addValue("turn_time", c["turn_time"]?.toString())
                .addValue("age", c["age"])
                .addValue("start_age", c["start_age"])
                .addValue("personal_code", c["personal_code"])
                .addValue("special_code", c["special_code"])
                .addValue("special2_code", c["special2_code"])
                .addValue("officer_city", c["officer_city"])
                .addValue("last_turn", jsonb(c["last_turn"] as? String))
                .addValue("meta", jsonb(c["meta"] as? String))
                .addValue("penalty", jsonb(c["penalty"] as? String))
        }.toTypedArray()
        val generalAffected = jdbc.batchUpdate(
            """
            INSERT INTO general
                (world_id, id, user_id, name, nation_id, city_id, troop_id, npc_state, affinity,
                 born_year, dead_year, picture, image_server,
                 leadership, strength, intel, injury, experience, dedication, officer_level,
                 gold, rice, crew, crew_type_id, train, atmos,
                 weapon_code, book_code, horse_code, item_code,
                 turn_time, age, start_age, personal_code, special_code, special2_code, officer_city,
                 last_turn, meta, penalty,
                 politics, charm)
            VALUES
                (:world_id, :id, :user_id, :name, :nation_id, :city_id, :troop_id, :npc_state, :affinity,
                 :born_year, :dead_year, :picture, :image_server,
                 :leadership, :strength, :intel, :injury, :experience, :dedication, :officer_level,
                 :gold, :rice, :crew, :crew_type_id, :train, :atmos,
                 :weapon_code, :book_code, :horse_code, :item_code,
                 CAST(:turn_time AS timestamptz), :age, :start_age, :personal_code, :special_code,
                 :special2_code, :officer_city,
                 :last_turn, :meta, :penalty,
                 :politics, :charm)
            """.trimIndent(),
            generalBatch,
        )
        requireExactlyOneAffected("general INSERT", generalAffected)
        lastOps.add(FlushExecOp("general", FlushVerb.CREATE_MANY, rows.size))

        // 2. general_turn 30행/장수 — turn_idx 0..29, 모두 휴식 (ScenarioImporter.insertGeneralTurns verbatim).
        //    링 용량/rank 컬럼 목록은 ScenarioImporter의 정본 상수를 재사용(같은 :infra 모듈, 패키지만 다름) —
        //    세 번째 사본을 만들지 않아 시드 경로와 생성-flush 경로가 절대 드리프트하지 않는다.
        val ring = ScenarioImporter.MAX_GENERAL_TURNS
        val turnBatch = ArrayList<SqlParameterSource>(rows.size * ring)
        for (r in rows) {
            val id = r.columns["id"]
            for (idx in 0 until ring) {
                turnBatch.add(
                    MapSqlParameterSource()
                        .addValue("world_id", worldId.value)
                        .addValue("general_id", id)
                        .addValue("turn_idx", idx),
                )
            }
        }
        val generalTurnAffected = jdbc.batchUpdate(
            """
            INSERT INTO general_turn (world_id, general_id, turn_idx, action_code, arg, brief)
            VALUES (:world_id, :general_id, :turn_idx, '휴식', '{}'::jsonb, '휴식')
            """.trimIndent(),
            turnBatch.toTypedArray(),
        )
        requireExactlyOneAffected("general_turn INSERT", generalTurnAffected)
        lastOps.add(FlushExecOp("general_turn", FlushVerb.CREATE_MANY, rows.size * ring))

        // 3. rank_data 37행/장수 — RANK_COLUMNS 전체, value=0, nation_id=0 (insertRankData verbatim).
        val rankColumns = ScenarioImporter.RANK_COLUMNS
        val rankBatch = ArrayList<SqlParameterSource>(rows.size * rankColumns.size)
        for (r in rows) {
            val id = r.columns["id"]
            for (type in rankColumns) {
                rankBatch.add(
                    MapSqlParameterSource()
                        .addValue("world_id", worldId.value)
                        .addValue("general_id", id)
                        .addValue("type", type),
                )
            }
        }
        val rankAffected = jdbc.batchUpdate(
            """
            INSERT INTO rank_data (world_id, nation_id, general_id, type, value)
            VALUES (:world_id, 0, :general_id, :type, 0)
            """.trimIndent(),
            rankBatch.toTypedArray(),
        )
        requireExactlyOneAffected("rank_data INSERT", rankAffected)
        lastOps.add(FlushExecOp("rank_data", FlushVerb.CREATE_MANY, rows.size * rankColumns.size))
    }

    private fun generalAccessLogUpsertMany(worldId: WorldId, rows: List<GeneralAccessLogWriteRow>) {
        val batch = rows.map { row ->
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("general_id", row.generalId)
                .addValue("user_id", row.userId)
                .addValue("last_refresh", row.lastRefresh?.toString())
                .addValue("refresh", row.refresh)
                .addValue("refresh_total", row.refreshTotal)
                .addValue("refresh_score", row.refreshScore)
                .addValue("refresh_score_total", row.refreshScoreTotal)
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO general_access_log
                (world_id, general_id, user_id, last_refresh, refresh, refresh_total, refresh_score,
                 refresh_score_total)
            VALUES
                (:world_id, :general_id, :user_id, CAST(:last_refresh AS timestamptz), :refresh, :refresh_total,
                 :refresh_score, :refresh_score_total)
            ON CONFLICT (world_id, general_id) DO UPDATE SET
                user_id = EXCLUDED.user_id,
                last_refresh = EXCLUDED.last_refresh,
                refresh = EXCLUDED.refresh,
                refresh_total = EXCLUDED.refresh_total,
                refresh_score = EXCLUDED.refresh_score,
                refresh_score_total = EXCLUDED.refresh_score_total
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("general_access_log", FlushVerb.UPSERT, rows.size))
    }

    // --- step 3: nation / nation_turn createMany ------------------------------------------------

    private fun nationCreateMany(worldId: WorldId, nations: List<Nation>) {
        val batch: Array<SqlParameterSource> = nations.map { n ->
            val cols = NationRowMapper.toColumns(n)
            val src = MapSqlParameterSource()
            src.addValue("world_id", worldId.value)
            for ((k, v) in cols) {
                if (k == "meta") src.addValue(k, jsonb(v as String?)) else src.addValue(k, v)
            }
            src
        }.toTypedArray()
        // power: nation.power 컬럼 포함(NationRowMapper.toColumns 와 짝). 건국 시점 power 는 보통 0
        // (V8 DEFAULT 0)이나, 0 아닌 power 를 실어 생성하는 경우(향후) 누락 없이 영속하도록 명시한다(#9).
        val nationAffected = jdbc.batchUpdate(
            """
            INSERT INTO nation (world_id, id, name, color, capital_city_id, gold, rice, tech, level, type_code, power, meta)
            VALUES (:world_id, :id, :name, :color, :capital_city_id, :gold, :rice, :tech, :level, :type_code, :power, :meta)
            """.trimIndent(),
            batch,
        )
        requireExactlyOneAffected("nation INSERT", nationAffected)
        lastOps.add(FlushExecOp("nation", FlushVerb.CREATE_MANY, nations.size))
    }

    private fun nationTurnCreateMany(worldId: WorldId, turns: List<NationTurn>) {
        val batch: Array<SqlParameterSource> = turns.map { t ->
            val cols = NationTurnRowMapper.toColumns(t)
            val src = MapSqlParameterSource()
            src.addValue("world_id", worldId.value)
            for ((k, v) in cols) {
                if (k == "arg") src.addValue(k, jsonb(v as String?)) else src.addValue(k, v)
            }
            src
        }.toTypedArray()
        val nationTurnAffected = jdbc.batchUpdate(
            """
            INSERT INTO nation_turn (world_id, nation_id, officer_level, turn_idx, action_code, arg, brief)
            VALUES (:world_id, :nation_id, :officer_level, :turn_idx, :action_code, :arg, :brief)
            """.trimIndent(),
            batch,
        )
        requireExactlyOneAffected("nation_turn INSERT", nationTurnAffected)
        lastOps.add(FlushExecOp("nation_turn", FlushVerb.CREATE_MANY, turns.size))
    }

    /**
     * Step-3 created-diplomacy createMany (`che_거병.php:114-138`): the founding command wires the new
     * nation to every existing nation with a bidirectional `(me, you, state=2, term=0)` pair. Inserted
     * after the nation createMany (the FK target exists) and before nation_turn, matching the frozen
     * step-3 contract order (general → nation → troop → diplomacy). Maps via [DiplomacyRowMapper].
     */
    private fun diplomacyCreateMany(worldId: WorldId, diplomacy: List<Diplomacy>) {
        val batch: Array<SqlParameterSource> = diplomacy.map { d ->
            val cols = DiplomacyRowMapper.toColumns(d)
            val src = MapSqlParameterSource()
            src.addValue("world_id", worldId.value)
            for ((k, v) in cols) src.addValue(k, v)
            src
        }.toTypedArray()
        val affected = jdbc.batchUpdate(
            """
            INSERT INTO diplomacy (world_id, src_nation_id, dest_nation_id, state_code, term)
            VALUES (:world_id, :src_nation_id, :dest_nation_id, :state_code, :term)
            """.trimIndent(),
            batch,
        )
        requireExactlyOneAffected("diplomacy INSERT", affected)
        lastOps.add(FlushExecOp("diplomacy", FlushVerb.CREATE_MANY, diplomacy.size))
    }

    // --- step 5: deleteMany general, then rank_data ---------------------------------------------

    private fun generalDeleteMany(worldId: WorldId, ids: List<Int>) {
        val affected = jdbc.batchUpdate(
            "DELETE FROM general WHERE world_id = :world_id AND id = :id",
            ids.map { id ->
                MapSqlParameterSource()
                    .addValue("world_id", worldId.value)
                    .addValue("id", id)
            }.toTypedArray(),
        )
        requireExactlyOneAffected("general DELETE", affected)
        lastOps.add(FlushExecOp("general", FlushVerb.DELETE_MANY, ids.size))
        jdbc.update(
            "DELETE FROM general_turn WHERE world_id = :world_id AND general_id IN (:ids)",
            MapSqlParameterSource().addValue("world_id", worldId.value).addValue("ids", ids),
        )
        lastOps.add(FlushExecOp("general_turn", FlushVerb.DELETE_MANY, ids.size))
    }

    private fun rankDataDeleteMany(worldId: WorldId, generalIds: List<Int>) {
        jdbc.update(
            "DELETE FROM rank_data WHERE world_id = :world_id AND general_id IN (:ids)",
            MapSqlParameterSource().addValue("world_id", worldId.value).addValue("ids", generalIds),
        )
        lastOps.add(FlushExecOp("rank_data", FlushVerb.DELETE_MANY, generalIds.size))
    }

    private fun generalAccessLogDeleteMany(worldId: WorldId, generalIds: List<Int>) {
        jdbc.update(
            "DELETE FROM general_access_log WHERE world_id = :world_id AND general_id IN (:ids)",
            MapSqlParameterSource().addValue("world_id", worldId.value).addValue("ids", generalIds),
        )
        lastOps.add(FlushExecOp("general_access_log", FlushVerb.DELETE_MANY, generalIds.size))
    }

    // --- step 6: nation cascade -----------------------------------------------------------------

    private fun nationCascadeDelete(worldId: WorldId, nationIds: List<Int>) {
        val affected = jdbc.batchUpdate(
            "DELETE FROM nation WHERE world_id = :world_id AND id = :id",
            nationIds.map { id ->
                MapSqlParameterSource()
                    .addValue("world_id", worldId.value)
                    .addValue("id", id)
            }.toTypedArray(),
        )
        requireExactlyOneAffected("nation DELETE", affected)
        lastOps.add(FlushExecOp("nation", FlushVerb.DELETE_MANY, nationIds.size))
        jdbc.update(
            "DELETE FROM nation_turn WHERE world_id = :world_id AND nation_id IN (:ids)",
            MapSqlParameterSource().addValue("world_id", worldId.value).addValue("ids", nationIds),
        )
        lastOps.add(FlushExecOp("nation_turn", FlushVerb.DELETE_MANY, nationIds.size))
        jdbc.update(
            """
            DELETE FROM diplomacy
             WHERE world_id = :world_id
               AND (src_nation_id IN (:ids) OR dest_nation_id IN (:ids))
            """.trimIndent(),
            MapSqlParameterSource().addValue("world_id", worldId.value).addValue("ids", nationIds),
        )
        lastOps.add(FlushExecOp("diplomacy", FlushVerb.DELETE_MANY, nationIds.size))
        jdbc.update(
            "DELETE FROM nation_env WHERE world_id = :world_id AND namespace IN (:ids)",
            MapSqlParameterSource().addValue("world_id", worldId.value).addValue("ids", nationIds),
        )
        lastOps.add(FlushExecOp("nation_env", FlushVerb.DELETE_MANY, nationIds.size))
    }

    // --- step 8: rank_data UPDATE (increment then set) + nation_id sync -------------------------

    /**
     * Faithful to `General.php:727-744`: flush the buffered rank maps as `UPDATE rank_data` —
     * `rankVarIncrease` first (`value = value + n`), then `rankVarSet` (`value = n`). The 37 rows per
     * general are pre-seeded at general creation, so this is an UPDATE (never an UPSERT). Caller
     * orders [RankWrite]s increments-before-sets to match the PHP map iteration; the op-tag is one
     * `rank_data UPDATE` covering all affected `(general, type)` rows.
     */
    private fun rankDataUpdate(worldId: WorldId, writes: List<RankWrite>) {
        for (w in writes) {
            val (sql, value) = when (val op = w.op) {
                is RankFlushOp.Increment -> "value = value + :value" to op.value
                is RankFlushOp.Set -> "value = :value" to op.value
            }
            val affected = jdbc.update(
                "UPDATE rank_data SET $sql WHERE world_id = :world_id AND general_id = :general_id AND type = :type",
                MapSqlParameterSource()
                    .addValue("world_id", worldId.value)
                    .addValue("value", value)
                    .addValue("general_id", w.generalId)
                    .addValue("type", w.type),
            )
            check(affected == 1) { "rank_data UPDATE affected $affected rows; expected exactly 1" }
        }
        lastOps.add(FlushExecOp("rank_data", FlushVerb.UPDATE, writes.size))
    }

    /**
     * The nation_id-sync denormalization (`General.php:718-723`): when a general's `nation` changed,
     * ALL of that general's rank_data rows get `nation_id := new`. One UPDATE per affected general.
     */
    private fun rankDataNationSync(worldId: WorldId, syncs: List<RankNationSync>) {
        for (s in syncs) {
            val affected = jdbc.update(
                "UPDATE rank_data SET nation_id = :nation_id WHERE world_id = :world_id AND general_id = :general_id",
                MapSqlParameterSource()
                    .addValue("world_id", worldId.value)
                    .addValue("nation_id", s.nationId)
                    .addValue("general_id", s.generalId),
            )
            check(affected > 0) { "rank_data nation sync missed world/general ${worldId.value}/${s.generalId}" }
        }
        lastOps.add(FlushExecOp("rank_data", FlushVerb.UPDATE, syncs.size))
    }

    // --- step 10: KV write (delete-on-null) — int-ns nation_env AND string-ns game_kv -----------

    /**
     * Flush the KV write-set (`KVStorage.php` delete-on-null). A [KvWrite] now carries its target
     * `table`: `nation_env` (int namespace = nation id) routes to the V3 table; every string namespace
     * (`game_env`, `betting`, `inheritance_{id}`, …) routes to the V7 `game_kv` table keyed by the
     * `table` discriminator. A `null` value DELETEs the row; a non-null value UPSERTs the
     * [MetaJson]-encoded jsonb (bare int for `next_execute_*`, object for `turn_last_{officer_level}`,
     * etc.). Every value is encoded here, matching `KVStorage::setDBValue`'s unconditional
     * `Json::encode($value)` call. Callers pass structured values rather than pre-encoded JSON.
     */
    private fun kvWriteFlush(worldId: WorldId?, writes: List<KvWrite>) {
        if (worldId == null) {
            check(writes.all { it.table == "inheritance" }) {
                "global KV flush accepts only table=inheritance"
            }
        } else {
            check(writes.none { it.table == "inheritance" }) {
                "world-scoped KV flush cannot write global inheritance rows"
            }
        }
        for (w in writes) {
            when (w.table) {
                "nation_env" -> nationEnvKvWrite(requireNotNull(worldId), w)
                else -> gameKvWrite(worldId, w)
            }
        }
        lastOps.add(FlushExecOp("kv", FlushVerb.UPSERT, writes.size))
    }

    /** int-namespace store (V3 `nation_env`): namespace is the nation id (parsed from the string). */
    private fun nationEnvKvWrite(worldId: WorldId, w: KvWrite) {
        val ns = w.namespace.toInt()
        if (w.value == null) {
            jdbc.update(
                "DELETE FROM nation_env WHERE world_id = :world_id AND namespace = :namespace AND key = :key",
                MapSqlParameterSource()
                    .addValue("world_id", worldId.value)
                    .addValue("namespace", ns)
                    .addValue("key", w.key),
            )
        } else {
            jdbc.update(
                """
                INSERT INTO nation_env (world_id, namespace, key, value)
                VALUES (:world_id, :namespace, :key, :value)
                ON CONFLICT (world_id, namespace, key) DO UPDATE SET value = EXCLUDED.value
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("world_id", worldId.value)
                    .addValue("namespace", ns)
                    .addValue("key", w.key)
                    .addValue("value", jsonb(encodeKvValue(w.value))),
            )
        }
    }

    /** string-namespace store (V7 `game_kv`): keyed by `(table, namespace, key)`. */
    private fun gameKvWrite(worldId: WorldId?, w: KvWrite) {
        val params = MapSqlParameterSource()
            .addValue("world_id", worldId?.value)
            .addValue("tbl", w.table)
            .addValue("namespace", w.namespace)
            .addValue("key", w.key)
        if (w.value == null) {
            val worldPredicate = if (worldId == null) "world_id IS NULL" else "world_id = :world_id"
            jdbc.update(
                """DELETE FROM game_kv WHERE $worldPredicate AND "table" = :tbl AND namespace = :namespace AND key = :key""",
                params,
            )
        } else {
            params.addValue("value", jsonb(encodeKvValue(w.value)))
            val conflictTarget = if (worldId == null) {
                """("table", namespace, key) WHERE "table" = 'inheritance' AND world_id IS NULL"""
            } else {
                """(world_id, "table", namespace, key) WHERE "table" <> 'inheritance' AND world_id IS NOT NULL"""
            }
            jdbc.update(
                """
                INSERT INTO game_kv (world_id, "table", namespace, key, value)
                VALUES (:world_id, :tbl, :namespace, :key, :value)
                ON CONFLICT $conflictTarget DO UPDATE SET value = EXCLUDED.value
                """.trimIndent(),
                params,
            )
        }
    }

    private fun encodeKvValue(value: Any?): String = MetaJson.encode(value)

    // --- step 9: log_entry createMany -----------------------------------------------------------

    private fun logEntryCreateMany(worldId: WorldId, logs: List<LogRow>) {
        val batch: Array<SqlParameterSource> = logs.map { l ->
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("scope", l.scope)
                .addValue("category", l.category)
                .addValue("sub_type", l.subType)
                .addValue("year", l.year)
                .addValue("month", l.month)
                .addValue("phase", l.phase.coerceIn(1, 3))
                .addValue("text", l.text)
                .addValue("general_id", l.generalId)
                .addValue("nation_id", l.nationId)
                .addValue("user_id", l.userId)
                .addValue("meta", jsonb(MetaJson.encode(l.meta)))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO log_entry
                (world_id, scope, category, sub_type, year, month, phase, text, general_id, nation_id, user_id, meta)
            VALUES
                (:world_id, CAST(:scope AS log_scope), CAST(:category AS log_category), :sub_type,
                 :year, :month, :phase, :text, :general_id, :nation_id, :user_id, :meta)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("log_entry", FlushVerb.CREATE_MANY, logs.size))
    }

    // --- step 8b: auction channel (T0.7) -------------------------------------------------------

    /**
     * UPSERT the `ng_auction` rows. An INSERT (open) carries [AuctionUpsertRow.allocatedId] (the
     * pre-assigned in-memory id, so bids reference it before flush); an UPDATE (extend/finish/shrink)
     * carries [AuctionUpsertRow.id]. `type`/`req_resource` bind through `CAST(... AS ng_auction_*)`,
     * `open_date`/`close_date` through `CAST(... AS timestamptz)`, `detail` is a raw json String.
     */
    private fun auctionUpsertMany(worldId: WorldId, rows: List<AuctionUpsertRow>) {
        for (r in rows) {
            val c = r.columns
            val src = MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("type", c["type"])
                .addValue("finished", c["finished"])
                .addValue("target", c["target"])
                .addValue("host_general_id", c["host_general_id"])
                .addValue("req_resource", c["req_resource"])
                .addValue("open_date", c["open_date"]?.toString())
                .addValue("close_date", c["close_date"]?.toString())
                .addValue("detail", jsonb(c["detail"] as? String))
            if (r.id == null) {
                src.addValue("id", r.allocatedId)
                val affected = jdbc.update(
                    """
                    INSERT INTO ng_auction
                        (world_id, id, type, finished, target, host_general_id, req_resource,
                         open_date, close_date, detail)
                    VALUES (:world_id, :id, CAST(:type AS ng_auction_type), :finished, :target, :host_general_id,
                            CAST(:req_resource AS ng_auction_resource), CAST(:open_date AS timestamptz),
                            CAST(:close_date AS timestamptz), :detail)
                    """.trimIndent(),
                    src,
                )
                check(affected == 1) { "ng_auction INSERT affected $affected rows; expected exactly 1" }
            } else {
                src.addValue("id", r.id)
                val affected = jdbc.update(
                    """
                    UPDATE ng_auction SET type = CAST(:type AS ng_auction_type), finished = :finished, target = :target,
                        host_general_id = :host_general_id, req_resource = CAST(:req_resource AS ng_auction_resource),
                        open_date = CAST(:open_date AS timestamptz), close_date = CAST(:close_date AS timestamptz), detail = :detail
                     WHERE world_id = :world_id AND id = :id
                    """.trimIndent(),
                    src,
                )
                check(affected == 1) { "ng_auction UPDATE affected $affected rows; expected exactly 1" }
            }
        }
        lastOps.add(FlushExecOp("ng_auction", FlushVerb.UPSERT, rows.size))
    }

    /** INSERT the `ng_auction_bid` rows (INSERT-only; outbid rows persist). `aux` is a raw json String. */
    private fun auctionBidInsertMany(worldId: WorldId, rows: List<AuctionBidInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("auction_id", c["auction_id"])
                .addValue("owner", c["owner"])
                .addValue("general_id", c["general_id"])
                .addValue("amount", c["amount"])
                .addValue("date", c["date"]?.toString())
                .addValue("aux", jsonb(c["aux"] as? String))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO ng_auction_bid (world_id, auction_id, owner, general_id, amount, date, aux)
            VALUES (:world_id, :auction_id, :owner, :general_id, :amount, CAST(:date AS timestamptz), :aux)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("ng_auction_bid", FlushVerb.CREATE_MANY, rows.size))
    }

    /**
     * `ng_betting` UPSERT (P6 베팅 — W0-8에서 INSERT 전용 → upsert로 확장, P0-07 flush 측).
     *
     * PHP 정본 Betting::bet(Betting.php:160-164)은
     * `insertUpdate('ng_betting', row, ['amount' => sqleval('amount + %i', $amount)])` —
     * UNIQUE(general_id, betting_id, betting_type)(V7, PHP by_general 인덱스 동일) 충돌 시
     * amount만 누적하고 user_id 등 나머지 컬럼은 기존 행을 유지한다. 동일 키 재베팅이 행을
     * 중복 적재하던 INSERT-only 결함의 정본 경로. (검증 체인 포팅은 W1-C PlaceBetHandler 소관.)
     */
    private fun bettingUpsertMany(worldId: WorldId, rows: List<BettingInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("betting_id", c["betting_id"])
                .addValue("general_id", c["general_id"])
                .addValue("user_id", c["user_id"])
                .addValue("betting_type", c["betting_type"])
                .addValue("amount", c["amount"])
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO ng_betting (world_id, betting_id, general_id, user_id, betting_type, amount)
            VALUES (:world_id, :betting_id, :general_id, :user_id, :betting_type, :amount)
            ON CONFLICT (world_id, general_id, betting_id, betting_type)
                DO UPDATE SET amount = ng_betting.amount + EXCLUDED.amount
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("ng_betting", FlushVerb.UPSERT, rows.size))
    }

    /**
     * OPENSAM-94 프로필 아이콘 sync — `general.picture`/`image_server` 전용 표시-컬럼 UPDATE.
     *
     * generalUpdate SET 절이 picture/image_server를 방출하지 않으므로(officer_city #17류 typed-컬럼 누락)
     * 전용 채널로 영속한다. WHERE의 `user_id = :user_id AND npc_state = 0`은 소유권/NPC predicate를
     * SQL 레벨에서 재-단언한다 — 핸들러의 owner+npc 선택과 함께 이중 방어(잘못된 id로 NPC/타 소유
     * 행을 덮어쓸 수 없다). 값이 동일한 재적용은 무해(idempotent).
     */
    private fun profileIconUpdateMany(worldId: WorldId, rows: List<ProfileIconUpdateRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("id", c["id"])
                .addValue("user_id", c["user_id"])
                .addValue("picture", c["picture"])
                .addValue("image_server", c["image_server"])
        }.toTypedArray()
        val affected = jdbc.batchUpdate(
            """
            UPDATE general
               SET picture = :picture,
                   image_server = :image_server,
                   updated_at = now()
             WHERE id = :id AND world_id = :world_id AND user_id = :user_id AND npc_state = 0
            """.trimIndent(),
            batch,
        )
        requireExactlyOneAffected("profile icon UPDATE", affected)
        lastOps.add(FlushExecOp("general", FlushVerb.UPDATE, rows.size))
    }

    // --- step 8d: 게시판 채널 (F4 C2 슬라이스 C, 회의실/기밀실) ----------------------------------

    /**
     * `board_post` 행 INSERT (INSERT 전용; `id`는 SERIAL — 생략해 DB가 부여).
     * W0-8: author_icon(V15, PHP board.author_icon VARCHAR(128) NULL — j_board_article_add.php:65,73)
     * 동반 — columns에 키가 없으면 NULL 바인딩(아이콘 없는 글, PHP NULL 패러티). 채움은 W1-D BoardHandler 소관.
     */
    private fun boardPostInsertMany(worldId: WorldId, rows: List<BoardPostInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("nation_id", c["nation_id"])
                .addValue("is_secret", c["is_secret"])
                .addValue("author_general_id", c["author_general_id"])
                .addValue("author_name", c["author_name"])
                .addValue("author_icon", c["author_icon"])
                .addValue("title", c["title"])
                .addValue("content_html", c["content_html"])
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO board_post
                (world_id, nation_id, is_secret, author_general_id, author_name, author_icon, title, content_html)
            VALUES
                (:world_id, :nation_id, :is_secret, :author_general_id, :author_name, :author_icon,
                 :title, :content_html)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("board_post", FlushVerb.CREATE_MANY, rows.size))
    }

    /** `board_comment` 행 INSERT (INSERT 전용; `id`는 SERIAL — 생략해 DB가 부여). */
    private fun boardCommentInsertMany(worldId: WorldId, rows: List<BoardCommentInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("post_id", c["post_id"])
                .addValue("nation_id", c["nation_id"])
                .addValue("is_secret", c["is_secret"])
                .addValue("author_general_id", c["author_general_id"])
                .addValue("author_name", c["author_name"])
                .addValue("content_text", c["content_text"])
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO board_comment
                (world_id, post_id, nation_id, is_secret, author_general_id, author_name, content_text)
            VALUES
                (:world_id, :post_id, :nation_id, :is_secret, :author_general_id, :author_name, :content_text)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("board_comment", FlushVerb.CREATE_MANY, rows.size))
    }

    // --- step 8e: 투표 채널 (F4 Wave 투표, 설문조사) ---------------------------------------------

    /**
     * `vote_poll` 행 INSERT (INSERT 전용; `id`는 SERIAL — 생략해 DB가 부여). `options`는 jsonb
     * (이미 인코딩된 JSON 배열 문자열); `start_at`/`end_at`는 timestamptz (문자열 캐스트, end_at은 nullable).
     */
    private fun votePollInsertMany(worldId: WorldId, rows: List<VotePollInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("title", c["title"])
                .addValue("body", c["body"])
                .addValue("options", jsonb(c["options"] as? String))
                .addValue("multiple_options", c["multiple_options"])
                .addValue("reveal_mode", c["reveal_mode"])
                .addValue("opener_general_id", c["opener_general_id"])
                .addValue("opener_name", c["opener_name"])
                .addValue("start_at", c["start_at"]?.toString())
                .addValue("end_at", c["end_at"]?.toString())
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO vote_poll
                (world_id, title, body, options, multiple_options, reveal_mode, opener_general_id,
                 opener_name, start_at, end_at)
            VALUES
                (:world_id, :title, :body, :options, :multiple_options, :reveal_mode,
                 :opener_general_id, :opener_name, CAST(:start_at AS timestamptz),
                 CAST(:end_at AS timestamptz))
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("vote_poll", FlushVerb.CREATE_MANY, rows.size))
    }

    /**
     * `vote` 행 INSERT (PHP `insertIgnore` → `ON CONFLICT (vote_id, general_id) DO NOTHING` —
     * UNIQUE 중복은 무시한다). `selection`은 jsonb (이미 인코딩된 정렬된 인덱스 배열 문자열).
     */
    private fun voteInsertMany(worldId: WorldId, rows: List<VoteInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("vote_id", c["vote_id"])
                .addValue("general_id", c["general_id"])
                .addValue("nation_id", c["nation_id"])
                .addValue("selection", jsonb(c["selection"] as? String))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO vote (world_id, vote_id, general_id, nation_id, selection)
            VALUES (:world_id, :vote_id, :general_id, :nation_id, :selection)
            ON CONFLICT (world_id, vote_id, general_id) DO NOTHING
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("vote", FlushVerb.CREATE_MANY, rows.size))
    }

    /** `vote_comment` 행 INSERT (INSERT 전용; `id`는 SERIAL — 생략해 DB가 부여). */
    private fun voteCommentInsertMany(worldId: WorldId, rows: List<VoteCommentInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("vote_id", c["vote_id"])
                .addValue("general_id", c["general_id"])
                .addValue("nation_id", c["nation_id"])
                .addValue("general_name", c["general_name"])
                .addValue("nation_name", c["nation_name"])
                .addValue("text", c["text"])
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO vote_comment
                (world_id, vote_id, general_id, nation_id, general_name, nation_name, text)
            VALUES
                (:world_id, :vote_id, :general_id, :nation_id, :general_name, :nation_name, :text)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("vote_comment", FlushVerb.CREATE_MANY, rows.size))
    }

    /**
     * `vote_poll` UPDATE 한 묶음 (closeOldVote 명시 마감 — NewVote.php::closeOldVote). pollId → 컬럼
     * (삽입순 last-write-wins LinkedHashMap)을 받아 `UPDATE vote_poll SET <cols> WHERE id=:id`를 행마다
     * 방출한다. 컬럼은 삽입 순서대로 SET 절을 구성한다 (diplomacy/message 단건 UPDATE 패턴과 동일).
     * 사용 컬럼: end_at, closed_at, updated_at — 모두 timestamptz라 `CAST(:col AS timestamptz)`로 바인딩
     * (vote_poll INSERT의 start_at/end_at 캐스트 패턴과 동일). 값이 null인 컬럼은 NULL로 SET된다.
     */
    private fun votePollUpdateMany(
        worldId: WorldId,
        updates: LinkedHashMap<Int, LinkedHashMap<String, Any?>>,
    ) {
        for ((pollId, columns) in updates) {
            if (columns.isEmpty()) continue // 갱신할 컬럼이 없으면 no-op.
            val src = MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("id", pollId)
            // 삽입 순서를 보존한 SET 절 — end_at/closed_at/updated_at은 timestamptz라 항상 캐스트한다.
            val setClause = columns.entries.joinToString(", ") { (col, value) ->
                src.addValue(col, value?.toString())
                "$col = CAST(:$col AS timestamptz)"
            }
            val affected = jdbc.update(
                "UPDATE vote_poll SET $setClause WHERE world_id = :world_id AND id = :id",
                src,
            )
            check(affected == 1) { "vote_poll UPDATE affected $affected rows; expected exactly 1" }
        }
        lastOps.add(FlushExecOp("vote_poll", FlushVerb.UPDATE, updates.size))
    }

    // --- step 8c: mailbox channel (T0.5) -------------------------------------------------------

    /**
     * INSERT the `message` rows with their pre-assigned in-memory ids (so the SERIAL matches the
     * `receiverMessageID`/`senderMessageID` body back-references — research §2). `type` binds through
     * `CAST(... AS message_type)`; the body binds byte-faithfully (raw json String). Append-additive
     * (receiver row before sender row — the engine ordered them); never deleted/deduped.
     */
    private fun messageCreateMany(worldId: WorldId, messages: List<CreatedMessageRow>) {
        val batch: Array<SqlParameterSource> = messages.map { m ->
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("id", m.id)
                .addValue("mailbox", m.mailbox)
                .addValue("type", m.type)
                .addValue("src", m.srcId)
                .addValue("dest", m.destId)
                .addValue("time", m.time)
                .addValue("valid_until", m.validUntil)
                .addValue("message", jsonb(m.bodyJson))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO message (world_id, id, mailbox, type, src, dest, time, valid_until, message)
            VALUES (:world_id, :id, :mailbox, CAST(:type AS message_type), :src, :dest,
                    CAST(:time AS timestamptz), CAST(:valid_until AS timestamptz), :message)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("message", FlushVerb.CREATE_MANY, messages.size))
    }

    /** UPDATE the `message` body + valid_until for an invalidated message (PHP `Message::invalidate`). */
    private fun messageInvalidateMany(worldId: WorldId, invalidates: List<MessageInvalidateRow>) {
        for (m in invalidates) {
            val affected = jdbc.update(
                """
                UPDATE message SET message = :message, valid_until = CAST(:valid_until AS timestamptz)
                 WHERE world_id = :world_id AND id = :id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("world_id", worldId.value)
                    .addValue("message", jsonb(m.bodyJson))
                    .addValue("valid_until", m.validUntil)
                    .addValue("id", m.id),
            )
            check(affected == 1) { "message UPDATE affected $affected rows; expected exactly 1" }
        }
        lastOps.add(FlushExecOp("message", FlushVerb.UPDATE, invalidates.size))
    }

    // --- step 8f: 외교 서신 채널 (W5d, diplomacy_letter) -----------------------------------------

    /**
     * INSERT the `diplomacy_letter` rows with their pre-assigned in-memory ids (=newLetterNo, so the
     * SERIAL matches the message body + result back-reference). `state` binds through
     * `CAST(... AS diplomacy_letter_state)` (PHP 소문자 'proposed'를 enum 'PROPOSED'로 대문자화한 값을
     * caller가 싣는다), `date` through `CAST(... AS timestamptz)`, `aux` byte-faithfully (raw json String).
     * INSERT 전용 — 절대 삭제/dedup되지 않는다. `prev_id`/`dest_signer`는 nullable.
     */
    private fun diplomacyLetterInsertMany(worldId: WorldId, rows: List<DiplomacyLetterInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("id", r.id)
                .addValue("src_nation_id", c["src_nation_id"])
                .addValue("dest_nation_id", c["dest_nation_id"])
                .addValue("prev_id", c["prev_id"])
                .addValue("state", c["state"])
                .addValue("text_brief", c["text_brief"])
                .addValue("text_detail", c["text_detail"])
                .addValue("date", c["date"]?.toString())
                .addValue("src_signer", c["src_signer"])
                .addValue("dest_signer", c["dest_signer"])
                .addValue("aux", jsonb(c["aux"] as? String))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO diplomacy_letter
                (world_id, id, src_nation_id, dest_nation_id, prev_id, state, text_brief,
                 text_detail, date, src_signer, dest_signer, aux)
            VALUES
                (:world_id, :id, :src_nation_id, :dest_nation_id, :prev_id,
                 CAST(:state AS diplomacy_letter_state), :text_brief, :text_detail,
                 CAST(:date AS timestamptz), :src_signer, :dest_signer, :aux)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("diplomacy_letter", FlushVerb.CREATE_MANY, rows.size))
    }

    /**
     * UPDATE the `diplomacy_letter` rows (회수/파기/대체). letterNo별 변경 컬럼만 SET한다 — `state`는
     * `diplomacy_letter_state` enum 캐스트, `aux`는 jsonb. 삽입 순서를 보존한 SET 절(컬럼 단위 last-write-wins).
     */
    private fun diplomacyLetterUpdateMany(
        worldId: WorldId,
        updates: LinkedHashMap<Int, LinkedHashMap<String, Any?>>,
    ) {
        for ((letterNo, columns) in updates) {
            if (columns.isEmpty()) continue // 갱신할 컬럼이 없으면 no-op.
            val src = MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("id", letterNo)
            val setClause = columns.entries.joinToString(", ") { (col, value) ->
                when (col) {
                    "state" -> { src.addValue(col, value?.toString()); "$col = CAST(:$col AS diplomacy_letter_state)" }
                    "aux" -> { src.addValue(col, jsonb(value as? String)); "$col = :$col" }
                    else -> { src.addValue(col, value); "$col = :$col" }
                }
            }
            val affected = jdbc.update(
                "UPDATE diplomacy_letter SET $setClause WHERE world_id = :world_id AND id = :id",
                src,
            )
            check(affected == 1) { "diplomacy_letter UPDATE affected $affected rows; expected exactly 1" }
        }
        lastOps.add(FlushExecOp("diplomacy_letter", FlushVerb.UPDATE, updates.size))
    }

    // --- step 11: inheritance channel (T0.8) --------------------------------------------------

    /** INSERT into `inheritance_log`. W0-8: date(V17, PHP user_record.date NULL 허용) 동반. */
    private fun inheritanceLogInsertMany(rows: List<InheritanceLogRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            MapSqlParameterSource()
                .addValue("user_id", r.ownerID.toString())
                .addValue("year", r.year)
                .addValue("month", r.month)
                .addValue("text", r.text)
                .addValue("date", r.date)
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO inheritance_log (user_id, year, month, text, date)
            VALUES (:user_id, :year, :month, :text, CAST(:date AS timestamptz))
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("inheritance_log", FlushVerb.CREATE_MANY, rows.size))
    }

    /** INSERT into `inheritance_result`. */
    private fun inheritanceResultInsertMany(worldId: WorldId, rows: List<InheritanceResultRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("server_id", r.serverID.toString())
                .addValue("owner", r.ownerID.toString())
                .addValue("general_id", r.generalID)
                .addValue("year", r.year)
                .addValue("month", r.month)
                .addValue("value", jsonb(r.valueJson))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO inheritance_result (world_id, server_id, owner, general_id, year, month, value)
            VALUES (:world_id, :server_id, :owner, :general_id, :year, :month, :value)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("inheritance_result", FlushVerb.CREATE_MANY, rows.size))
    }

    /** INSERT into `statistic` (W1 checkStatistic). */
    private fun statisticInsertMany(worldId: WorldId, rows: List<StatisticInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("year", c["year"])
                .addValue("month", c["month"])
                .addValue("nation_count", c["nation_count"])
                .addValue("nation_name", c["nation_name"])
                .addValue("nation_hist", c["nation_hist"])
                .addValue("gen_count", c["gen_count"])
                .addValue("personal_hist", c["personal_hist"])
                .addValue("special_hist", c["special_hist"])
                .addValue("power_hist", c["power_hist"])
                .addValue("crewtype", c["crewtype"])
                .addValue("etc", c["etc"])
                .addValue("aux", jsonb(c["aux"] as? String))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO statistic (world_id, year, month, nation_count, nation_name, nation_hist, gen_count,
                personal_hist, special_hist, power_hist, crewtype, etc, aux)
            VALUES (:world_id, :year, :month, :nation_count, :nation_name, :nation_hist, :gen_count,
                :personal_hist, :special_hist, :power_hist, :crewtype, :etc, :aux)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("statistic", FlushVerb.CREATE_MANY, rows.size))
    }

    /**
     * `yearbook_history` INSERT (W0-8 연감 채널, P0-20). PHP 정본 LogHistory(func_history.php:436-448)는
     * `ng_history`에 server_id/year/month + map/global_history/global_action/nations를 평INSERT한다.
     * V28부터 server_id를 정본 컬럼으로 싣고, profile_name/hash는 기존 loader/test 호환용으로만 보존한다.
     * map은 객체(기본 '{}'), global_history/global_action/nations는 배열(기본 '[]') jsonb.
     */
    private fun yearbookInsertMany(worldId: WorldId, rows: List<YearbookInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            val serverId = c["server_id"] ?: c["profile_name"]
                ?: error("yearbook_history insert requires server_id or legacy profile_name")
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("server_id", serverId)
                .addValue("profile_name", c["profile_name"] ?: serverId)
                .addValue("year", c["year"])
                .addValue("month", c["month"])
                .addValue("map", jsonb(c["map"] as? String))
                .addValue("nations", jsonb(c["nations"] as? String ?: "[]"))
                .addValue("global_history", jsonb(c["global_history"] as? String ?: "[]"))
                .addValue("global_action", jsonb(c["global_action"] as? String ?: "[]"))
                .addValue("hash", c["hash"] ?: "")
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO yearbook_history
                (world_id, server_id, profile_name, year, month, map, nations, global_history,
                 global_action, hash)
            VALUES
                (:world_id, :server_id, :profile_name, :year, :month, :map, :nations,
                 :global_history, :global_action, :hash)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("yearbook_history", FlushVerb.CREATE_MANY, rows.size))
    }

    private fun gameWinnerUpdateMany(worldId: WorldId, rows: List<GameWinnerUpdateRow>) {
        for (row in rows) {
            val affected = jdbc.update(
                """
                UPDATE ng_games
                   SET winner_nation = :winner_nation
                 WHERE world_id = :world_id AND server_id = :server_id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("world_id", worldId.value)
                    .addValue("server_id", row.serverId)
                    .addValue("winner_nation", row.winnerNation),
            )
            check(affected == 1) { "ng_games UPDATE affected $affected rows; expected exactly 1" }
        }
        lastOps.add(FlushExecOp("ng_games", FlushVerb.UPDATE, rows.size))
    }

    private fun emperiorInsertMany(worldId: WorldId, rows: List<EmperiorInsertRow>) {
        val batch = rows.map { row ->
            val c = row.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("phase", c["phase"])
                .addValue("server_id", c["server_id"])
                .addValue("nation_count", c["nation_count"])
                .addValue("nation_name", c["nation_name"])
                .addValue("nation_hist", c["nation_hist"])
                .addValue("gen_count", c["gen_count"])
                .addValue("personal_hist", c["personal_hist"])
                .addValue("special_hist", c["special_hist"])
                .addValue("name", c["name"])
                .addValue("type", c["type"])
                .addValue("color", c["color"])
                .addValue("year", c["year"])
                .addValue("month", c["month"])
                .addValue("power", c["power"])
                .addValue("gennum", c["gennum"])
                .addValue("citynum", c["citynum"])
                .addValue("pop", c["pop"])
                .addValue("poprate", c["poprate"])
                .addValue("gold", c["gold"])
                .addValue("rice", c["rice"])
                .addValue("l12name", c["l12name"])
                .addValue("l12pic", c["l12pic"])
                .addValue("l11name", c["l11name"])
                .addValue("l11pic", c["l11pic"])
                .addValue("l10name", c["l10name"])
                .addValue("l10pic", c["l10pic"])
                .addValue("l9name", c["l9name"])
                .addValue("l9pic", c["l9pic"])
                .addValue("l8name", c["l8name"])
                .addValue("l8pic", c["l8pic"])
                .addValue("l7name", c["l7name"])
                .addValue("l7pic", c["l7pic"])
                .addValue("l6name", c["l6name"])
                .addValue("l6pic", c["l6pic"])
                .addValue("l5name", c["l5name"])
                .addValue("l5pic", c["l5pic"])
                .addValue("tiger", c["tiger"])
                .addValue("eagle", c["eagle"])
                .addValue("gen", c["gen"])
                .addValue("history", jsonb(c["history"] as? String ?: "[]"))
                .addValue("aux", jsonb(c["aux"] as? String ?: "{}"))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO emperior (
                world_id, phase, server_id, nation_count, nation_name, nation_hist, gen_count,
                personal_hist, special_hist,
                name, type, color, year, month, power, gennum, citynum, pop, poprate, gold, rice,
                l12name, l12pic, l11name, l11pic, l10name, l10pic, l9name, l9pic, l8name, l8pic,
                l7name, l7pic, l6name, l6pic, l5name, l5pic, tiger, eagle, gen, history, aux
            ) VALUES (
                :world_id, :phase, :server_id, :nation_count, :nation_name, :nation_hist, :gen_count,
                :personal_hist, :special_hist,
                :name, :type, :color, :year, :month, :power, :gennum, :citynum, :pop, :poprate, :gold, :rice,
                :l12name, :l12pic, :l11name, :l11pic, :l10name, :l10pic, :l9name, :l9pic, :l8name, :l8pic,
                :l7name, :l7pic, :l6name, :l6pic, :l5name, :l5pic, :tiger, :eagle, :gen, :history, :aux
            )
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("emperior", FlushVerb.CREATE_MANY, rows.size))
    }

    private fun hallUpsertMany(worldId: WorldId, rows: List<HallUpsertRow>) {
        val batch = rows.map { row ->
            val c = row.columns
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("server_id", c["server_id"])
                .addValue("season", c["season"])
                .addValue("scenario", c["scenario"])
                .addValue("general_no", c["general_no"])
                .addValue("type", c["type"])
                .addValue("value", c["value"])
                .addValue("owner", c["owner"])
                .addValue("aux", jsonb(c["aux"] as? String ?: "{}"))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO hall (world_id, server_id, season, scenario, general_no, type, value, owner, aux)
            VALUES (:world_id, :server_id, :season, :scenario, :general_no, :type, :value, :owner, :aux)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
            batch,
        )
        jdbc.batchUpdate(
            """
            UPDATE hall
               SET value = :value,
                   aux = :aux
             WHERE server_id = :server_id
               AND world_id = :world_id
               AND type = :type
               AND scenario = :scenario
               AND general_no = :general_no
               AND value < :value
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("hall", FlushVerb.UPSERT, rows.size))
    }

    private fun eventInsertMany(worldId: WorldId, rows: List<EventInsertRow>) {
        val batch = rows.map { row ->
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("id", row.id)
                .addValue("target_code", row.targetCode)
                .addValue("priority", row.priority)
                .addValue("condition", row.condition)
                .addValue("action", row.action)
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO event (world_id, id, target_code, priority, condition, action)
            VALUES (:world_id, :id, :target_code, :priority, CAST(:condition AS jsonb), CAST(:action AS jsonb))
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("event", FlushVerb.CREATE_MANY, rows.size))
    }

    private fun eventDeleteMany(worldId: WorldId, ids: List<Int>) {
        val batch = ids.map { id ->
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("id", id)
        }.toTypedArray()
        jdbc.batchUpdate("DELETE FROM event WHERE world_id = :world_id AND id = :id", batch)
        lastOps.add(FlushExecOp("event", FlushVerb.DELETE_MANY, ids.size))
    }

    private fun selectPoolMutate(worldId: WorldId, mutations: List<SelectPoolMutation>) {
        for (mutation in mutations) {
            val params = MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("unique_name", mutation.uniqueName)
                .addValue("owner", mutation.ownerUserId)
                .addValue("general_id", mutation.generalId)
                .addValue("claimed_general_id", -mutation.generalId)
                .addValue("now", java.sql.Timestamp.from(mutation.now))
            when (mutation.type) {
                SelectPoolMutationType.REFRESH -> {
                    jdbc.update(
                        """
                        DELETE FROM select_pool
                         WHERE world_id = :world_id
                           AND (reserved_until < :now OR reserved_until IS NULL)
                           AND general_id IS NULL
                        """.trimIndent(),
                        params,
                    )
                    val reservedUntil = requireNotNull(mutation.reservedUntil)
                    val batch = mutation.candidates.map { candidate ->
                        MapSqlParameterSource()
                            .addValue("world_id", worldId.value)
                            .addValue("owner", mutation.ownerUserId)
                            .addValue("unique_name", candidate.uniqueName)
                            .addValue("reserved_until", java.sql.Timestamp.from(reservedUntil))
                            .addValue("info", MetaJson.encode(candidate.info))
                    }.toTypedArray()
                    jdbc.batchUpdate(
                        """
                        INSERT INTO select_pool (world_id, owner, unique_name, reserved_until, info)
                        VALUES (:world_id, :owner, :unique_name, :reserved_until, :info)
                        """.trimIndent(),
                        batch,
                    )
                }
                SelectPoolMutationType.PICK -> {
                    val claimed = jdbc.update(
                        """
                        UPDATE select_pool
                           SET general_id = :general_id,
                               owner = NULL,
                               reserved_until = NULL
                         WHERE unique_name = :unique_name
                           AND world_id = :world_id
                           AND owner = :owner
                           AND reserved_until >= :now
                           AND general_id IS NULL
                        """.trimIndent(),
                        params,
                    )
                    check(claimed == 1) { "select_pool pick claim lost: ${mutation.uniqueName}" }
                }
                SelectPoolMutationType.UPDATE -> {
                    val marked = jdbc.update(
                        """
                        UPDATE select_pool
                           SET general_id = :claimed_general_id,
                               owner = NULL,
                               reserved_until = NULL
                         WHERE unique_name = :unique_name
                           AND world_id = :world_id
                           AND owner = :owner
                           AND reserved_until >= :now
                        """.trimIndent(),
                        params,
                    )
                    check(marked == 1) { "select_pool update claim lost: ${mutation.uniqueName}" }
                    jdbc.update(
                        """
                        UPDATE select_pool
                           SET general_id = NULL,
                               owner = NULL,
                               reserved_until = NULL
                         WHERE unique_name <> :unique_name
                           AND world_id = :world_id
                           AND general_id = :general_id
                        """.trimIndent(),
                        params,
                    )
                    val swapped = jdbc.update(
                        """
                        UPDATE select_pool
                           SET general_id = :general_id
                         WHERE world_id = :world_id AND general_id = :claimed_general_id
                        """.trimIndent(),
                        params,
                    )
                    check(swapped == 1) { "select_pool update swap lost: ${mutation.uniqueName}" }
                }
            }
            if (mutation.type != SelectPoolMutationType.REFRESH) {
                jdbc.update(
                    """
                    UPDATE select_pool
                       SET owner = NULL,
                           reserved_until = NULL
                     WHERE (owner = :owner OR reserved_until < :now)
                       AND world_id = :world_id
                       AND general_id IS NULL
                    """.trimIndent(),
                    params,
                )
            }
        }
        lastOps.add(FlushExecOp("select_pool", FlushVerb.UPDATE, mutations.size))
    }

    private fun jsonb(json: String?): PGobject {
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = json ?: "{}"
        return obj
    }

    private fun requireExactlyOneAffected(operation: String, affected: IntArray) {
        affected.forEachIndexed { index, count ->
            check(count == 1) { "$operation input[$index] affected $count rows; expected exactly 1" }
        }
    }
}

/** A recorded flush op (instrumentation seam — infra has no engine dep, so this mirrors
 *  `opensamguk.engine.flush.FlushOp` independently). */
data class FlushExecOp(val table: String, val verb: FlushVerb, val count: Int)

enum class FlushVerb { UPDATE, UPSERT, CREATE_MANY, DELETE_MANY }

/**
 * Mirrors the engine `DirtyState` but lives in `:infra` so the executor has no engine dep cycle;
 * `:app:game-engine` maps `DirtyState -> FlushPayload` at the call site (AREA F). The created/deleted
 * lists default empty in P1 — the full contract is present so later phases never reshape this.
 */
data class FlushPayload(
    val worldId: WorldId,
    val worldStateUpdate: Map<String, Any?>,
    val archiveServerId: String? = null,
    val updatedGenerals: List<General> = emptyList(),   // logic entities
    val updatedCities: List<City> = emptyList(),
    val logEntries: List<LogRow> = emptyList(),
    val deletedNationSnapshots: List<Map<String, Any?>> = emptyList(),
    val oldGeneralSnapshots: List<OldGeneralArchiveRow> = emptyList(),
    // --- B1 장수생성 foundation: 신규 장수 INSERT (step-3 createMany) ---
    // 새로 만든 장수 행 + 30개 general_turn(휴식) + 37개 rank_data(value 0). 컬럼맵 운반체
    // ([GeneralCreateRow])라 infra가 엔진 TurnGeneral 모양에 결합되지 않는다(betting/board/auction
    // INSERT-row와 동일). 엔진 측 created-set(world DirtyState.createdGenerals)이 이 슬롯을 채운다.
    val createdGenerals: List<GeneralCreateRow> = emptyList(),
    val generalAccessLogUpserts: List<GeneralAccessLogWriteRow> = emptyList(),
    val generalAccessLogDeletes: List<Int> = emptyList(),
    // --- P2 satellite write-set (Task FF2) ---
    val updatedNations: List<Nation> = emptyList(),           // step-7 nation UPDATE (excl created)
    val createdNations: List<Nation> = emptyList(),           // step-3 createMany
    val createdNationTurns: List<NationTurn> = emptyList(),   // step-3 createMany
    val createdDiplomacy: List<Diplomacy> = emptyList(),      // step-3 createMany (거병 bidirectional pairs)
    val updatedDiplomacy: List<DiplomacyUpdate> = emptyList(),// step-7d per-command diplomacy UPDATE (T0.4)
    // --- F4 Wave C2 slice B: troop persistence (NewTroop/ExitTroop-disband/SetTroopName) ---
    val createdTroops: List<TroopRow> = emptyList(),          // step-3 createMany troop
    val deletedTroops: List<Int> = emptyList(),               // step-4 deleteMany troop (by troop_leader)
    val updatedTroops: List<TroopRow> = emptyList(),          // step-7 troop UPDATE (rename, excl created)
    val deletedGenerals: List<Int> = emptyList(),             // step-5 deleteMany general + rank_data
    val deletedNations: List<Int> = emptyList(),              // step-6 nation cascade
    val rankWrites: List<RankWrite> = emptyList(),            // step-8 rank_data UPDATE (incr then set)
    val rankNationSync: List<RankNationSync> = emptyList(),   // step-8 rank_data nation_id sync
    val kvWrites: List<KvWrite> = emptyList(),                // step-10 nation_env KV (delete-on-null)
    val createdMessages: List<CreatedMessageRow> = emptyList(),       // step-8c message INSERT (T0.5)
    val messageInvalidates: List<MessageInvalidateRow> = emptyList(), // step-8c message invalidate UPDATE (T0.5)
    // --- W5d 외교 서신: diplomacy_letter INSERT(발송) + UPDATE(회수/파기/대체) ---
    val diplomacyLetterInserts: List<DiplomacyLetterInsertRow> = emptyList(), // step-8f diplomacy_letter INSERT
    val diplomacyLetterUpdates: LinkedHashMap<Int, LinkedHashMap<String, Any?>> = LinkedHashMap(), // step-8f UPDATE
    val auctionUpserts: List<AuctionUpsertRow> = emptyList(),         // step-8b ng_auction UPSERT (T0.7)
    val auctionBidInserts: List<AuctionBidInsertRow> = emptyList(),   // step-8b ng_auction_bid INSERT (T0.7)
    val bettingInserts: List<BettingInsertRow> = emptyList(),         // step-8b ng_betting INSERT (P6)
    // OPENSAM-94 — 프로필 아이콘 typed sync: general.picture/image_server 전용 컬럼 UPDATE. generalUpdate
    // SET 절이 이 두 표시-컬럼을 방출하지 않으므로(officer_city #17류 누락) 전용 채널로 영속한다.
    val profileIconUpdates: List<ProfileIconUpdateRow> = emptyList(), // step-8b general portrait UPDATE (OPENSAM-94)
    // --- F4 Wave C2 슬라이스 C: 게시판(회의실/기밀실) 소셜-콘텐츠 INSERT ---
    val boardPostInserts: List<BoardPostInsertRow> = emptyList(),     // step-8d board_post INSERT
    val boardCommentInserts: List<BoardCommentInsertRow> = emptyList(), // step-8d board_comment INSERT
    // --- F4 Wave 투표: 설문조사(vote_poll/vote/vote_comment) INSERT + vote_poll UPDATE ---
    val votePollInserts: List<VotePollInsertRow> = emptyList(),       // step-8e vote_poll INSERT
    val voteInserts: List<VoteInsertRow> = emptyList(),               // step-8e vote INSERT
    val voteCommentInserts: List<VoteCommentInsertRow> = emptyList(), // step-8e vote_comment INSERT
    // step-8e vote_poll UPDATE (closeOldVote 마감 end_at/closed_at) — pollId → 컬럼(삽입순, last-write-wins).
    val votePollUpdates: LinkedHashMap<Int, LinkedHashMap<String, Any?>> = LinkedHashMap(),
    // --- T0.8 inheritance channel ---
    val inheritanceKvWrites: List<KvWrite> = emptyList(),             // step-11a inheritance KV writes
    val inheritanceLogInserts: List<InheritanceLogRow> = emptyList(), // step-11b inheritance_log INSERT
    val inheritanceResultInserts: List<InheritanceResultRow> = emptyList(), // step-11c inheritance_result INSERT
    // --- W1 checkStatistic channel ---
    val statisticInserts: List<StatisticInsertRow> = emptyList(),     // step-12 statistic INSERT
    // --- W0-8 연감 채널 (P0-20 LogHistory 월별 스냅샷) ---
    val yearbookInserts: List<YearbookInsertRow> = emptyList(),       // step-13 yearbook_history INSERT
    val gameWinnerUpdates: List<GameWinnerUpdateRow> = emptyList(),
    val emperiorInserts: List<EmperiorInsertRow> = emptyList(),
    val hallUpserts: List<HallUpsertRow> = emptyList(),
    val selectPoolMutations: List<SelectPoolMutation> = emptyList(),
    val eventInserts: List<EventInsertRow> = emptyList(),
    val eventDeletes: List<Int> = emptyList(),
)

enum class SelectPoolMutationType { REFRESH, PICK, UPDATE }

data class SelectPoolCandidate(
    val uniqueName: String,
    val info: Map<String, Any?>,
)

data class SelectPoolMutation(
    val type: SelectPoolMutationType,
    val uniqueName: String,
    val ownerUserId: Int,
    val generalId: Int,
    val now: Instant,
    val reservedUntil: Instant? = null,
    val candidates: List<SelectPoolCandidate> = emptyList(),
)

data class GeneralAccessLogWriteRow(
    val generalId: Int,
    val userId: Long?,
    val lastRefresh: Instant?,
    val refresh: Int,
    val refreshTotal: Int,
    val refreshScore: Int,
    val refreshScoreTotal: Int,
)

/** One `statistic` INSERT (W1 checkStatistic, INSERT-only). `columns`는 byte-faithful statistic 컬럼 맵. */
data class StatisticInsertRow(val columns: Map<String, Any?>)

/**
 * One `yearbook_history` INSERT (W0-8 연감 채널, P0-20). `columns`는 PHP `ng_history`
 * server_id/year/month/map(jsonb)/global_history(jsonb)/global_action(jsonb)/nations(jsonb)
 * 대응이며, legacy profile_name/hash 입력도 compatibility로 허용한다.
 */
data class YearbookInsertRow(val columns: Map<String, Any?>)

data class GameWinnerUpdateRow(val serverId: String, val winnerNation: Int)

data class EmperiorInsertRow(val columns: Map<String, Any?>)

data class HallUpsertRow(val columns: Map<String, Any?>)

data class EventInsertRow(
    val id: Int,
    val targetCode: String,
    val priority: Int,
    val condition: String,
    val action: String,
)

data class OldGeneralArchiveRow(
    val serverId: String?,
    val generalNo: Int,
    val owner: String?,
    val name: String,
    val lastYearMonth: Int,
    val turnTime: Instant,
    val data: Map<String, Any?>,
)

/** One `ng_auction` UPSERT (T0.7). `id` non-null → UPDATE; null → INSERT with `allocatedId`. */
data class AuctionUpsertRow(val id: Int?, val allocatedId: Int?, val columns: Map<String, Any?>)

/** One `ng_auction_bid` INSERT (T0.7, INSERT-only). */
data class AuctionBidInsertRow(val columns: Map<String, Any?>)

/**
 * One `ng_betting` UPSERT (P6 betting intake). W0-8: INSERT 전용 → PHP `insertUpdate` 패러티의
 * amount-누적 UPSERT (UNIQUE(general_id,betting_id,betting_type) 충돌 시 amount += EXCLUDED.amount).
 */
data class BettingInsertRow(val columns: Map<String, Any?>)
/** OPENSAM-94 프로필 아이콘 sync — general portrait 컬럼(picture/image_server) UPDATE 운반체. */
data class ProfileIconUpdateRow(val columns: Map<String, Any?>)

/**
 * 신규 장수 INSERT 한 건 (B1 장수생성 foundation). `id`는 `general.id integer PRIMARY KEY`(NOT serial)
 * — 엔진이 id를 부여하므로 명시적으로 싣는다(nation 패턴과 동일, flush-time 재조정 없음). `columns`는
 * byte-faithful `general` 컬럼 맵으로, ScenarioImporter.insertGenerals의 컬럼 집합(V1+V6)을 정확히
 *미러링한다: id/name/nation_id/city_id/troop_id/npc_state/affinity/born_year/dead_year/picture/
 * image_server/leadership/strength/intel/injury/experience/dedication/officer_level/gold/rice/crew/
 * crew_type_id/train/atmos/weapon_code/book_code/horse_code/item_code/turn_time/age/start_age/
 * personal_code/special_code/special2_code/officer_city/last_turn/meta/penalty. INSERT 전용. 각 장수마다
 * 30개 general_turn(휴식) + 37개 rank_data(value 0)가 함께 INSERT된다(executor의 generalCreateMany).
 */
data class GeneralCreateRow(val columns: Map<String, Any?>)

/**
 * `diplomacy_letter` INSERT 한 건 (W5d 외교 서신 발송, INSERT 전용). `id`는 recorder가 선할당한
 * letterNo(= PHP `insertId()`)를 명시적으로 싣는다(in-memory 단조 id가 flushed SERIAL과 일치 —
 * message/auction open INSERT 패턴). `columns`는 byte-faithful diplomacy_letter 컬럼 맵.
 */
data class DiplomacyLetterInsertRow(val id: Int, val columns: Map<String, Any?>)

/** `board_post` INSERT 한 건 (F4 Wave C2 슬라이스 C, 회의실/기밀실 글, INSERT 전용). */
data class BoardPostInsertRow(val columns: Map<String, Any?>)

/** `board_comment` INSERT 한 건 (F4 Wave C2 슬라이스 C, 회의실/기밀실 댓글, INSERT 전용). */
data class BoardCommentInsertRow(val columns: Map<String, Any?>)

/** `vote_poll` INSERT 한 건 (F4 Wave 투표, 설문조사 개설, INSERT 전용). */
data class VotePollInsertRow(val columns: Map<String, Any?>)

/** `vote` INSERT 한 건 (F4 Wave 투표, 투표 던지기, INSERT 전용 — PHP insertIgnore = ON CONFLICT DO NOTHING). */
data class VoteInsertRow(val columns: Map<String, Any?>)

/** `vote_comment` INSERT 한 건 (F4 Wave 투표, 설문조사 댓글, INSERT 전용). */
data class VoteCommentInsertRow(val columns: Map<String, Any?>)

/**
 * One `troop` row (F4 Wave C2 slice B). Infra-local mirror of the engine `Troop` (no engine dep
 * cycle). `troopLeader` is the PK (== the leader general's id); `nation` is immutable for the row's
 * lifetime, so the rename UPDATE touches only `name`.
 */
data class TroopRow(val troopLeader: Int, val nation: Int, val name: String)

/**
 * One `inheritance_log` INSERT (T0.8). Year/month are stamped by [DatabaseHooks].
 * `date`(W0-8, V17): PHP user_record.date(DATETIME NULL) 대응의 ISO-8601 timestamptz 문자열 —
 * null이면 NULL 바인딩(기존 행/미스탬프 행 패러티; 실제 스탬프는 W1-J 소관).
 */
data class InheritanceLogRow(
    val ownerID: Int,
    val year: Int,
    val month: Int,
    val text: String,
    val tag: String,
    val date: String? = null,
)

/**
 * One `message`-row INSERT (T0.5). `id` is the pre-assigned in-memory monotonic id; `bodyJson` is the
 * byte-faithful `Json::encode({src,dest,text,option})`. Infra-local mirror of the engine `CreatedMessage`.
 */
data class CreatedMessageRow(
    val id: Int,
    val mailbox: Int,
    val type: String,
    val srcId: Int,
    val destId: Int,
    val time: String,
    val validUntil: String,
    val bodyJson: String,
)

/** One `message` invalidate UPDATE (T0.5). Infra-local mirror of the engine `MessageInvalidate`. */
data class MessageInvalidateRow(val id: Int, val validUntil: String, val bodyJson: String)

/**
 * One rank_data write for a `(general, type)`: an [RankFlushOp.Increment] (`value = value + n`,
 * rankVarIncrease) or [RankFlushOp.Set] (`value = n`, rankVarSet). Infra-local mirror of the engine
 * `RankDelta` (no engine dep cycle). `type` is the rank_data column name (the PHP `RankColumn` value).
 */
data class RankWrite(val generalId: Int, val type: String, val op: RankFlushOp)

sealed interface RankFlushOp {
    data class Increment(val value: Int) : RankFlushOp
    data class Set(val value: Int) : RankFlushOp
}

/** When a general's `nation` changed, ALL its rank_data rows get `nation_id := [nationId]`. */
data class RankNationSync(val generalId: Int, val nationId: Int)

/**
 * One per-command diplomacy UPDATE (T0.4): toggle `state_code`+`term` (and `is_dead` when [dead] is
 * non-null) for a single `(src, dest)` row. Bidirectional transitions are TWO of these. Infra-local
 * mirror of the engine `DiplomacyRowPatch` (no engine dep cycle).
 */
data class DiplomacyUpdate(
    val fromNationId: Int,
    val toNationId: Int,
    val state: Int,
    val term: Int,
    val dead: Int? = null,
)

/**
 * One KV write: `value == null` DELETEs the row (delete-on-null, KVStorage.php), a non-null value
 * UPSERTs the encoded jsonb.
 *
 *  - `table == "nation_env"` → the V3 int-namespace store (`namespace` is the nation id as a
 *    decimal string; values: bare int for `next_execute_*`, object for `turn_last_{officer_level}`).
 *  - any other `table` (`game_env`/`betting`/`inheritance_{id}`/…) → the V7 string-namespace
 *    `game_kv` store keyed by `(table, namespace, key)`.
 *
 * Every non-null `value` is [MetaJson]-encoded at flush, matching PHP `Json::encode`.
 */
data class KvWrite(val table: String, val namespace: String, val key: String, val value: Any?) {
    companion object {
        /** Convenience for the legacy nation_env int-namespace call sites (P2/P3). */
        fun nationEnv(namespace: Int, key: String, value: Any?): KvWrite =
            KvWrite("nation_env", namespace.toString(), key, value)
    }
}

/**
 * A finalized `log_entry` row ready to INSERT. `scope`/`category` are the PG enum literals
 * (`log_scope`/`log_category`); they bind through a `CAST(... AS log_scope)` in the INSERT.
 * `year`/`month`/`phase` come from world state (the engine `LogEntryDraft` does not carry them; they are
 * stamped at finalize). `meta` is encoded jsonb via [MetaJson] (insertion-order, PHP-faithful).
 */
data class LogRow(
    val scope: String,
    val category: String,
    val text: String,
    val year: Int,
    val month: Int,
    val phase: Int = 1,
    val subType: String? = null,
    val generalId: Int? = null,
    val nationId: Int? = null,
    val userId: Int? = null,
    val meta: Map<String, Any?> = linkedMapOf(),
    val flushBeforeArchive: Boolean = false,
)


/** OPENSAM-131: CAS miss — entire flush transaction must roll back. */
class StaleWorldWriterException(
    val worldId: Int,
    val expectedVersion: Long,
    val writerEpoch: Long,
) : RuntimeException(
    "stale world writer world_id=$worldId expected_version=$expectedVersion writer_epoch=$writerEpoch",
)
