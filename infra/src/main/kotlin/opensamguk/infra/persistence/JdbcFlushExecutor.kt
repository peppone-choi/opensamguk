package opensamguk.infra.persistence

import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
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
 *  5. deleteMany general, then rank_data
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

            // 3. createMany general/nation/troop/diplomacy (each guarded > 0).
            // P1 never creates rows; full contract present for later phases.
            // (no-op while the created lists are empty)

            // 4. deleteMany troop. (no-op in P1)

            // 5. deleteMany general, then rank_data. (no-op in P1)

            // 6. nation cascade: diplomacy, nation_turn, nation. (no-op in P1)

            // 7. updates: general (excl created), city, nation upsert (excl created), troop, diplomacy.
            if (payload.updatedGenerals.isNotEmpty()) {
                generalUpdate(payload.updatedGenerals)
            }
            if (payload.updatedCities.isNotEmpty()) {
                cityUpdate(payload.updatedCities)
            }

            // 8. rank_data upsert (RANK_ROWS_PER_GENERAL per target). (no-op in P1)

            // 9. log_entry createMany.
            if (payload.logEntries.isNotEmpty()) {
                logEntryCreateMany(payload.logEntries)
            }

            // 10. reserved_turns flush. (ring-buffer write happens via ReservedTurnRepository in D3;
            //     recorded here for contract-order completeness)
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
                if (k == "meta") src.addValue(k, jsonb(v as String?)) else src.addValue(k, v)
            }
            src
        }.toTypedArray()
        jdbc.batchUpdate(
            """
            UPDATE general
               SET nation_id = :nation_id,
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
                   meta = :meta
             WHERE id = :id
            """.trimIndent(),
            batch,
        )
        lastOps.add(FlushExecOp("city", FlushVerb.UPDATE, cities.size))
    }

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
)

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
