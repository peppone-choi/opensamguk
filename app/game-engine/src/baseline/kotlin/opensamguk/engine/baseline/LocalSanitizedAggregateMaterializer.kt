package opensamguk.engine.baseline

import java.sql.Connection
import javax.sql.DataSource

internal class LocalSanitizedAggregateMaterializer(
    private val dataSource: DataSource,
) {
    fun materialize(fixture: ProductionShapeFixtureConfig) {
        require(fixture.isLocalSanitizedAggregateSurrogate) {
            "Local materializer requires the local sanitized aggregate fixture kind"
        }
        requireLocalShape(fixture)
        dataSource.connection.use { connection ->
            val originalAutoCommit = connection.autoCommit
            connection.autoCommit = false
            try {
                insertWorldState(connection)
                insertGeneral(connection)
                insertReservedRestTurns(connection)
                insertLogs(connection, fixture)
                padPayload(
                    connection = connection,
                    table = "world_state",
                    sourceQuery = worldStateSourceQuery,
                    payloadExpression = worldStatePayloadExpression,
                    character = "W",
                    target = fixture.expectedLoaderInputs.getValue("worldState").payloadBytes,
                )
                padPayload(
                    connection = connection,
                    table = "general",
                    sourceQuery = generalSourceQuery,
                    payloadExpression = generalPayloadExpression,
                    character = "G",
                    target = fixture.expectedLoaderInputs.getValue("generals").payloadBytes,
                )
                connection.commit()
            } catch (exception: Exception) {
                connection.rollback()
                throw exception
            } finally {
                connection.autoCommit = originalAutoCommit
            }
        }
    }

    private fun requireLocalShape(fixture: ProductionShapeFixtureConfig) {
        require(fixture.fixedHotActionRows == 256) {
            "Local materializer requires exactly 256 hot action rows"
        }
        require(fixture.coldHistoryRows in setOf(10_000, 100_000)) {
            "Local materializer supports only the checked-in current and cold10x history sizes"
        }
        require(fixture.payloadSizeBytes == mapOf("hotAction" to 192, "coldHistory" to 192)) {
            "Local materializer requires 192-byte hot and cold payloads"
        }
        require(fixture.expectedTableCardinalities == expectedTableCardinalities(fixture.coldHistoryRows)) {
            "Local materializer table cardinalities do not match the deterministic policy"
        }
        require(fixture.expectedSnapshotCardinalities == expectedSnapshotCardinalities(fixture.coldHistoryRows)) {
            "Local materializer snapshot cardinalities do not match the deterministic policy"
        }
        require(fixture.expectedLoaderInputs == expectedLoaderInputs(fixture.coldHistoryRows)) {
            "Local materializer loader-input metrics do not match the deterministic policy"
        }
    }

    private fun insertWorldState(connection: Connection) {
        connection.createStatement().use { statement ->
            require(
                statement.executeUpdate(
                    """
                    INSERT INTO world_state (
                        id, scenario_code, current_year, current_month, current_phase, tick_seconds,
                        config, meta, start_year, start_time, turn_term, isunited, hidden_seed, status
                    ) VALUES (
                        1, 'op123_local_sanitized_aggregate', 184, 1, 1, 60,
                        '{"turnterm": 1}'::jsonb,
                        '{"hiddenSeed": "00000000000000000000000000000000", "startYear": 184, "startTime": "0184-01-01T00:00:00Z", "lastTurnTime": "0184-01-01T00:00:00Z", "maxNationId": 0, "maxGeneralId": 1, "localPadding": ""}'::jsonb,
                        184, TIMESTAMPTZ '0184-01-01 00:00:00+00', 1, 0,
                        '00000000000000000000000000000000', 'OPEN'
                    )
                    """.trimIndent(),
                ) == 1,
            ) { "Local materializer could not insert the singleton world_state row" }
        }
    }

    private fun insertGeneral(connection: Connection) {
        connection.createStatement().use { statement ->
            require(
                statement.executeUpdate(
                    """
                    INSERT INTO general (
                        id, name, nation_id, city_id, turn_time, meta, last_turn, penalty
                    ) VALUES (
                        1, 'OP123 local aggregate', 0, 0, TIMESTAMPTZ '0184-01-01 00:00:00+00',
                        '{"killturn": 0, "localPadding": ""}'::jsonb, '{}'::jsonb, '{}'::jsonb
                    )
                    """.trimIndent(),
                ) == 1,
            ) { "Local materializer could not insert the representative general" }
        }
    }

    private fun insertReservedRestTurns(connection: Connection) {
        connection.createStatement().use { statement ->
            require(
                statement.executeUpdate(
                    """
                    INSERT INTO general_turn (general_id, turn_idx, action_code, arg)
                    SELECT 1, series, '휴식', '{}'::jsonb
                      FROM generate_series(0, 29) AS series
                    """.trimIndent(),
                ) == 30,
            ) { "Local materializer could not insert the representative rest-turn ring" }
        }
    }

    private fun insertLogs(connection: Connection, fixture: ProductionShapeFixtureConfig) {
        connection.createStatement().use { statement ->
            require(
                statement.executeUpdate(
                    """
                    INSERT INTO log_entry (scope, category, year, month, text, meta)
                    SELECT CAST('SYSTEM' AS log_scope), CAST('ACTION' AS log_category), 184, 1,
                           repeat('H', 192), '{}'::jsonb
                      FROM generate_series(1, ${fixture.fixedHotActionRows}) AS series
                    """.trimIndent(),
                ) == fixture.fixedHotActionRows,
            ) { "Local materializer could not insert deterministic action logs" }
            require(
                statement.executeUpdate(
                    """
                    INSERT INTO log_entry (scope, category, year, month, text, meta)
                    SELECT CAST('SYSTEM' AS log_scope), CAST('HISTORY' AS log_category), 184, 1,
                           repeat('C', 192), '{}'::jsonb
                      FROM generate_series(1, ${fixture.coldHistoryRows}) AS series
                    """.trimIndent(),
                ) == fixture.coldHistoryRows,
            ) { "Local materializer could not insert deterministic history logs" }
        }
    }

    private fun padPayload(
        connection: Connection,
        table: String,
        sourceQuery: String,
        payloadExpression: String,
        character: String,
        target: Int,
    ) {
        val initial = payloadBytes(connection, sourceQuery, payloadExpression)
        val padding = target - initial
        require(padding >= 0) {
            "Local materializer $table payload base $initial exceeds deterministic target $target"
        }
        connection.prepareStatement(
            "UPDATE $table SET meta = jsonb_set(meta, '{localPadding}', to_jsonb(repeat(?, ?)), true) WHERE id = 1",
        ).use { statement ->
            statement.setString(1, character)
            statement.setInt(2, padding)
            require(statement.executeUpdate() == 1) { "Local materializer could not pad $table payload" }
        }
        require(payloadBytes(connection, sourceQuery, payloadExpression) == target) {
            "Local materializer $table payload did not reach deterministic target $target"
        }
    }

    private fun payloadBytes(connection: Connection, sourceQuery: String, payloadExpression: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery(
            "SELECT COALESCE(sum($payloadExpression), 0) AS payload_bytes FROM ($sourceQuery) AS source",
        ).use { rows ->
            require(rows.next()) { "Local materializer payload query returned no row" }
            rows.getLong("payload_bytes").also { bytes ->
                require(bytes in 0..Int.MAX_VALUE.toLong()) { "Local materializer payload is outside Int range" }
            }.toInt()
        }
    }

    companion object {
        private val expectedTableBase = linkedMapOf(
            "worldState" to 1,
            "city" to 0,
            "nation" to 0,
            "general" to 1,
            "diplomacy" to 0,
            "rankData" to 0,
        )
        private val expectedSnapshotBase = linkedMapOf(
            "generals" to 1,
            "cities" to 0,
            "nations" to 0,
            "diplomacy" to 0,
            "accessLogs" to 0,
            "nationHistoryEntries" to 0,
            "generalHistoryEntries" to 0,
        )
        private val zeroMetrics = listOf(
            "archivedNationIds",
            "statistics",
            "nationHistoryLogs",
            "generalHistoryLogs",
            "activeUniqueAuctionItems",
            "storedUniqueItemNamespaces",
            "gameEnv",
            "nationEnv",
            "inheritancePoints",
            "generalRankValues",
            "nations",
            "cities",
            "diplomacy",
            "generalAccessLogs",
        )
        private val worldStatePayloadExpression = payloadExpression(
            listOf(
                "id", "current_year", "current_month", "current_phase", "tick_seconds",
                "isunited", "status", "meta", "config", "start_time",
            ),
        )
        private val generalPayloadExpression = payloadExpression(
            listOf(
                "id", "name", "nation_id", "city_id", "troop_id", "npc_state", "affinity",
                "leadership", "strength", "intel", "politics", "charm", "experience", "dedication", "officer_level",
                "injury", "gold", "rice", "crew", "crew_type_id", "train", "atmos", "age",
                "weapon_code", "book_code", "horse_code", "item_code",
                "turn_time", "recent_war_time", "user_id", "born_year", "dead_year", "picture", "image_server",
                "start_age", "personal_code", "special_code", "special2_code", "officer_city",
                "last_turn", "penalty", "meta",
            ),
        )
        private const val worldStateSourceQuery = """
            SELECT id, current_year, current_month, current_phase, tick_seconds, isunited, status, meta, config, start_time
              FROM world_state ORDER BY id ASC LIMIT 1
        """
        private const val generalSourceQuery = """
            SELECT id, name, nation_id, city_id, troop_id, npc_state, affinity,
                   leadership, strength, intel, politics, charm, experience, dedication, officer_level,
                   injury, gold, rice, crew, crew_type_id, train, atmos, age,
                   weapon_code, book_code, horse_code, item_code,
                   turn_time, recent_war_time, user_id, born_year, dead_year, picture, image_server,
                   start_age, personal_code, special_code, special2_code, officer_city,
                   last_turn, penalty, meta
              FROM general ORDER BY id ASC
        """

        private fun expectedTableCardinalities(coldHistoryRows: Int): Map<String, Int> =
            LinkedHashMap(expectedTableBase).apply { this["logEntry"] = 256 + coldHistoryRows }

        private fun expectedSnapshotCardinalities(coldHistoryRows: Int): Map<String, Int> =
            LinkedHashMap(expectedSnapshotBase).apply { this["globalLogs"] = 256 + coldHistoryRows }

        private fun expectedLoaderInputs(coldHistoryRows: Int): Map<String, ExpectedLoaderInputMetric> =
            linkedMapOf<String, ExpectedLoaderInputMetric>().apply {
                this["worldState"] = ExpectedLoaderInputMetric(1, 1, 4096)
                this["ngGames"] = ExpectedLoaderInputMetric(0, 1, 1)
                for (inputId in zeroMetrics) this[inputId] = ExpectedLoaderInputMetric(0, 0, 0)
                this["systemActionLogs"] = ExpectedLoaderInputMetric(256, 256, 256 * 192)
                this["systemHistoryLogs"] = ExpectedLoaderInputMetric(coldHistoryRows, coldHistoryRows, coldHistoryRows * 192)
                this["generals"] = ExpectedLoaderInputMetric(1, 1, 4096)
            }

        private fun payloadExpression(columns: List<String>): String = columns.joinToString(" + ") { column ->
            "COALESCE(octet_length((source.\"$column\")::text), 0)"
        }
    }
}
