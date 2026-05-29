package opensamguk.infra.persistence

import org.postgresql.util.PGobject
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * `general_turn` ring-buffer reserved-turn repository (JDBC).
 *
 * Mirrors the TS `app/game-api/src/turns/reservedTurns.ts` `setGeneralTurn` semantics
 * (devsam-core2026), collapsed to a single-row upsert against the
 * `UNIQUE (general_id, turn_idx)` baseline constraint:
 *
 *  - [DEFAULT_TURN_ACTION] = `휴식` (TS `DEFAULT_TURN_ACTION`)
 *  - [MAX_GENERAL_TURNS] = 30 (TS `MAX_GENERAL_TURNS`) — the ring buffer length; `turn_idx`
 *    is normalized `mod 30` (TS `buildTurnListFromRows` drops out-of-range indices; here the
 *    index wraps into the 0..29 ring so a caller's absolute turn counter maps to a slot).
 *  - `normalizeAction` (TS): a null/empty action persists as `휴식`.
 *  - `normalizeArgs` (TS): a null/blank arg persists as the empty jsonb object `{}`.
 *  - [reserve] upserts the row (TS `setGeneralTurn` overwrites `turns[turnIndex]` then persists;
 *    against the `UNIQUE (general_id, turn_idx)` baseline this is an `ON CONFLICT ... DO UPDATE`,
 *    so re-reserving the same `(general, turnIdx)` slot never duplicates).
 *  - [readReserved] returns the stored entry, or the default `휴식` entry when no row exists
 *    (TS `buildTurnListFromRows` seeds every slot with `createDefaultEntry()` before overlaying rows).
 *
 * The write path is plain JDBC ([NamedParameterJdbcTemplate]) — NO JPA `EntityManager`
 * (design §0.1 #3; the F4 `InfraNoEntityManagerTest` guard enforces this for the persistence
 * write classes). `arg` is bound as a jsonb [PGobject].
 */
class ReservedTurnRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {

    /** A reserved turn slot: the normalized action code + the raw `arg` jsonb string. */
    data class ReservedTurn(val actionCode: String, val argJson: String)

    /**
     * Upsert the reserved action for `(generalId, turnIdx mod 30)`. Re-reserving the same slot
     * updates the existing row (no duplicate) via `ON CONFLICT (general_id, turn_idx)`.
     */
    fun reserve(generalId: Int, turnIdx: Int, actionCode: String?, argJson: String? = null) {
        val slot = ringIndex(turnIdx)
        val params = MapSqlParameterSource()
            .addValue("general_id", generalId)
            .addValue("turn_idx", slot)
            .addValue("action_code", normalizeAction(actionCode))
            .addValue("arg", jsonb(normalizeArgs(argJson)))
        jdbc.update(
            """
            INSERT INTO general_turn (general_id, turn_idx, action_code, arg)
            VALUES (:general_id, :turn_idx, :action_code, :arg)
            ON CONFLICT (general_id, turn_idx)
            DO UPDATE SET action_code = EXCLUDED.action_code,
                          arg = EXCLUDED.arg
            """.trimIndent(),
            params,
        )
    }

    /**
     * Read the reserved turn for `(generalId, turnIdx mod 30)`. Returns the default `휴식`
     * entry when no row exists (TS default-seeded ring buffer).
     */
    fun readReserved(generalId: Int, turnIdx: Int): ReservedTurn {
        val slot = ringIndex(turnIdx)
        val params = MapSqlParameterSource()
            .addValue("general_id", generalId)
            .addValue("turn_idx", slot)
        val rows = jdbc.query(
            """
            SELECT action_code, arg::text AS arg
              FROM general_turn
             WHERE general_id = :general_id AND turn_idx = :turn_idx
            """.trimIndent(),
            params,
        ) { rs, _ ->
            ReservedTurn(
                actionCode = normalizeAction(rs.getString("action_code")),
                argJson = normalizeArgs(rs.getString("arg")),
            )
        }
        return rows.firstOrNull() ?: ReservedTurn(DEFAULT_TURN_ACTION, EMPTY_ARG)
    }

    /** Map an absolute turn counter into the 0..29 ring slot (TS `MAX_GENERAL_TURNS`). */
    private fun ringIndex(turnIdx: Int): Int = ((turnIdx % MAX_GENERAL_TURNS) + MAX_GENERAL_TURNS) % MAX_GENERAL_TURNS

    /** TS `normalizeAction`: a null/empty action becomes the default `휴식`. */
    private fun normalizeAction(action: String?): String =
        if (action != null && action.isNotEmpty()) action else DEFAULT_TURN_ACTION

    /** TS `normalizeArgs`: a null/blank arg becomes the empty jsonb object `{}`. */
    private fun normalizeArgs(arg: String?): String {
        val trimmed = arg?.trim()
        return if (trimmed.isNullOrEmpty()) EMPTY_ARG else trimmed
    }

    private fun jsonb(json: String): PGobject {
        val obj = PGobject()
        obj.type = "jsonb"
        obj.value = json
        return obj
    }

    companion object {
        /** TS `DEFAULT_TURN_ACTION`. */
        const val DEFAULT_TURN_ACTION = "휴식"

        /** TS `MAX_GENERAL_TURNS` — the general ring-buffer length. */
        const val MAX_GENERAL_TURNS = 30

        private const val EMPTY_ARG = "{}"
    }
}
