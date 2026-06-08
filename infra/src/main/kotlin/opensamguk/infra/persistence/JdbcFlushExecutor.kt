package opensamguk.infra.persistence

import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.NationTurn
import opensamguk.logic.inheritance.InheritanceResultRow
import opensamguk.infra.seed.ScenarioImporter
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.transaction.support.TransactionTemplate

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

            // 1. world_state UPDATE — always fires.
            worldStateUpdate(payload.worldStateUpdate)

            // 2. ng_old_nations UPSERT per deleted-nation snapshot.
            if (payload.deletedNationSnapshots.isNotEmpty()) {
                ngOldNationsUpsert(payload.deletedNationSnapshots)
            }

            // 3. createMany general → nation → nation_turn → diplomacy → troop (각 > 0 가드, 동결된
            //    step-3 contract 순서 general-먼저). 신규 장수 INSERT(B1 장수생성 foundation)는 general 행 +
            //    30 general_turn(휴식) + 37 rank_data(value 0)를 함께 쓴다 — ScenarioImporter.insertGenerals
            //    의 컬럼/행 모양과 정확히 일치(엔진 TurnGeneral은 컬럼맵 GeneralCreateRow로 운반돼 infra 결합 없음).
            if (payload.createdGenerals.isNotEmpty()) generalCreateMany(payload.createdGenerals)
            if (payload.createdNations.isNotEmpty()) nationCreateMany(payload.createdNations)
            if (payload.createdDiplomacy.isNotEmpty()) diplomacyCreateMany(payload.createdDiplomacy)
            if (payload.createdNationTurns.isNotEmpty()) nationTurnCreateMany(payload.createdNationTurns)
            if (payload.createdTroops.isNotEmpty()) troopCreateMany(payload.createdTroops)

            // 4. deleteMany troop (by troop_leader; ExitTroop leader-disband + outright removal).
            if (payload.deletedTroops.isNotEmpty()) troopDeleteMany(payload.deletedTroops)

            // 5. deleteMany general, then rank_data (both guarded on deletedGenerals > 0).
            if (payload.deletedGenerals.isNotEmpty()) {
                generalDeleteMany(payload.deletedGenerals)
                rankDataDeleteMany(payload.deletedGenerals)
            }

            // 6. nation cascade: diplomacy, nation_turn, nation (guarded on deletedNations > 0).
            if (payload.deletedNations.isNotEmpty()) {
                nationCascadeDelete(payload.deletedNations)
            }

            // 7. updates: general (excl created), city, nation UPDATE (excl created).
            if (payload.updatedGenerals.isNotEmpty()) {
                generalUpdate(payload.updatedGenerals)
            }
            if (payload.updatedCities.isNotEmpty()) {
                cityUpdate(payload.updatedCities)
            }
            if (payload.updatedNations.isNotEmpty()) {
                nationUpdate(payload.updatedNations)
            }
            // 7c. troop UPDATE (rename via SetTroopName; created-this-tick troops are excluded upstream).
            if (payload.updatedTroops.isNotEmpty()) {
                troopUpdate(payload.updatedTroops)
            }
            // 7d. per-command diplomacy UPDATE (T0.4) — distinct from the monthly TICK's bulk-SQL
            //     update; runs here in the per-command flush, the tick runs in its own boundary flush.
            if (payload.updatedDiplomacy.isNotEmpty()) {
                diplomacyUpdate(payload.updatedDiplomacy)
            }

            // 8. rank_data UPDATE (rankVarIncrease then rankVarSet — General.php:727-744) + nation_id
            //    sync (when a general's nation changed, ALL its rank_data rows get the new nation_id).
            if (payload.rankWrites.isNotEmpty()) {
                rankDataUpdate(payload.rankWrites)
            }
            if (payload.rankNationSync.isNotEmpty()) {
                rankDataNationSync(payload.rankNationSync)
            }

            // 8b. auction channel (T0.7): ng_auction UPSERT (open INSERT / extend-finish UPDATE) then
            //     ng_auction_bid INSERT (INSERT-only — outbid rows are NEVER deleted, research §3).
            if (payload.auctionUpserts.isNotEmpty()) {
                auctionUpsertMany(payload.auctionUpserts)
            }
            if (payload.auctionBidInserts.isNotEmpty()) {
                auctionBidInsertMany(payload.auctionBidInserts)
            }
            if (payload.bettingInserts.isNotEmpty()) {
                bettingInsertMany(payload.bettingInserts)
            }

            // 8d. 게시판 채널 (F4 C2 슬라이스 C): board_post INSERT 후 board_comment INSERT —
            //     부모-먼저-자식 순서라 댓글의 post_id FK 대상이 먼저 존재한다 (board_comment →
            //     board_post ON DELETE CASCADE). INSERT 전용 소셜 콘텐츠 (회의실/기밀실 글·댓글).
            if (payload.boardPostInserts.isNotEmpty()) {
                boardPostInsertMany(payload.boardPostInserts)
            }
            if (payload.boardCommentInserts.isNotEmpty()) {
                boardCommentInsertMany(payload.boardCommentInserts)
            }

            // 8e. 투표 채널 (F4 Wave 투표): vote_poll INSERT 후 vote / vote_comment INSERT —
            //     부모-먼저-자식 순서라 vote/vote_comment의 vote_id FK 대상(vote_poll)이 먼저 존재한다
            //     (둘 다 vote_poll ON DELETE CASCADE). INSERT 전용 (vote는 PHP insertIgnore →
            //     UNIQUE(vote_id,general_id) 중복은 DB가 무시).
            if (payload.votePollInserts.isNotEmpty()) {
                votePollInsertMany(payload.votePollInserts)
            }
            if (payload.voteInserts.isNotEmpty()) {
                voteInsertMany(payload.voteInserts)
            }
            if (payload.voteCommentInserts.isNotEmpty()) {
                voteCommentInsertMany(payload.voteCommentInserts)
            }
            // vote_poll UPDATE (closeOldVote 마감) — INSERT 뒤에 와야 같은 tick에 개설+마감된 설문이 먼저
            // 존재한다(부모-먼저-갱신-나중). 빈 맵이면 no-op (diplomacy updatedDiplomacy 패턴과 동일).
            if (payload.votePollUpdates.isNotEmpty()) {
                votePollUpdateMany(payload.votePollUpdates)
            }

            // 8c. mailbox channel (T0.5): message INSERT (append-additive, receiver-before-sender —
            //     the engine emits them in that order) then invalidate UPDATE (deleteMsg/sibling-sweep).
            if (payload.createdMessages.isNotEmpty()) {
                messageCreateMany(payload.createdMessages)
            }
            if (payload.messageInvalidates.isNotEmpty()) {
                messageInvalidateMany(payload.messageInvalidates)
            }

            // 8f. 외교 서신 채널 (W5d): diplomacy_letter INSERT(발송) 후 UPDATE(회수/파기/대체) —
            //     INSERT-먼저-UPDATE 순서라 같은 tick에 발송된 prev 서신을 'replaced'로 갱신하는 UPDATE 대상이
            //     먼저 존재한다(부모-먼저-갱신-나중). 빈 목록/맵이면 no-op.
            if (payload.diplomacyLetterInserts.isNotEmpty()) {
                diplomacyLetterInsertMany(payload.diplomacyLetterInserts)
            }
            if (payload.diplomacyLetterUpdates.isNotEmpty()) {
                diplomacyLetterUpdateMany(payload.diplomacyLetterUpdates)
            }

            // 9. log_entry createMany.
            if (payload.logEntries.isNotEmpty()) {
                logEntryCreateMany(payload.logEntries)
            }

            // 10. KV writes (nation_env int-ns + game_kv string-ns, delete-on-null) + reserved_turns
            //     flush (ring write via ReservedTurnRepository, recorded here for contract-order
            //     completeness).
            if (payload.kvWrites.isNotEmpty()) {
                kvWriteFlush(payload.kvWrites)
            }

            // 11. Inheritance channel (T0.8) — KV writes, log inserts, result inserts.
            if (payload.inheritanceKvWrites.isNotEmpty()) {
                kvWriteFlush(payload.inheritanceKvWrites)
            }
            if (payload.inheritanceLogInserts.isNotEmpty()) {
                inheritanceLogInsertMany(payload.inheritanceLogInserts)
            }
            if (payload.inheritanceResultInserts.isNotEmpty()) {
                inheritanceResultInsertMany(payload.inheritanceResultInserts)
            }
            // 12. Statistic channel (W1) — year-boundary statistic INSERT.
            if (payload.statisticInserts.isNotEmpty()) {
                statisticInsertMany(payload.statisticInserts)
            }
            null
        }
    }

    // --- step 1 ---------------------------------------------------------------------------------

    private fun worldStateUpdate(worldState: Map<String, Any?>) {
        val params = MapSqlParameterSource()
        params.addValue("id", worldState["id"])
        params.addValue("current_year", worldState["current_year"])
        params.addValue("current_month", worldState["current_month"])
        params.addValue("updated_at_now", true)
        jdbc.update(
            """
            UPDATE world_state
               SET current_year = :current_year,
                   current_month = :current_month,
                   updated_at = now()
             WHERE id = :id
            """.trimIndent(),
            params,
        )
        lastOps.add(FlushExecOp("world_state", FlushVerb.UPDATE, 1))
    }

    // --- step 2 ---------------------------------------------------------------------------------

    private fun ngOldNationsUpsert(snapshots: List<Map<String, Any?>>) {
        // Full contract present for later phases; P1 never populates this list.
        lastOps.add(FlushExecOp("ng_old_nations", FlushVerb.UPSERT, snapshots.size))
    }

    // --- step 7: general UPDATE -----------------------------------------------------------------

    private fun generalUpdate(generals: List<General>) {
        val batch: Array<SqlParameterSource> = generals.map { g ->
            val cols = GeneralRowMapper.toColumns(g)
            val src = MapSqlParameterSource()
            for ((k, v) in cols) {
                if (k == "meta" || k == "last_turn" || k == "penalty") {
                    src.addValue(k, jsonb(v as String?))
                } else {
                    src.addValue(k, v)
                }
            }
            src
        }.toTypedArray()
        jdbc.batchUpdate(
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
                   last_turn = :last_turn,
                   penalty = :penalty,
                   meta = :meta,
                   updated_at = now()
             WHERE id = :id
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("general", FlushVerb.UPDATE, generals.size))
    }

    // --- step 7: city UPDATE --------------------------------------------------------------------

    private fun cityUpdate(cities: List<City>) {
        val batch: Array<SqlParameterSource> = cities.map { c ->
            val cols = CityRowMapper.toColumns(c)
            val src = MapSqlParameterSource()
            for ((k, v) in cols) {
                if (k == "meta") src.addValue(k, jsonb(v as String?)) else src.addValue(k, v)
            }
            src
        }.toTypedArray()
        jdbc.batchUpdate(
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
                   trust = :trust,
                   secu = :secu,
                   secu_max = :secu_max,
                   def = :def,
                   def_max = :def_max,
                   wall = :wall,
                   wall_max = :wall_max,
                   pop = :pop,
                   pop_max = :pop_max,
                   trade = :trade,
                   region = :region,
                   meta = :meta
             WHERE id = :id
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("city", FlushVerb.UPDATE, cities.size))
    }

    // --- step 7: nation UPDATE ------------------------------------------------------------------

    private fun nationUpdate(nations: List<Nation>) {
        val batch: Array<SqlParameterSource> = nations.map { n ->
            val cols = NationRowMapper.toColumns(n)
            val src = MapSqlParameterSource()
            for ((k, v) in cols) {
                if (k == "meta") src.addValue(k, jsonb(v as String?)) else src.addValue(k, v)
            }
            src
        }.toTypedArray()
        jdbc.batchUpdate(
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
                   meta = :meta
             WHERE id = :id
            """.trimIndent(),
            batch,
        )
        // databaseHooks models nation as an UPSERT (createMany excludes these); the UPDATE op-tag is
        // recorded as UPSERT to match the contract / DatabaseHooksOrderTest expectation.
        lastOps.add(FlushExecOp("nation", FlushVerb.UPSERT, nations.size))
    }

    // --- F4 Wave C2 slice B: troop persistence -------------------------------------------------

    private fun troopCreateMany(rows: List<TroopRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            MapSqlParameterSource()
                .addValue("troop_leader", r.troopLeader)
                .addValue("nation", r.nation)
                .addValue("name", r.name)
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO troop (troop_leader, nation, name)
            VALUES (:troop_leader, :nation, :name)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("troop", FlushVerb.CREATE_MANY, rows.size))
    }

    private fun troopDeleteMany(ids: List<Int>) {
        jdbc.update(
            "DELETE FROM troop WHERE troop_leader IN (:ids)",
            MapSqlParameterSource().addValue("ids", ids),
        )
        lastOps.add(FlushExecOp("troop", FlushVerb.DELETE_MANY, ids.size))
    }

    private fun troopUpdate(rows: List<TroopRow>) {
        // SetTroopName updates only `name` (`nation` is immutable for a troop's lifetime).
        val batch: Array<SqlParameterSource> = rows.map { r ->
            MapSqlParameterSource()
                .addValue("troop_leader", r.troopLeader)
                .addValue("name", r.name)
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            UPDATE troop
               SET name = :name
             WHERE troop_leader = :troop_leader
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("troop", FlushVerb.UPDATE, rows.size))
    }

    // --- step 7d: per-command diplomacy UPDATE (T0.4) -------------------------------------------

    /**
     * Faithful to the per-command `diplomacy` UPDATE (`che_선전포고`/`수락`/`파기`/`종전`): toggle
     * `state_code` + `term` (and `is_dead` when the patch carries it) for a single `(src, dest)` row.
     * Bidirectional transitions arrive as TWO patches (both directions). Batched. This is the DELTA
     * path — the monthly TICK's diplomacy bulk-SQL update is a separate write (P3 PostUpdateMonthly).
     */
    private fun diplomacyUpdate(updates: List<DiplomacyUpdate>) {
        for (u in updates) {
            val src = MapSqlParameterSource()
                .addValue("state_code", u.state)
                .addValue("term", u.term)
                .addValue("src_nation_id", u.fromNationId)
                .addValue("dest_nation_id", u.toNationId)
            if (u.dead == null) {
                jdbc.update(
                    """
                    UPDATE diplomacy SET state_code = :state_code, term = :term
                     WHERE src_nation_id = :src_nation_id AND dest_nation_id = :dest_nation_id
                    """.trimIndent(),
                    src,
                )
            } else {
                src.addValue("is_dead", u.dead != 0)
                jdbc.update(
                    """
                    UPDATE diplomacy SET state_code = :state_code, term = :term, is_dead = :is_dead
                     WHERE src_nation_id = :src_nation_id AND dest_nation_id = :dest_nation_id
                    """.trimIndent(),
                    src,
                )
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
     *
     * general_access_log는 V1 baseline 스키마에 없다(kill() delete 경로 주석과 동일 — 미포팅) → 생략.
     * B1 핸들러가 나중에 추가할 수 있다.
     */
    private fun generalCreateMany(rows: List<GeneralCreateRow>) {
        // 1. general 행 INSERT (ScenarioImporter.insertGenerals 컬럼/순서 verbatim).
        val generalBatch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("id", c["id"])
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
        jdbc.batchUpdate(
            """
            INSERT INTO general
                (id, name, nation_id, city_id, troop_id, npc_state, affinity,
                 born_year, dead_year, picture, image_server,
                 leadership, strength, intel, injury, experience, dedication, officer_level,
                 gold, rice, crew, crew_type_id, train, atmos,
                 weapon_code, book_code, horse_code, item_code,
                 turn_time, age, start_age, personal_code, special_code, special2_code, officer_city,
                 last_turn, meta, penalty)
            VALUES
                (:id, :name, :nation_id, :city_id, :troop_id, :npc_state, :affinity,
                 :born_year, :dead_year, :picture, :image_server,
                 :leadership, :strength, :intel, :injury, :experience, :dedication, :officer_level,
                 :gold, :rice, :crew, :crew_type_id, :train, :atmos,
                 :weapon_code, :book_code, :horse_code, :item_code,
                 CAST(:turn_time AS timestamptz), :age, :start_age, :personal_code, :special_code,
                 :special2_code, :officer_city,
                 :last_turn, :meta, :penalty)
            """.trimIndent(),
            generalBatch,
        )
        lastOps.add(FlushExecOp("general", FlushVerb.CREATE_MANY, rows.size))

        // 2. general_turn 30행/장수 — turn_idx 0..29, 모두 휴식 (ScenarioImporter.insertGeneralTurns verbatim).
        //    링 용량/rank 컬럼 목록은 ScenarioImporter의 정본 상수를 재사용(같은 :infra 모듈, 패키지만 다름) —
        //    세 번째 사본을 만들지 않아 시드 경로와 생성-flush 경로가 절대 드리프트하지 않는다.
        val ring = ScenarioImporter.MAX_GENERAL_TURNS
        val turnBatch = ArrayList<SqlParameterSource>(rows.size * ring)
        for (r in rows) {
            val id = r.columns["id"]
            for (idx in 0 until ring) {
                turnBatch.add(MapSqlParameterSource().addValue("general_id", id).addValue("turn_idx", idx))
            }
        }
        jdbc.batchUpdate(
            """
            INSERT INTO general_turn (general_id, turn_idx, action_code, arg, brief)
            VALUES (:general_id, :turn_idx, '휴식', '{}'::jsonb, '휴식')
            """.trimIndent(),
            turnBatch.toTypedArray(),
        )
        lastOps.add(FlushExecOp("general_turn", FlushVerb.CREATE_MANY, rows.size * ring))

        // 3. rank_data 37행/장수 — RANK_COLUMNS 전체, value=0, nation_id=0 (insertRankData verbatim).
        val rankColumns = ScenarioImporter.RANK_COLUMNS
        val rankBatch = ArrayList<SqlParameterSource>(rows.size * rankColumns.size)
        for (r in rows) {
            val id = r.columns["id"]
            for (type in rankColumns) {
                rankBatch.add(MapSqlParameterSource().addValue("general_id", id).addValue("type", type))
            }
        }
        jdbc.batchUpdate(
            """
            INSERT INTO rank_data (nation_id, general_id, type, value)
            VALUES (0, :general_id, :type, 0)
            """.trimIndent(),
            rankBatch.toTypedArray(),
        )
        lastOps.add(FlushExecOp("rank_data", FlushVerb.CREATE_MANY, rows.size * rankColumns.size))
    }

    // --- step 3: nation / nation_turn createMany ------------------------------------------------

    private fun nationCreateMany(nations: List<Nation>) {
        val batch: Array<SqlParameterSource> = nations.map { n ->
            val cols = NationRowMapper.toColumns(n)
            val src = MapSqlParameterSource()
            for ((k, v) in cols) {
                if (k == "meta") src.addValue(k, jsonb(v as String?)) else src.addValue(k, v)
            }
            src
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO nation (id, name, color, capital_city_id, gold, rice, tech, level, type_code, meta)
            VALUES (:id, :name, :color, :capital_city_id, :gold, :rice, :tech, :level, :type_code, :meta)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("nation", FlushVerb.CREATE_MANY, nations.size))
    }

    private fun nationTurnCreateMany(turns: List<NationTurn>) {
        val batch: Array<SqlParameterSource> = turns.map { t ->
            val cols = NationTurnRowMapper.toColumns(t)
            val src = MapSqlParameterSource()
            for ((k, v) in cols) {
                if (k == "arg") src.addValue(k, jsonb(v as String?)) else src.addValue(k, v)
            }
            src
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO nation_turn (nation_id, officer_level, turn_idx, action_code, arg, brief)
            VALUES (:nation_id, :officer_level, :turn_idx, :action_code, :arg, :brief)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("nation_turn", FlushVerb.CREATE_MANY, turns.size))
    }

    /**
     * Step-3 created-diplomacy createMany (`che_거병.php:114-138`): the founding command wires the new
     * nation to every existing nation with a bidirectional `(me, you, state=2, term=0)` pair. Inserted
     * after the nation createMany (the FK target exists) and before nation_turn, matching the frozen
     * step-3 contract order (general → nation → troop → diplomacy). Maps via [DiplomacyRowMapper].
     */
    private fun diplomacyCreateMany(diplomacy: List<Diplomacy>) {
        val batch: Array<SqlParameterSource> = diplomacy.map { d ->
            val cols = DiplomacyRowMapper.toColumns(d)
            val src = MapSqlParameterSource()
            for ((k, v) in cols) src.addValue(k, v)
            src
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO diplomacy (src_nation_id, dest_nation_id, state_code, term)
            VALUES (:src_nation_id, :dest_nation_id, :state_code, :term)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("diplomacy", FlushVerb.CREATE_MANY, diplomacy.size))
    }

    // --- step 5: deleteMany general, then rank_data ---------------------------------------------

    private fun generalDeleteMany(ids: List<Int>) {
        jdbc.update(
            "DELETE FROM general WHERE id IN (:ids)",
            MapSqlParameterSource().addValue("ids", ids),
        )
        lastOps.add(FlushExecOp("general", FlushVerb.DELETE_MANY, ids.size))
        // kill()'s 4-table delete (`General.php:92-95`): general / general_turn / rank_data /
        // general_access_log. general_turn is deleted here (rank_data follows in step-5);
        // general_access_log is NOT ported to the V1 baseline schema (no table), so it is a no-op.
        jdbc.update(
            "DELETE FROM general_turn WHERE general_id IN (:ids)",
            MapSqlParameterSource().addValue("ids", ids),
        )
        lastOps.add(FlushExecOp("general_turn", FlushVerb.DELETE_MANY, ids.size))
    }

    private fun rankDataDeleteMany(generalIds: List<Int>) {
        jdbc.update(
            "DELETE FROM rank_data WHERE general_id IN (:ids)",
            MapSqlParameterSource().addValue("ids", generalIds),
        )
        lastOps.add(FlushExecOp("rank_data", FlushVerb.DELETE_MANY, generalIds.size))
    }

    // --- step 6: nation cascade (diplomacy, nation_turn, nation) --------------------------------

    private fun nationCascadeDelete(nationIds: List<Int>) {
        jdbc.update(
            "DELETE FROM diplomacy WHERE src_nation_id IN (:ids) OR dest_nation_id IN (:ids)",
            MapSqlParameterSource().addValue("ids", nationIds),
        )
        lastOps.add(FlushExecOp("diplomacy", FlushVerb.DELETE_MANY, nationIds.size))
        jdbc.update(
            "DELETE FROM nation_turn WHERE nation_id IN (:ids)",
            MapSqlParameterSource().addValue("ids", nationIds),
        )
        lastOps.add(FlushExecOp("nation_turn", FlushVerb.DELETE_MANY, nationIds.size))
        jdbc.update(
            "DELETE FROM nation WHERE id IN (:ids)",
            MapSqlParameterSource().addValue("ids", nationIds),
        )
        lastOps.add(FlushExecOp("nation", FlushVerb.DELETE_MANY, nationIds.size))
    }

    // --- step 8: rank_data UPDATE (increment then set) + nation_id sync -------------------------

    /**
     * Faithful to `General.php:727-744`: flush the buffered rank maps as `UPDATE rank_data` —
     * `rankVarIncrease` first (`value = value + n`), then `rankVarSet` (`value = n`). The 37 rows per
     * general are pre-seeded at general creation, so this is an UPDATE (never an UPSERT). Caller
     * orders [RankWrite]s increments-before-sets to match the PHP map iteration; the op-tag is one
     * `rank_data UPDATE` covering all affected `(general, type)` rows.
     */
    private fun rankDataUpdate(writes: List<RankWrite>) {
        for (w in writes) {
            val (sql, value) = when (val op = w.op) {
                is RankFlushOp.Increment -> "value = value + :value" to op.value
                is RankFlushOp.Set -> "value = :value" to op.value
            }
            jdbc.update(
                "UPDATE rank_data SET $sql WHERE general_id = :general_id AND type = :type",
                MapSqlParameterSource()
                    .addValue("value", value)
                    .addValue("general_id", w.generalId)
                    .addValue("type", w.type),
            )
        }
        lastOps.add(FlushExecOp("rank_data", FlushVerb.UPDATE, writes.size))
    }

    /**
     * The nation_id-sync denormalization (`General.php:718-723`): when a general's `nation` changed,
     * ALL of that general's rank_data rows get `nation_id := new`. One UPDATE per affected general.
     */
    private fun rankDataNationSync(syncs: List<RankNationSync>) {
        for (s in syncs) {
            jdbc.update(
                "UPDATE rank_data SET nation_id = :nation_id WHERE general_id = :general_id",
                MapSqlParameterSource()
                    .addValue("nation_id", s.nationId)
                    .addValue("general_id", s.generalId),
            )
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
     * etc.). The KvWrite values are pre-encoded where the caller already holds a jsonb String (the
     * `value` is encoded only if it is not already a raw json String — see [encodeKvValue]).
     */
    private fun kvWriteFlush(writes: List<KvWrite>) {
        for (w in writes) {
            when (w.table) {
                "nation_env" -> nationEnvKvWrite(w)
                else -> gameKvWrite(w)
            }
        }
        lastOps.add(FlushExecOp("kv", FlushVerb.UPSERT, writes.size))
    }

    /** int-namespace store (V3 `nation_env`): namespace is the nation id (parsed from the string). */
    private fun nationEnvKvWrite(w: KvWrite) {
        val ns = w.namespace.toInt()
        if (w.value == null) {
            jdbc.update(
                "DELETE FROM nation_env WHERE namespace = :namespace AND key = :key",
                MapSqlParameterSource().addValue("namespace", ns).addValue("key", w.key),
            )
        } else {
            jdbc.update(
                """
                INSERT INTO nation_env (namespace, key, value)
                VALUES (:namespace, :key, :value)
                ON CONFLICT (namespace, key) DO UPDATE SET value = EXCLUDED.value
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("namespace", ns)
                    .addValue("key", w.key)
                    .addValue("value", jsonb(encodeKvValue(w.value))),
            )
        }
    }

    /** string-namespace store (V7 `game_kv`): keyed by `(table, namespace, key)`. */
    private fun gameKvWrite(w: KvWrite) {
        if (w.value == null) {
            jdbc.update(
                """DELETE FROM game_kv WHERE "table" = :tbl AND namespace = :namespace AND key = :key""",
                MapSqlParameterSource().addValue("tbl", w.table).addValue("namespace", w.namespace).addValue("key", w.key),
            )
        } else {
            jdbc.update(
                """
                INSERT INTO game_kv ("table", namespace, key, value)
                VALUES (:tbl, :namespace, :key, :value)
                ON CONFLICT ("table", namespace, key) DO UPDATE SET value = EXCLUDED.value
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("tbl", w.table)
                    .addValue("namespace", w.namespace)
                    .addValue("key", w.key)
                    .addValue("value", jsonb(encodeKvValue(w.value))),
            )
        }
    }

    /**
     * A KV value already materialized as a raw jsonb String (e.g. a byte-faithful `Json::encode`
     * payload the family produced) is bound as-is; any other value (Int/Map/List …) is encoded via
     * [MetaJson]. This lets the obfuscatedNamePool / BettingInfo families hand the executor the exact
     * PHP-`json_encode` bytes without a re-encode round-trip.
     */
    private fun encodeKvValue(value: Any?): String =
        if (value is String) value else MetaJson.encode(value)

    // --- step 9: log_entry createMany -----------------------------------------------------------

    private fun logEntryCreateMany(logs: List<LogRow>) {
        val batch: Array<SqlParameterSource> = logs.map { l ->
            MapSqlParameterSource()
                .addValue("scope", l.scope)
                .addValue("category", l.category)
                .addValue("sub_type", l.subType)
                .addValue("year", l.year)
                .addValue("month", l.month)
                .addValue("text", l.text)
                .addValue("general_id", l.generalId)
                .addValue("nation_id", l.nationId)
                .addValue("user_id", l.userId)
                .addValue("meta", jsonb(MetaJson.encode(l.meta)))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO log_entry
                (scope, category, sub_type, year, month, text, general_id, nation_id, user_id, meta)
            VALUES
                (CAST(:scope AS log_scope), CAST(:category AS log_category), :sub_type, :year, :month,
                 :text, :general_id, :nation_id, :user_id, :meta)
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
    private fun auctionUpsertMany(rows: List<AuctionUpsertRow>) {
        for (r in rows) {
            val c = r.columns
            val src = MapSqlParameterSource()
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
                jdbc.update(
                    """
                    INSERT INTO ng_auction (id, type, finished, target, host_general_id, req_resource, open_date, close_date, detail)
                    VALUES (:id, CAST(:type AS ng_auction_type), :finished, :target, :host_general_id,
                            CAST(:req_resource AS ng_auction_resource), CAST(:open_date AS timestamptz),
                            CAST(:close_date AS timestamptz), :detail)
                    """.trimIndent(),
                    src,
                )
            } else {
                src.addValue("id", r.id)
                jdbc.update(
                    """
                    UPDATE ng_auction SET type = CAST(:type AS ng_auction_type), finished = :finished, target = :target,
                        host_general_id = :host_general_id, req_resource = CAST(:req_resource AS ng_auction_resource),
                        open_date = CAST(:open_date AS timestamptz), close_date = CAST(:close_date AS timestamptz), detail = :detail
                     WHERE id = :id
                    """.trimIndent(),
                    src,
                )
            }
        }
        lastOps.add(FlushExecOp("ng_auction", FlushVerb.UPSERT, rows.size))
    }

    /** INSERT the `ng_auction_bid` rows (INSERT-only; outbid rows persist). `aux` is a raw json String. */
    private fun auctionBidInsertMany(rows: List<AuctionBidInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("auction_id", c["auction_id"])
                .addValue("owner", c["owner"])
                .addValue("general_id", c["general_id"])
                .addValue("amount", c["amount"])
                .addValue("date", c["date"]?.toString())
                .addValue("aux", jsonb(c["aux"] as? String))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO ng_auction_bid (auction_id, owner, general_id, amount, date, aux)
            VALUES (:auction_id, :owner, :general_id, :amount, CAST(:date AS timestamptz), :aux)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("ng_auction_bid", FlushVerb.CREATE_MANY, rows.size))
    }

    /** INSERT the `ng_betting` rows (P6 betting intake, INSERT-only). */
    private fun bettingInsertMany(rows: List<BettingInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("betting_id", c["betting_id"])
                .addValue("general_id", c["general_id"])
                .addValue("user_id", c["user_id"])
                .addValue("betting_type", c["betting_type"])
                .addValue("amount", c["amount"])
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO ng_betting (betting_id, general_id, user_id, betting_type, amount)
            VALUES (:betting_id, :general_id, :user_id, :betting_type, :amount)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("ng_betting", FlushVerb.CREATE_MANY, rows.size))
    }

    // --- step 8d: 게시판 채널 (F4 C2 슬라이스 C, 회의실/기밀실) ----------------------------------

    /** `board_post` 행 INSERT (INSERT 전용; `id`는 SERIAL — 생략해 DB가 부여). */
    private fun boardPostInsertMany(rows: List<BoardPostInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("nation_id", c["nation_id"])
                .addValue("is_secret", c["is_secret"])
                .addValue("author_general_id", c["author_general_id"])
                .addValue("author_name", c["author_name"])
                .addValue("title", c["title"])
                .addValue("content_html", c["content_html"])
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO board_post (nation_id, is_secret, author_general_id, author_name, title, content_html)
            VALUES (:nation_id, :is_secret, :author_general_id, :author_name, :title, :content_html)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("board_post", FlushVerb.CREATE_MANY, rows.size))
    }

    /** `board_comment` 행 INSERT (INSERT 전용; `id`는 SERIAL — 생략해 DB가 부여). */
    private fun boardCommentInsertMany(rows: List<BoardCommentInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("post_id", c["post_id"])
                .addValue("nation_id", c["nation_id"])
                .addValue("is_secret", c["is_secret"])
                .addValue("author_general_id", c["author_general_id"])
                .addValue("author_name", c["author_name"])
                .addValue("content_text", c["content_text"])
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO board_comment (post_id, nation_id, is_secret, author_general_id, author_name, content_text)
            VALUES (:post_id, :nation_id, :is_secret, :author_general_id, :author_name, :content_text)
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
    private fun votePollInsertMany(rows: List<VotePollInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
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
            INSERT INTO vote_poll (title, body, options, multiple_options, reveal_mode, opener_general_id, opener_name, start_at, end_at)
            VALUES (:title, :body, :options, :multiple_options, :reveal_mode, :opener_general_id, :opener_name, CAST(:start_at AS timestamptz), CAST(:end_at AS timestamptz))
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("vote_poll", FlushVerb.CREATE_MANY, rows.size))
    }

    /**
     * `vote` 행 INSERT (PHP `insertIgnore` → `ON CONFLICT (vote_id, general_id) DO NOTHING` —
     * UNIQUE 중복은 무시한다). `selection`은 jsonb (이미 인코딩된 정렬된 인덱스 배열 문자열).
     */
    private fun voteInsertMany(rows: List<VoteInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("vote_id", c["vote_id"])
                .addValue("general_id", c["general_id"])
                .addValue("nation_id", c["nation_id"])
                .addValue("selection", jsonb(c["selection"] as? String))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO vote (vote_id, general_id, nation_id, selection)
            VALUES (:vote_id, :general_id, :nation_id, :selection)
            ON CONFLICT (vote_id, general_id) DO NOTHING
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("vote", FlushVerb.CREATE_MANY, rows.size))
    }

    /** `vote_comment` 행 INSERT (INSERT 전용; `id`는 SERIAL — 생략해 DB가 부여). */
    private fun voteCommentInsertMany(rows: List<VoteCommentInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
                .addValue("vote_id", c["vote_id"])
                .addValue("general_id", c["general_id"])
                .addValue("nation_id", c["nation_id"])
                .addValue("general_name", c["general_name"])
                .addValue("nation_name", c["nation_name"])
                .addValue("text", c["text"])
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO vote_comment (vote_id, general_id, nation_id, general_name, nation_name, text)
            VALUES (:vote_id, :general_id, :nation_id, :general_name, :nation_name, :text)
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
    private fun votePollUpdateMany(updates: LinkedHashMap<Int, LinkedHashMap<String, Any?>>) {
        for ((pollId, columns) in updates) {
            if (columns.isEmpty()) continue // 갱신할 컬럼이 없으면 no-op.
            val src = MapSqlParameterSource().addValue("id", pollId)
            // 삽입 순서를 보존한 SET 절 — end_at/closed_at/updated_at은 timestamptz라 항상 캐스트한다.
            val setClause = columns.entries.joinToString(", ") { (col, value) ->
                src.addValue(col, value?.toString())
                "$col = CAST(:$col AS timestamptz)"
            }
            jdbc.update(
                "UPDATE vote_poll SET $setClause WHERE id = :id",
                src,
            )
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
    private fun messageCreateMany(messages: List<CreatedMessageRow>) {
        val batch: Array<SqlParameterSource> = messages.map { m ->
            MapSqlParameterSource()
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
            INSERT INTO message (id, mailbox, type, src, dest, time, valid_until, message)
            VALUES (:id, :mailbox, CAST(:type AS message_type), :src, :dest,
                    CAST(:time AS timestamptz), CAST(:valid_until AS timestamptz), :message)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("message", FlushVerb.CREATE_MANY, messages.size))
    }

    /** UPDATE the `message` body + valid_until for an invalidated message (PHP `Message::invalidate`). */
    private fun messageInvalidateMany(invalidates: List<MessageInvalidateRow>) {
        for (m in invalidates) {
            jdbc.update(
                """
                UPDATE message SET message = :message, valid_until = CAST(:valid_until AS timestamptz)
                 WHERE id = :id
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("message", jsonb(m.bodyJson))
                    .addValue("valid_until", m.validUntil)
                    .addValue("id", m.id),
            )
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
    private fun diplomacyLetterInsertMany(rows: List<DiplomacyLetterInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
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
            INSERT INTO diplomacy_letter (id, src_nation_id, dest_nation_id, prev_id, state, text_brief,
                                          text_detail, date, src_signer, dest_signer, aux)
            VALUES (:id, :src_nation_id, :dest_nation_id, :prev_id, CAST(:state AS diplomacy_letter_state),
                    :text_brief, :text_detail, CAST(:date AS timestamptz), :src_signer, :dest_signer, :aux)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("diplomacy_letter", FlushVerb.CREATE_MANY, rows.size))
    }

    /**
     * UPDATE the `diplomacy_letter` rows (회수/파기/대체). letterNo별 변경 컬럼만 SET한다 — `state`는
     * `diplomacy_letter_state` enum 캐스트, `aux`는 jsonb. 삽입 순서를 보존한 SET 절(컬럼 단위 last-write-wins).
     */
    private fun diplomacyLetterUpdateMany(updates: LinkedHashMap<Int, LinkedHashMap<String, Any?>>) {
        for ((letterNo, columns) in updates) {
            if (columns.isEmpty()) continue // 갱신할 컬럼이 없으면 no-op.
            val src = MapSqlParameterSource().addValue("id", letterNo)
            val setClause = columns.entries.joinToString(", ") { (col, value) ->
                when (col) {
                    "state" -> { src.addValue(col, value?.toString()); "$col = CAST(:$col AS diplomacy_letter_state)" }
                    "aux" -> { src.addValue(col, jsonb(value as? String)); "$col = :$col" }
                    else -> { src.addValue(col, value); "$col = :$col" }
                }
            }
            jdbc.update("UPDATE diplomacy_letter SET $setClause WHERE id = :id", src)
        }
        lastOps.add(FlushExecOp("diplomacy_letter", FlushVerb.UPDATE, updates.size))
    }

    // --- step 11: inheritance channel (T0.8) --------------------------------------------------

    /** INSERT into `inheritance_log`. */
    private fun inheritanceLogInsertMany(rows: List<InheritanceLogRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            MapSqlParameterSource()
                .addValue("user_id", r.ownerID.toString())
                .addValue("year", r.year)
                .addValue("month", r.month)
                .addValue("text", r.text)
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO inheritance_log (user_id, year, month, text)
            VALUES (:user_id, :year, :month, :text)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("inheritance_log", FlushVerb.CREATE_MANY, rows.size))
    }

    /** INSERT into `inheritance_result`. */
    private fun inheritanceResultInsertMany(rows: List<InheritanceResultRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            MapSqlParameterSource()
                .addValue("server_id", r.serverID.toString())
                .addValue("owner", r.ownerID.toString())
                .addValue("general_id", r.generalID)
                .addValue("year", r.year)
                .addValue("month", r.month)
                .addValue("value", jsonb(r.valueJson))
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            INSERT INTO inheritance_result (server_id, owner, general_id, year, month, value)
            VALUES (:server_id, :owner, :general_id, :year, :month, :value)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("inheritance_result", FlushVerb.CREATE_MANY, rows.size))
    }

    /** INSERT into `statistic` (W1 checkStatistic). */
    private fun statisticInsertMany(rows: List<StatisticInsertRow>) {
        val batch: Array<SqlParameterSource> = rows.map { r ->
            val c = r.columns
            MapSqlParameterSource()
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
            INSERT INTO statistic (year, month, nation_count, nation_name, nation_hist, gen_count,
                personal_hist, special_hist, power_hist, crewtype, etc, aux)
            VALUES (:year, :month, :nation_count, :nation_name, :nation_hist, :gen_count,
                :personal_hist, :special_hist, :power_hist, :crewtype, :etc, :aux)
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("statistic", FlushVerb.CREATE_MANY, rows.size))
    }

    private fun jsonb(json: String?): PGobject {
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = json ?: "{}"
        return obj
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
    val worldStateUpdate: Map<String, Any?>,
    val updatedGenerals: List<General> = emptyList(),   // logic entities
    val updatedCities: List<City> = emptyList(),
    val logEntries: List<LogRow> = emptyList(),
    val deletedNationSnapshots: List<Map<String, Any?>> = emptyList(),
    // --- B1 장수생성 foundation: 신규 장수 INSERT (step-3 createMany) ---
    // 새로 만든 장수 행 + 30개 general_turn(휴식) + 37개 rank_data(value 0). 컬럼맵 운반체
    // ([GeneralCreateRow])라 infra가 엔진 TurnGeneral 모양에 결합되지 않는다(betting/board/auction
    // INSERT-row와 동일). 엔진 측 created-set(world DirtyState.createdGenerals)이 이 슬롯을 채운다.
    val createdGenerals: List<GeneralCreateRow> = emptyList(),
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
)

/** One `statistic` INSERT (W1 checkStatistic, INSERT-only). `columns`는 byte-faithful statistic 컬럼 맵. */
data class StatisticInsertRow(val columns: Map<String, Any?>)

/** One `ng_auction` UPSERT (T0.7). `id` non-null → UPDATE; null → INSERT with `allocatedId`. */
data class AuctionUpsertRow(val id: Int?, val allocatedId: Int?, val columns: Map<String, Any?>)

/** One `ng_auction_bid` INSERT (T0.7, INSERT-only). */
data class AuctionBidInsertRow(val columns: Map<String, Any?>)

/** One `ng_betting` INSERT (P6 betting intake, INSERT-only). */
data class BettingInsertRow(val columns: Map<String, Any?>)

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

/** One `inheritance_log` INSERT (T0.8). Year/month are stamped by [DatabaseHooks]. */
data class InheritanceLogRow(
    val ownerID: Int,
    val year: Int,
    val month: Int,
    val text: String,
    val tag: String,
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
 * A `value` that is already a raw json String is bound verbatim; any other value is [MetaJson]-encoded.
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
 * `year`/`month` come from world state (the engine `LogEntryDraft` does not carry them; they are
 * stamped at finalize). `meta` is encoded jsonb via [MetaJson] (insertion-order, PHP-faithful).
 */
data class LogRow(
    val scope: String,
    val category: String,
    val text: String,
    val year: Int,
    val month: Int,
    val subType: String? = null,
    val generalId: Int? = null,
    val nationId: Int? = null,
    val userId: Int? = null,
    val meta: Map<String, Any?> = linkedMapOf(),
)
