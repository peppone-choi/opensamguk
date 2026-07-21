package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

/**
 * `general_turn` ring-buffer reserved-turn repository (JDBC).
 *
 * Mirrors the TS `app/game-api/src/turns/reservedTurns.ts` `setGeneralTurn` semantics
 * (devsam-core2026), collapsed to a single-row upsert against the
 * scoped `UNIQUE (world_id, general_id, turn_idx)` constraint:
 *
 *  - [DEFAULT_TURN_ACTION] = `휴식` (TS `DEFAULT_TURN_ACTION`)
 *  - [MAX_GENERAL_TURNS] = 30 (TS `MAX_GENERAL_TURNS`) — the ring buffer length; `turn_idx`
 *    is normalized `mod 30` (TS `buildTurnListFromRows` drops out-of-range indices; here the
 *    index wraps into the 0..29 ring so a caller's absolute turn counter maps to a slot).
 *  - `normalizeAction` (TS): a null/empty action persists as `휴식`.
 *  - `normalizeArgs` (TS): a null/blank arg persists as the empty jsonb object `{}`.
 *  - [reserve] upserts the row (TS `setGeneralTurn` overwrites `turns[turnIndex]` then persists;
 *    against the scoped `UNIQUE (world_id, general_id, turn_idx)` constraint this is an
 *    `ON CONFLICT ... DO UPDATE`, so re-reserving the same `(world, general, turnIdx)` slot never
 *    duplicates).
 *  - [readReserved] returns the stored entry, or the default `휴식` entry when no row exists
 *    (TS `buildTurnListFromRows` seeds every slot with `createDefaultEntry()` before overlaying rows).
 *
 * The write path is plain JDBC ([NamedParameterJdbcTemplate]) — NO JPA `EntityManager`
 * (design §0.1 #3; the F4 `InfraNoEntityManagerTest` guard enforces this for the persistence
 * write classes). `arg` is bound as a jsonb [PGobject].
 */
open class ReservedTurnRepository(
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
     * Upsert the reserved action for `(worldId, generalId, turnIdx mod 30)`. Re-reserving the same
     * scoped slot updates the existing row (no duplicate) via
     * `ON CONFLICT (world_id, general_id, turn_idx)`.
     */
    open fun reserve(
        worldId: WorldId,
        generalId: Int,
        turnIdx: Int,
        actionCode: String?,
        argJson: String? = null,
        brief: String = DEFAULT_TURN_ACTION,
    ) {
        val slot = ringIndex(turnIdx)
        val params = MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("general_id", generalId)
            .addValue("turn_idx", slot)
            .addValue("action_code", normalizeAction(actionCode))
            .addValue("arg", jsonb(normalizeArgs(argJson)))
            .addValue("brief", brief)
        jdbc.update(
            """
            INSERT INTO general_turn (world_id, general_id, turn_idx, action_code, arg, brief)
            VALUES (:world_id, :general_id, :turn_idx, :action_code, :arg, :brief)
            ON CONFLICT (world_id, general_id, turn_idx)
            DO UPDATE SET action_code = EXCLUDED.action_code,
                          arg = EXCLUDED.arg,
                          brief = EXCLUDED.brief
            """.trimIndent(),
            params,
        )
    }

    /**
     * Read the reserved turn for `(worldId, generalId, turnIdx mod 30)`. Returns the default `휴식`
     * entry when no row exists (TS default-seeded ring buffer).
     */
    fun readReserved(worldId: WorldId, generalId: Int, turnIdx: Int): ReservedTurn {
        val slot = ringIndex(turnIdx)
        val params = MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("general_id", generalId)
            .addValue("turn_idx", slot)
        val rows = jdbc.query(
            """
            SELECT action_code, arg::text AS arg, brief
              FROM general_turn
             WHERE world_id = :world_id AND general_id = :general_id AND turn_idx = :turn_idx
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
    open fun pullGeneralTurn(worldId: WorldId, generalId: Int, turnCnt: Int = 1) {
        if (turnCnt == 0 || turnCnt >= MAX_GENERAL_TURNS) return
        val base = MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("general_id", generalId)
        jdbc.update(
            """
            UPDATE general_turn
               SET turn_idx = turn_idx + :offset
             WHERE world_id = :world_id AND general_id = :general_id
            """.trimIndent(),
            MapSqlParameterSource(base.values).addValue("offset", MAX_GENERAL_TURNS * 2),
        )
        jdbc.update(
            """
            UPDATE general_turn
               SET turn_idx = ((((turn_idx - :offset) % :max_turn) - :turn_cnt + :max_turn) % :max_turn),
                   action_code = CASE WHEN ((turn_idx - :offset) % :max_turn) < :turn_cnt THEN '휴식' ELSE action_code END,
                   arg = CASE WHEN ((turn_idx - :offset) % :max_turn) < :turn_cnt THEN '{}'::jsonb ELSE arg END,
                   brief = CASE WHEN ((turn_idx - :offset) % :max_turn) < :turn_cnt THEN '휴식' ELSE brief END
             WHERE world_id = :world_id AND general_id = :general_id
            """.trimIndent(),
            MapSqlParameterSource(base.values)
                .addValue("offset", MAX_GENERAL_TURNS * 2)
                .addValue("max_turn", MAX_GENERAL_TURNS)
                .addValue("turn_cnt", turnCnt),
        )
    }

    /**
     * Push(밀기) the general_turn ring — `func_command.php:31-54 pushGeneralCommand`를 그대로 옮긴 것.
     * 양수 turnCnt만 처리(음수는 호출부에서 [pullGeneralTurn]으로 분기, 0은 무시).
     *
     * PHP 두 UPDATE 순서를 그대로 보존:
     *  1. 모든 행을 turn_idx += turnCnt 만큼 아래로 민다(`ORDER BY turn_idx DESC` — UNIQUE 충돌 회피).
     *  2. MAX_GENERAL_TURNS 이상으로 밀려난 행을 turn_idx -= MAX 로 앞으로 되감고 휴식/{}/휴식으로 리셋.
     *
     * 가드: turnCnt <= 0 또는 turnCnt >= MAX_GENERAL_TURNS 이면 no-op(PHP `$turnCnt >= GameConst::$maxTurn`).
     * (turn_idx 정규화는 하지 않는다 — PHP는 절대 인덱스에 직접 산술한다.)
     */
    open fun pushGeneralTurn(worldId: WorldId, generalId: Int, turnCnt: Int) {
        if (turnCnt <= 0 || turnCnt >= MAX_GENERAL_TURNS) return
        val base = MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("general_id", generalId)
        jdbc.update(
            """
            UPDATE general_turn
               SET turn_idx = turn_idx + :offset
             WHERE world_id = :world_id AND general_id = :general_id
            """.trimIndent(),
            MapSqlParameterSource(base.values).addValue("offset", MAX_GENERAL_TURNS * 2),
        )
        jdbc.update(
            """
            UPDATE general_turn
               SET turn_idx = ((((turn_idx - :offset) % :max_turn) + :turn_cnt) % :max_turn),
                   action_code = CASE WHEN (((turn_idx - :offset) % :max_turn) + :turn_cnt) >= :max_turn THEN '휴식' ELSE action_code END,
                   arg = CASE WHEN (((turn_idx - :offset) % :max_turn) + :turn_cnt) >= :max_turn THEN '{}'::jsonb ELSE arg END,
                   brief = CASE WHEN (((turn_idx - :offset) % :max_turn) + :turn_cnt) >= :max_turn THEN '휴식' ELSE brief END
             WHERE world_id = :world_id AND general_id = :general_id
            """.trimIndent(),
            MapSqlParameterSource(base.values)
                .addValue("offset", MAX_GENERAL_TURNS * 2)
                .addValue("max_turn", MAX_GENERAL_TURNS)
                .addValue("turn_cnt", turnCnt),
        )
    }

    /**
     * Repeat(반복 채우기) the general_turn ring — `func_command.php:81-107 repeatGeneralCommand`를 그대로 옮긴 것.
     *
     * turn_idx 0..(reqTurn-1)의 각 행을 turnCnt, 2*turnCnt, … 만큼 앞으로 복제해 링 끝까지 채운다.
     * 원본 덮어쓰기 방지: turnCnt*2 > MAX 이면 reqTurn = MAX - turnCnt 로 클램프(PHP 동일).
     *
     * 가드: turnCnt <= 0 또는 turnCnt >= MAX_GENERAL_TURNS 이면 no-op.
     * 대상 인덱스 생성: PHP `Util::range($turnIdx+$turnCnt, MAX, $turnCnt)` = [start, MAX) step turnCnt
     * (range는 끝값 미포함 — turn_idx < MAX 인 슬롯만).
     */
    open fun repeatGeneralTurn(worldId: WorldId, generalId: Int, turnCnt: Int) {
        if (turnCnt <= 0 || turnCnt >= MAX_GENERAL_TURNS) return
        val reqTurn = if (turnCnt * 2 > MAX_GENERAL_TURNS) MAX_GENERAL_TURNS - turnCnt else turnCnt
        val sources = jdbc.query(
            """
            SELECT turn_idx, action_code, arg::text AS arg, brief
              FROM general_turn
             WHERE world_id = :world_id AND general_id = :general_id AND turn_idx < :req_turn
             ORDER BY turn_idx ASC
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("general_id", generalId)
                .addValue("req_turn", reqTurn),
        ) { rs, _ ->
            SourceTurn(
                turnIdx = rs.getInt("turn_idx"),
                actionCode = rs.getString("action_code"),
                argJson = rs.getString("arg"),
                brief = rs.getString("brief"),
            )
        }
        for (src in sources) {
            val targets = rangeTargets(src.turnIdx + turnCnt, MAX_GENERAL_TURNS, turnCnt)
            if (targets.isEmpty()) continue
            jdbc.update(
                """
                UPDATE general_turn
                   SET action_code = :action_code,
                       arg = :arg::jsonb,
                       brief = :brief
                 WHERE world_id = :world_id AND general_id = :general_id AND turn_idx IN (:targets)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("world_id", worldId.value)
                    .addValue("general_id", generalId)
                    .addValue("action_code", src.actionCode)
                    .addValue("arg", src.argJson)
                    .addValue("brief", src.brief)
                    .addValue("targets", targets),
            )
        }
    }

    // --- nation_turn ring (FF2) -----------------------------------------------------------------

    /**
     * Upsert the reserved nation command for `(nationId, officerLevel, turnIdx mod 12)`. Mirrors
     * [reserve] but on the chief ring (`nation_turn`, MAX_CHIEF_TURNS = 12, keyed
     * `(world_id, nation_id, officer_level, turn_idx)`). The `brief` rides the V2 `nation_turn.brief text`
     * column (PHP seeds `휴식` — `func_command.php`).
     */
    open fun reserveNationTurn(
        worldId: WorldId,
        nationId: Int,
        officerLevel: Int,
        turnIdx: Int,
        actionCode: String?,
        argJson: String? = null,
        brief: String = DEFAULT_TURN_ACTION,
    ) {
        val slot = nationRingIndex(turnIdx)
        val params = MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("nation_id", nationId)
            .addValue("officer_level", officerLevel)
            .addValue("turn_idx", slot)
            .addValue("action_code", normalizeAction(actionCode))
            .addValue("arg", jsonb(normalizeArgs(argJson)))
            .addValue("brief", brief)
        jdbc.update(
            """
            INSERT INTO nation_turn (world_id, nation_id, officer_level, turn_idx, action_code, arg, brief)
            VALUES (:world_id, :nation_id, :officer_level, :turn_idx, :action_code, :arg, :brief)
            ON CONFLICT (world_id, nation_id, officer_level, turn_idx)
            DO UPDATE SET action_code = EXCLUDED.action_code,
                          arg = EXCLUDED.arg,
                          brief = EXCLUDED.brief
            """.trimIndent(),
            params,
        )
    }

    /**
     * Read the reserved nation command for `(worldId, nationId, officerLevel, turnIdx mod 12)`.
     * Returns the default `휴식`/`{}` entry when no row exists.
     */
    fun readReservedNationTurn(
        worldId: WorldId,
        nationId: Int,
        officerLevel: Int,
        turnIdx: Int,
    ): ReservedTurn {
        val slot = nationRingIndex(turnIdx)
        val params = MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("nation_id", nationId)
            .addValue("officer_level", officerLevel)
            .addValue("turn_idx", slot)
        val rows = jdbc.query(
            """
            SELECT action_code, arg::text AS arg, brief
              FROM nation_turn
             WHERE world_id = :world_id
               AND nation_id = :nation_id
               AND officer_level = :officer_level
               AND turn_idx = :turn_idx
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
    open fun pullNationTurn(worldId: WorldId, nationId: Int, officerLevel: Int, turnCnt: Int = 1) {
        if (nationId == 0 || officerLevel < 5 || turnCnt == 0 || turnCnt >= MAX_CHIEF_TURNS) return
        val base = MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("nation_id", nationId)
            .addValue("officer_level", officerLevel)
        jdbc.update(
            """
            UPDATE nation_turn
               SET turn_idx = turn_idx + :offset
             WHERE world_id = :world_id AND nation_id = :nation_id AND officer_level = :officer_level
            """.trimIndent(),
            MapSqlParameterSource(base.values).addValue("offset", MAX_CHIEF_TURNS * 2),
        )
        jdbc.update(
            """
            UPDATE nation_turn
               SET turn_idx = ((((turn_idx - :offset) % :max_chief) - :turn_cnt + :max_chief) % :max_chief),
                   action_code = CASE WHEN ((turn_idx - :offset) % :max_chief) < :turn_cnt THEN '휴식' ELSE action_code END,
                   arg = CASE WHEN ((turn_idx - :offset) % :max_chief) < :turn_cnt THEN '{}'::jsonb ELSE arg END,
                   brief = CASE WHEN ((turn_idx - :offset) % :max_chief) < :turn_cnt THEN '휴식' ELSE brief END
             WHERE world_id = :world_id AND nation_id = :nation_id AND officer_level = :officer_level
            """.trimIndent(),
            MapSqlParameterSource(base.values)
                .addValue("offset", MAX_CHIEF_TURNS * 2)
                .addValue("max_chief", MAX_CHIEF_TURNS)
                .addValue("turn_cnt", turnCnt),
        )
    }

    /**
     * Push(밀기) the nation_turn ring — `func_command.php:109-138 pushNationCommand`를 그대로 옮긴 것.
     * 양수 turnCnt만 처리(음수는 호출부에서 [pullNationTurn]으로 분기, 0은 무시).
     *
     * 가드(PHP 순서 그대로): nationID==0 / officerLevel<5 / turnCnt==0 / turnCnt<0(분기) /
     * turnCnt >= MAX_CHIEF_TURNS → no-op. 두 UPDATE는 `nation_id AND officer_level`로 키한다.
     */
    open fun pushNationTurn(worldId: WorldId, nationId: Int, officerLevel: Int, turnCnt: Int) {
        if (nationId == 0 || officerLevel < 5 || turnCnt <= 0 || turnCnt >= MAX_CHIEF_TURNS) return
        val base = MapSqlParameterSource()
            .addValue("world_id", worldId.value)
            .addValue("nation_id", nationId)
            .addValue("officer_level", officerLevel)
        jdbc.update(
            """
            UPDATE nation_turn
               SET turn_idx = turn_idx + :offset
             WHERE world_id = :world_id AND nation_id = :nation_id AND officer_level = :officer_level
            """.trimIndent(),
            MapSqlParameterSource(base.values).addValue("offset", MAX_CHIEF_TURNS * 2),
        )
        jdbc.update(
            """
            UPDATE nation_turn
               SET turn_idx = ((((turn_idx - :offset) % :max_chief) + :turn_cnt) % :max_chief),
                   action_code = CASE WHEN (((turn_idx - :offset) % :max_chief) + :turn_cnt) >= :max_chief THEN '휴식' ELSE action_code END,
                   arg = CASE WHEN (((turn_idx - :offset) % :max_chief) + :turn_cnt) >= :max_chief THEN '{}'::jsonb ELSE arg END,
                   brief = CASE WHEN (((turn_idx - :offset) % :max_chief) + :turn_cnt) >= :max_chief THEN '휴식' ELSE brief END
             WHERE world_id = :world_id AND nation_id = :nation_id AND officer_level = :officer_level
            """.trimIndent(),
            MapSqlParameterSource(base.values)
                .addValue("offset", MAX_CHIEF_TURNS * 2)
                .addValue("max_chief", MAX_CHIEF_TURNS)
                .addValue("turn_cnt", turnCnt),
        )
    }

    /**
     * Repeat(반복 채우기) the nation_turn ring — `func_command.php:171-200 repeatNationCommand`를 그대로 옮긴 것.
     *
     * 가드: turnCnt <= 0 또는 turnCnt >= MAX_CHIEF_TURNS 이면 no-op. (PHP는 nationID/officerLevel을
     * repeat에서 가드하지 않는다 — 호출부 precheck가 책임. 충실 이식을 위해 동일하게 두지 않는다.)
     * 원본 덮어쓰기 방지: turnCnt*2 > MAX_CHIEF 이면 reqTurn = MAX_CHIEF - turnCnt.
     */
    open fun repeatNationTurn(worldId: WorldId, nationId: Int, officerLevel: Int, turnCnt: Int) {
        if (turnCnt <= 0 || turnCnt >= MAX_CHIEF_TURNS) return
        val reqTurn = if (turnCnt * 2 > MAX_CHIEF_TURNS) MAX_CHIEF_TURNS - turnCnt else turnCnt
        val sources = jdbc.query(
            """
            SELECT turn_idx, action_code, arg::text AS arg, brief
              FROM nation_turn
             WHERE world_id = :world_id
               AND nation_id = :nation_id
               AND officer_level = :officer_level
               AND turn_idx < :req_turn
             ORDER BY turn_idx ASC
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("world_id", worldId.value)
                .addValue("nation_id", nationId)
                .addValue("officer_level", officerLevel)
                .addValue("req_turn", reqTurn),
        ) { rs, _ ->
            SourceTurn(
                turnIdx = rs.getInt("turn_idx"),
                actionCode = rs.getString("action_code"),
                argJson = rs.getString("arg"),
                brief = rs.getString("brief"),
            )
        }
        for (src in sources) {
            val targets = rangeTargets(src.turnIdx + turnCnt, MAX_CHIEF_TURNS, turnCnt)
            if (targets.isEmpty()) continue
            jdbc.update(
                """
                UPDATE nation_turn
                   SET action_code = :action_code,
                       arg = :arg::jsonb,
                       brief = :brief
                 WHERE world_id = :world_id
                   AND nation_id = :nation_id
                   AND officer_level = :officer_level
                   AND turn_idx IN (:targets)
                """.trimIndent(),
                MapSqlParameterSource()
                    .addValue("world_id", worldId.value)
                    .addValue("nation_id", nationId)
                    .addValue("officer_level", officerLevel)
                    .addValue("action_code", src.actionCode)
                    .addValue("arg", src.argJson)
                    .addValue("brief", src.brief)
                    .addValue("targets", targets),
            )
        }
    }

    /** repeat 복제용 1행 스냅샷(읽은 원본 슬롯). */
    private data class SourceTurn(
        val turnIdx: Int,
        val actionCode: String,
        val argJson: String,
        val brief: String,
    )

    /**
     * PHP `Util::range(start, end, step)`와 동등 — [start, end) 범위를 step 간격으로(끝값 미포함).
     * step > 0, start..end 만 사용한다(repeat 전용). end 이상은 포함하지 않는다.
     */
    private fun rangeTargets(start: Int, end: Int, step: Int): List<Int> {
        val out = ArrayList<Int>()
        var t = start
        while (t < end) {
            out.add(t)
            t += step
        }
        return out
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
