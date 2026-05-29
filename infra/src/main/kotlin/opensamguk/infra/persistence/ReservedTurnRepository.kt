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

    /**
     * A reserved turn slot: the normalized action code + the raw `arg` jsonb string + the `brief`
     * (FD1: the V2 `general_turn.brief text` column — PHP seeds it `휴식` on every row,
     * `GeneralBuilder.php:720`). `brief` defaults to [DEFAULT_TURN_ACTION] to match the PHP seed.
     */
    data class ReservedTurn(
        val actionCode: String,
        val argJson: String,
        val brief: String = DEFAULT_TURN_ACTION,
    )

    /**
     * Upsert the reserved action for `(generalId, turnIdx mod 30)`. Re-reserving the same slot
     * updates the existing row (no duplicate) via `ON CONFLICT (general_id, turn_idx)`.
     */
    fun reserve(
        generalId: Int,
        turnIdx: Int,
        actionCode: String?,
        argJson: String? = null,
        brief: String = DEFAULT_TURN_ACTION,
    ) {
        val slot = ringIndex(turnIdx)
        val params = MapSqlParameterSource()
            .addValue("general_id", generalId)
            .addValue("turn_idx", slot)
            .addValue("action_code", normalizeAction(actionCode))
            .addValue("arg", jsonb(normalizeArgs(argJson)))
            .addValue("brief", brief)
        jdbc.update(
            """
            INSERT INTO general_turn (general_id, turn_idx, action_code, arg, brief)
            VALUES (:general_id, :turn_idx, :action_code, :arg, :brief)
            ON CONFLICT (general_id, turn_idx)
            DO UPDATE SET action_code = EXCLUDED.action_code,
                          arg = EXCLUDED.arg,
                          brief = EXCLUDED.brief
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
            SELECT action_code, arg::text AS arg, brief
              FROM general_turn
             WHERE general_id = :general_id AND turn_idx = :turn_idx
            """.trimIndent(),
            params,
        ) { rs, _ ->
            ReservedTurn(
                actionCode = normalizeAction(rs.getString("action_code")),
                argJson = normalizeArgs(rs.getString("arg")),
                brief = rs.getString("brief") ?: DEFAULT_TURN_ACTION,
            )
        }
        return rows.firstOrNull() ?: ReservedTurn(DEFAULT_TURN_ACTION, EMPTY_ARG)
    }

    /**
     * Pull (rotate) the general_turn ring after a general's turn runs — faithful to
     * `func_command.php:56-79 pullGeneralCommand` for the default `turnCnt = 1`:
     *  1. the run slot (turn_idx 0) gets `turn_idx += MAX_GENERAL_TURNS`, action/arg/brief reset to
     *     `휴식`/`{}`/`휴식` (the vacated slot rotates to the ring tail),
     *  2. every row then shifts down one (`turn_idx -= 1`, ordered turn_idx ASC).
     *
     * Net effect: slot 0 vacates to `휴식` at the tail (turn_idx 29) and slots 1..N shift down to
     * 0..N-1. No-op for `turnCnt == 0` / `turnCnt >= MAX_GENERAL_TURNS` (the PHP guards; the
     * negative-turnCnt push path is the inverse op, out of the LC3 ring-shift scope).
     */
    fun pullGeneralTurn(generalId: Int, turnCnt: Int = 1) {
        if (turnCnt == 0 || turnCnt >= MAX_GENERAL_TURNS) return
        val base = MapSqlParameterSource().addValue("general_id", generalId)
        // 1. reset the slots being pulled (turn_idx < turnCnt) to 휴식/{} and rotate them to the back.
        jdbc.update(
            """
            UPDATE general_turn
               SET turn_idx = turn_idx + :max_turn,
                   action_code = '휴식',
                   arg = '{}'::jsonb,
                   brief = '휴식'
             WHERE general_id = :general_id AND turn_idx < :turn_cnt
            """.trimIndent(),
            MapSqlParameterSource(base.values).addValue("max_turn", MAX_GENERAL_TURNS).addValue("turn_cnt", turnCnt),
        )
        // 2. shift every row down by turnCnt.
        jdbc.update(
            """
            UPDATE general_turn
               SET turn_idx = turn_idx - :turn_cnt
             WHERE general_id = :general_id
            """.trimIndent(),
            MapSqlParameterSource(base.values).addValue("turn_cnt", turnCnt),
        )
    }

    // --- nation_turn ring (FF2) -----------------------------------------------------------------

    /**
     * Upsert the reserved nation command for `(nationId, officerLevel, turnIdx mod 12)`. Mirrors
     * [reserve] but on the chief ring (`nation_turn`, MAX_CHIEF_TURNS = 12, keyed
     * `(nation_id, officer_level, turn_idx)`). The `brief` rides the V2 `nation_turn.brief text`
     * column (PHP seeds `휴식` — `func_command.php`).
     */
    fun reserveNationTurn(
        nationId: Int,
        officerLevel: Int,
        turnIdx: Int,
        actionCode: String?,
        argJson: String? = null,
        brief: String = DEFAULT_TURN_ACTION,
    ) {
        val slot = nationRingIndex(turnIdx)
        val params = MapSqlParameterSource()
            .addValue("nation_id", nationId)
            .addValue("officer_level", officerLevel)
            .addValue("turn_idx", slot)
            .addValue("action_code", normalizeAction(actionCode))
            .addValue("arg", jsonb(normalizeArgs(argJson)))
            .addValue("brief", brief)
        jdbc.update(
            """
            INSERT INTO nation_turn (nation_id, officer_level, turn_idx, action_code, arg, brief)
            VALUES (:nation_id, :officer_level, :turn_idx, :action_code, :arg, :brief)
            ON CONFLICT (nation_id, officer_level, turn_idx)
            DO UPDATE SET action_code = EXCLUDED.action_code,
                          arg = EXCLUDED.arg,
                          brief = EXCLUDED.brief
            """.trimIndent(),
            params,
        )
    }

    /**
     * Read the reserved nation command for `(nationId, officerLevel, turnIdx mod 12)`. Returns the
     * default `휴식`/`{}` entry when no row exists.
     */
    fun readReservedNationTurn(nationId: Int, officerLevel: Int, turnIdx: Int): ReservedTurn {
        val slot = nationRingIndex(turnIdx)
        val params = MapSqlParameterSource()
            .addValue("nation_id", nationId)
            .addValue("officer_level", officerLevel)
            .addValue("turn_idx", slot)
        val rows = jdbc.query(
            """
            SELECT action_code, arg::text AS arg, brief
              FROM nation_turn
             WHERE nation_id = :nation_id AND officer_level = :officer_level AND turn_idx = :turn_idx
            """.trimIndent(),
            params,
        ) { rs, _ ->
            ReservedTurn(
                actionCode = normalizeAction(rs.getString("action_code")),
                argJson = normalizeArgs(rs.getString("arg")),
                brief = rs.getString("brief") ?: DEFAULT_TURN_ACTION,
            )
        }
        return rows.firstOrNull() ?: ReservedTurn(DEFAULT_TURN_ACTION, EMPTY_ARG)
    }

    /**
     * Pull (rotate) the nation_turn ring after a chief turn runs — faithful to
     * `func_command.php:140-169 pullNationCommand` for the default `turnCnt = 1`:
     *  1. the run slot (turn_idx 0) gets `turn_idx += MAX_CHIEF_TURNS`, action/arg/brief reset to
     *     `휴식`/`{}`/`휴식` (the vacated slot rotates to the ring tail),
     *  2. every row then shifts down one (`turn_idx -= 1`, ordered turn_idx ASC).
     *
     * Net effect: slot 0 vacates to `휴식` at the tail (turn_idx 11) and slots 1..N shift down to
     * 0..N-1. No-op for `nationId == 0` / `officerLevel < 5` (the PHP guards).
     */
    fun pullNationTurn(nationId: Int, officerLevel: Int, turnCnt: Int = 1) {
        if (nationId == 0 || officerLevel < 5 || turnCnt == 0 || turnCnt >= MAX_CHIEF_TURNS) return
        val base = MapSqlParameterSource()
            .addValue("nation_id", nationId)
            .addValue("officer_level", officerLevel)
        // 1. reset the slots being pulled (turn_idx < turnCnt) to 휴식/{} and rotate them to the back.
        jdbc.update(
            """
            UPDATE nation_turn
               SET turn_idx = turn_idx + :max_chief,
                   action_code = '휴식',
                   arg = '{}'::jsonb,
                   brief = '휴식'
             WHERE nation_id = :nation_id AND officer_level = :officer_level AND turn_idx < :turn_cnt
            """.trimIndent(),
            MapSqlParameterSource(base.values).addValue("max_chief", MAX_CHIEF_TURNS).addValue("turn_cnt", turnCnt),
        )
        // 2. shift every row down by turnCnt.
        jdbc.update(
            """
            UPDATE nation_turn
               SET turn_idx = turn_idx - :turn_cnt
             WHERE nation_id = :nation_id AND officer_level = :officer_level
            """.trimIndent(),
            MapSqlParameterSource(base.values).addValue("turn_cnt", turnCnt),
        )
    }

    /** Map an absolute turn counter into the 0..29 ring slot (TS `MAX_GENERAL_TURNS`). */
    private fun ringIndex(turnIdx: Int): Int = ((turnIdx % MAX_GENERAL_TURNS) + MAX_GENERAL_TURNS) % MAX_GENERAL_TURNS

    /** Map an absolute chief turn counter into the 0..11 ring slot (`maxChiefTurn` = 12). */
    private fun nationRingIndex(turnIdx: Int): Int = ((turnIdx % MAX_CHIEF_TURNS) + MAX_CHIEF_TURNS) % MAX_CHIEF_TURNS

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

        /** PHP `GameConst::$maxChiefTurn` (= 12) — the nation_turn (chief) ring-buffer length. */
        const val MAX_CHIEF_TURNS = 12

        private const val EMPTY_ARG = "{}"
    }
}
