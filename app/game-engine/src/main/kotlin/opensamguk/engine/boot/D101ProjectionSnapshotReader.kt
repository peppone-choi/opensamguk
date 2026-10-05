package opensamguk.engine.boot

import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import javax.sql.DataSource

/**
 * Reads the D101 membership/clock projection from one PostgreSQL MVCC snapshot.
 * This is a read seam, not a selected-source issuer or an operational proof.
 * The caller must supply independently verified source pins and process observations.
 */
class D101ProjectionSnapshotReader(private val dataSource: DataSource) {
    data class Snapshot(
        val world: Map<String, Any>,
        val seedSettings: Map<String, Any>,
        val generals: List<List<Any?>>,
        val nations: List<List<Any?>>,
        val cities: List<List<Any?>>,
        val positions: List<List<Any?>>,
        val retainers: List<List<Any?>>,
        val rawLastTurnTime: Instant,
        val worldVersion: Long,
        val writerEpoch: Long,
    )

    /** B0 rows before the first flush; lastTurnTime does not exist in the seed row yet. */
    data class SeedMembership(
        val generals: List<List<Any?>>,
        val retainers: List<List<Any?>>,
        val extendedGeneral: Boolean,
        val persistedStartTime: Instant,
    )

    fun captureSeedMembership(startTimeUtc: Instant, effectiveResetExtend: Int): SeedMembership {
        require(effectiveResetExtend == 0 || effectiveResetExtend == 1) { "effective RESET_EXTEND must be explicit 0 or 1" }
        dataSource.connection.use { connection ->
            connection.isReadOnly = true
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            try {
                val result = captureSeedMembershipInTransaction(connection, startTimeUtc, effectiveResetExtend)
                connection.rollback()
                return result
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun captureSeedMembershipInTransaction(
        connection: Connection,
        startTimeUtc: Instant,
        effectiveResetExtend: Int,
    ): SeedMembership {
        connection.prepareStatement("""
            SELECT scenario_code, current_year, current_month, current_phase, tick_seconds,
                   start_time, meta ->> 'startTime' AS meta_start_time,
                   (config ->> 'extended_general')::boolean AS extended_general
              FROM world_state WHERE id = 1
        """.trimIndent()).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "D101 seed world row is unavailable" }
                check(rs.getString("scenario_code") == "scenario_3190" && rs.getInt("current_year") == 190 &&
                    rs.getInt("current_month") == 1 && rs.getInt("current_phase") == 1 &&
                    rs.getInt("tick_seconds") == 3600) { "D101 seed world settings differ" }
                val persistedStart = rs.getObject("start_time", OffsetDateTime::class.java)?.toInstant()
                    ?: error("seed start_time is unavailable")
                val metaStart = rs.getString("meta_start_time")?.let { OffsetDateTime.parse(it).toInstant() }
                    ?: error("seed meta.startTime is unavailable")
                check(persistedStart == startTimeUtc && metaStart == persistedStart) {
                    "seed start time differs from persisted anchors"
                }
                val extended = rs.getObject("extended_general") as? Boolean
                    ?: error("seed extended_general is unavailable")
                check(extended == (effectiveResetExtend == 1)) { "seed RESET_EXTEND differs from selected option" }
                check(!rs.next()) { "D101 seed world selection is not unique" }
                val generals = rows(connection, """
                    SELECT id, name, nation_id, city_id, npc_state,
                           (user_id IS NOT NULL AND user_id <> '') AS human_owned
                      FROM general WHERE world_id = 1 ORDER BY id
                """.trimIndent(), 6)
                val retainers = rows(connection, """
                    SELECT id, master_general_id, general_id, name, origin, relation, role,
                           release_policy, has_own_bugok, loyalty, task
                      FROM general_retainers WHERE world_id = 1 ORDER BY id
                """.trimIndent(), 11)
                return SeedMembership(generals, retainers, extended, persistedStart)
            }
        }
    }

    /** B0, typed generation and RESET_EXTEND are caller-supplied inputs; this reader never supplies defaults. */
    fun capture(
        boundaryAtUtc: Instant,
        typedGeneration: String,
        effectiveResetExtend: Int,
    ): Snapshot {
        require(typedGeneration.matches(Regex("0|[1-9][0-9]*"))) { "typed generation is required" }
        require(effectiveResetExtend == 0 || effectiveResetExtend == 1) { "effective RESET_EXTEND must be explicit 0 or 1" }
        dataSource.connection.use { connection ->
            connection.isReadOnly = true
            connection.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            connection.autoCommit = false
            try {
                val result = captureInTransaction(connection, boundaryAtUtc, typedGeneration, effectiveResetExtend)
                connection.rollback() // A read-only snapshot must never issue a write commit.
                return result
            } catch (failure: Exception) {
                connection.rollback()
                throw failure
            }
        }
    }

    private fun captureInTransaction(
        connection: Connection,
        boundaryAtUtc: Instant,
        typedGeneration: String,
        effectiveResetExtend: Int,
    ): Snapshot {
        val state = one(connection, """
            SELECT scenario_code, current_year, current_month, current_phase, tick_seconds,
                   meta ->> 'lastTurnTime' AS raw_last_turn_time,
                   (config ->> 'maxgeneral')::int AS maxgeneral,
                   (config ->> 'block_general_create')::int AS block_general_create,
                   config ->> 'firstTurnPolicy' AS first_turn,
                   (config ->> 'extended_general')::boolean AS extended_general,
                   meta ->> 'server_generation' AS server_generation,
                   world_version, writer_epoch
              FROM world_state WHERE id = 1
        """.trimIndent())
        val rawClock = Instant.parse(requiredString(state, "raw_last_turn_time"))
        check(rawClock == boundaryAtUtc) { "raw persisted clock differs from first boundary" }
        val tickSeconds = requiredInt(state, "tick_seconds")
        check(tickSeconds == 3600) { "D101 tick must be 3600 seconds" }
        val dbGeneration = state["server_generation"]?.toString()
        if (dbGeneration != null) {
            check(dbGeneration == typedGeneration) { "typed generation differs from world_state meta" }
        }
        // Fresh 3190 seeds do not persist meta.server_generation. In that case generation below
        // is only the required typed target candidate; this snapshot cannot prove DB generation
        // agreement or be promoted to an actual issuer proof until a canonical persisted source
        // and its provenance are supplied. Never infer an observed zero from the missing key.
        val configExtended = requiredBoolean(state, "extended_general")
        check(configExtended == (effectiveResetExtend == 1)) { "effective RESET_EXTEND differs from world config" }
        val env = gameEnv(connection)
        val maxGeneral = requiredInt(state, "maxgeneral")
        val blockCreate = requiredInt(state, "block_general_create")
        check(maxGeneral == 50 && blockCreate == 1 && requiredString(state, "first_turn") == "immediate") {
            "D101 config differs from approved 50/blocked/immediate settings"
        }
        check(env.getValue("maxgeneral") == "50" && env.getValue("block_general_create") == "1") {
            "D101 game_env differs from world config"
        }
        check(env.getValue("extended_general") == (effectiveResetExtend == 1).toString()) {
            "effective RESET_EXTEND differs from game_env"
        }
        val world = mapOf(
            "worldId" to 1,
            "generation" to typedGeneration,
            "scenarioCode" to requiredString(state, "scenario_code"),
            "currentYear" to requiredInt(state, "current_year"),
            "currentMonth" to requiredInt(state, "current_month"),
            "currentPhase" to requiredInt(state, "current_phase"),
            "tickSeconds" to tickSeconds,
            "lastTurnOffsetNanos" to Duration.between(boundaryAtUtc, rawClock).toNanos().toString(),
            "nextBoundaryOffsetNanos" to Duration.ofSeconds(tickSeconds.toLong()).toNanos().toString(),
        )
        check(world["scenarioCode"] == "scenario_3190" && world["currentYear"] == 190) {
            "D101 world is not scenario_3190 at year 190"
        }
        val settings = mapOf(
            "maxGeneralConfig" to maxGeneral,
            "maxGeneralGameEnv" to env.getValue("maxgeneral").toInt(),
            "blockGeneralCreateConfig" to blockCreate,
            "blockGeneralCreateGameEnv" to env.getValue("block_general_create").toInt(),
            "firstTurn" to requiredString(state, "first_turn"),
            "extendedGeneral" to configExtended,
        )
        val generals = rows(connection, """
            SELECT id, name, nation_id, city_id, npc_state,
                   (user_id IS NOT NULL AND user_id <> '') AS human_owned
              FROM general WHERE world_id = 1 ORDER BY id
        """.trimIndent(), 6)
        val nations = rows(connection, """
            SELECT id, name, capital_city_id, type_code
              FROM nation WHERE world_id = 1 ORDER BY id
        """.trimIndent(), 4)
        val cities = rows(connection, """
            SELECT id, name, nation_id FROM city WHERE world_id = 1 ORDER BY id
        """.trimIndent(), 3)
        val positions = rows(connection, """
            SELECT general_id, topology_revision, topology_hash, node_kind, node_id, revision
              FROM general_spatial_position WHERE world_id = 1 ORDER BY general_id
        """.trimIndent(), 6)
        val retainers = rows(connection, """
            SELECT id, master_general_id, general_id, name, origin, relation, role,
                   release_policy, has_own_bugok, loyalty, task
              FROM general_retainers WHERE world_id = 1 ORDER BY id
        """.trimIndent(), 11)
        return Snapshot(
            world, settings, generals, nations, cities, positions, retainers, rawClock,
            requiredLong(state, "world_version"), requiredLong(state, "writer_epoch"),
        )
    }

    private fun gameEnv(connection: Connection): Map<String, String> {
        val result = mutableMapOf<String, String>()
        connection.prepareStatement("""
            SELECT key, value #>> '{}' AS scalar
              FROM game_kv
             WHERE world_id = 1 AND "table" = 'game_env' AND namespace = 'game_env'
               AND key IN ('maxgeneral', 'block_general_create', 'extended_general')
        """.trimIndent()).use { statement ->
            statement.executeQuery().use { rs ->
                while (rs.next()) {
                    val key = rs.getString("key")
                    check(result.put(key, rs.getString("scalar") ?: error("game_env $key is null")) == null)
                }
            }
        }
        check(result.keys == setOf("maxgeneral", "block_general_create", "extended_general")) {
            "required game_env settings are missing"
        }
        return result
    }

    private fun one(connection: Connection, sql: String): Map<String, Any?> =
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                check(rs.next()) { "D101 world row is unavailable" }
                val values = (1..rs.metaData.columnCount).associate { index ->
                    rs.metaData.getColumnLabel(index) to rs.getObject(index)
                }
                check(!rs.next()) { "D101 world selection is not unique" }
                values
            }
        }

    private fun rows(connection: Connection, sql: String, width: Int): List<List<Any?>> =
        connection.prepareStatement(sql).use { statement ->
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) add((1..width).map { index -> rs.getObject(index) })
                }
            }
        }

    private fun requiredString(row: Map<String, Any?>, key: String): String =
        (row[key] as? String)?.takeIf { it.isNotBlank() } ?: error("$key is unavailable")

    private fun requiredInt(row: Map<String, Any?>, key: String): Int =
        (row[key] as? Number)?.toInt() ?: error("$key is unavailable")

    private fun requiredLong(row: Map<String, Any?>, key: String): Long =
        (row[key] as? Number)?.toLong() ?: error("$key is unavailable")

    private fun requiredBoolean(row: Map<String, Any?>, key: String): Boolean =
        row[key] as? Boolean ?: error("$key is unavailable")
}
