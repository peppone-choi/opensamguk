package db.migration

import opensamguk.common.constants.GameConst
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.EffectiveScenarioResolver
import opensamguk.infra.seed.ScenarioGeneral
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V38__rtk14_npc_lifecycle_repair : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        for (world in loadWorlds(connection)) {
            val legacyDeferredActions = loadLegacyDeferredActions(connection, world.id)
            val hasNpcStateRows = hasNpcStateRows(connection, world.id)
            if (!hasNpcStateRows && legacyDeferredActions.isEmpty()) continue

            val scenario = resolveEffectiveScenario(context, world)
            val seedGenerals = scenario.seedGenerals(world.extendedGeneral)
            reconcileLegacyDeferredActions(connection, world, seedGenerals, legacyDeferredActions)
            if (!hasNpcStateRows) continue

            val futureAppearanceCandidates = seedGenerals
                .filter { it.appearanceYear?.let { year -> year > world.currentYear } == true }
                .filter { it.legacyActiveAtStart == true }
                .groupBy { ScenarioIdentity(it.name, it.nationId) }
            val matched = loadFutureAppearanceRows(
                connection,
                world.id,
                world.currentYear,
                futureAppearanceCandidates.keys.toList(),
            )
                .groupBy { ScenarioIdentity(it.name, it.nationId) }
                .flatMap { (identity, rows) ->
                    if (rows.size != 1) {
                        throw FlywayException(
                            "V38 cannot safely defer future appearance worldId=${world.id} " +
                                "name=${identity.name} nationId=${identity.nationId}: " +
                                "expected one database row, found ${rows.size}",
                        )
                    }
                    val row = rows.single()
                    scenarioMatches(row, futureAppearanceCandidates[identity].orEmpty())
                        .singleOrNull()
                        ?.let { general -> listOf(row to general) }
                        .orEmpty()
                }
                .sortedBy { (row, _) -> row.id }
            if (matched.isEmpty()) continue

            val existingIdentities = loadDeferredGeneralIdentities(connection, world.id)
            matched
                .filterNot { (_, general) -> ScenarioIdentity(general.name, general.nationId) in existingIdentities }
                .groupBy { (_, general) ->
                    general.appearanceYear
                        ?: (general.bornYear ?: DEFAULT_BIRTH_YEAR) + GameConst.adultAge.toInt()
                }
                .toSortedMap()
                .forEach { (appearanceYear, rows) ->
                    insertDeferredEvent(connection, world.id, appearanceYear, rows.map { it.second })
                }

            removeVerifiedLegacyRows(connection, world.id, matched.map { it.first.id })
            refreshNationGeneralCounts(connection, world.id)
        }
    }

    private fun loadWorlds(connection: Connection): List<WorldRow> = connection.prepareStatement(
        """
        SELECT id,
               scenario_code,
               current_year,
               COALESCE(
                   NULLIF(config ->> 'extended_general', '')::boolean,
                   NULLIF(meta ->> 'extended_general', '')::boolean,
                   true
               ) AS extended_general
          FROM world_state
         ORDER BY id
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    add(
                        WorldRow(
                            id = rs.getInt("id"),
                            scenarioCode = rs.getString("scenario_code"),
                            currentYear = rs.getInt("current_year"),
                            extendedGeneral = rs.getBoolean("extended_general"),
                        ),
                    )
                }
            }
        }
    }

    private fun hasNpcStateRows(connection: Connection, worldId: Int): Boolean = connection.prepareStatement(
        "SELECT EXISTS(SELECT 1 FROM general WHERE world_id = ? AND npc_state >= 2)",
    ).use { statement ->
        statement.setInt(1, worldId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

    private fun resolveEffectiveScenario(context: Context, world: WorldRow) =
        try {
            EffectiveScenarioResolver(
                scenarioDir = context.configuration.placeholders[SCENARIO_DIR_PLACEHOLDER].orEmpty(),
                classLoader = context.configuration.classLoader,
            ).resolve(world.scenarioCode)
        } catch (failure: Exception) {
            throw FlywayException(
                "V38 found repair candidates in world id=${world.id} but effective " +
                    "scenario/${world.scenarioCode}.json cannot be resolved; refusing data loss",
                failure,
            )
        }

    private fun loadFutureAppearanceRows(
        connection: Connection,
        worldId: Int,
        currentYear: Int,
        identities: List<ScenarioIdentity>,
    ): List<LegacyGeneralRow> {
        if (identities.isEmpty()) return emptyList()
        val values = identities.joinToString(", ") { "(?, ?)" }
        return connection.prepareStatement(
            """
            SELECT g.id, g.name, g.nation_id, g.born_year
              FROM general g
              JOIN (VALUES $values) AS scenario_identity(name, nation_id)
                ON g.name = scenario_identity.name
               AND g.nation_id = scenario_identity.nation_id
             WHERE g.world_id = ?
               AND g.npc_state >= 2
               AND ? - g.born_year >= ?
             ORDER BY g.id
            """.trimIndent(),
        ).use { statement ->
            var parameterIndex = 1
            identities.forEach { identity ->
                statement.setString(parameterIndex++, identity.name)
                statement.setInt(parameterIndex++, identity.nationId)
            }
            statement.setInt(parameterIndex++, worldId)
            statement.setInt(parameterIndex++, currentYear)
            statement.setInt(parameterIndex, GameConst.adultAge.toInt())
            statement.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            LegacyGeneralRow(
                                id = rs.getInt("id"),
                                name = rs.getString("name"),
                                nationId = rs.getInt("nation_id"),
                                bornYear = rs.getInt("born_year"),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun scenarioMatches(
        row: LegacyGeneralRow,
        identityMatches: List<ScenarioGeneral>,
    ): List<ScenarioGeneral> {
        val exactMatches = identityMatches.filter { (it.bornYear ?: DEFAULT_BIRTH_YEAR) == row.bornYear }
        return if (exactMatches.isNotEmpty()) {
            exactMatches
        } else {
            identityMatches.takeIf { it.size == 1 && it.single().officerNumber != null }.orEmpty()
        }
    }

    private fun loadLegacyDeferredActions(connection: Connection, worldId: Int): List<LegacyDeferredAction> = connection.prepareStatement(
        """
        SELECT e.id AS event_id,
               action_row.ordinality AS action_index,
               CASE
                   WHEN jsonb_typeof(e.action) = 'array'
                       THEN jsonb_array_length(e.action) - 1
                   ELSE 0
               END AS deferred_action_count,
               action_row.value ->> 0 AS action_name,
               action_row.value ->> 2 AS name,
               action_row.value ->> 4 AS nation_id,
               action_row.value ->> 10 AS born_year
          FROM event e
         CROSS JOIN LATERAL jsonb_array_elements(
             CASE WHEN jsonb_typeof(e.action) = 'array' THEN e.action ELSE '[]'::jsonb END
         ) WITH ORDINALITY AS action_row(value, ordinality)
         WHERE e.world_id = ?
           AND e.target_code = 'Month'
           AND e.priority = 1000
           AND CASE
                   WHEN jsonb_typeof(e.condition) = 'array'
                       THEN jsonb_array_length(e.condition) = 4
                   ELSE false
               END
           AND e.condition ->> 0 = 'Date'
           AND e.condition ->> 1 = '>='
           AND e.condition ->> 3 = '1'
           AND CASE
                   WHEN jsonb_typeof(e.action) = 'array'
                       THEN jsonb_array_length(e.action) >= 2
                   ELSE false
               END
           AND CASE
                   WHEN jsonb_typeof(e.action) = 'array'
                       THEN e.action -> -1 = '["DeleteEvent"]'::jsonb
                   ELSE false
               END
           AND action_row.ordinality < CASE
                                           WHEN jsonb_typeof(e.action) = 'array'
                                               THEN jsonb_array_length(e.action)
                                           ELSE 0
                                       END
           AND COALESCE(action_row.value ->> 0 IN ('RegNPC', 'RegNeutralNPC'), false)
           AND COALESCE((e.condition ->> 2) ~ '^-?[0-9]+$', false)
           AND NOT EXISTS (
               SELECT 1
                 FROM jsonb_array_elements(
                     CASE WHEN jsonb_typeof(e.action) = 'array' THEN e.action ELSE '[]'::jsonb END
                 ) WITH ORDINALITY AS candidate(value, ordinality)
                WHERE candidate.ordinality < CASE
                                                   WHEN jsonb_typeof(e.action) = 'array'
                                                       THEN jsonb_array_length(e.action)
                                                   ELSE 0
                                               END
                  AND NOT (
                      CASE
                          WHEN jsonb_typeof(candidate.value) = 'array'
                              THEN jsonb_array_length(candidate.value) = 15
                          ELSE false
                      END
                      AND COALESCE(candidate.value ->> 0 IN ('RegNPC', 'RegNeutralNPC'), false)
                      AND candidate.value ->> 2 IS NOT NULL
                      AND COALESCE((candidate.value ->> 4) ~ '^-?[0-9]+$', false)
                      AND COALESCE((candidate.value ->> 10) ~ '^-?[0-9]+$', false)
                      AND CASE
                              WHEN COALESCE((candidate.value ->> 10) ~ '^-?[0-9]+$', false)
                                  AND COALESCE((e.condition ->> 2) ~ '^-?[0-9]+$', false)
                                  THEN (candidate.value ->> 10)::integer + ? = (e.condition ->> 2)::integer
                              ELSE false
                          END
                  )
           )
         ORDER BY e.id, action_row.ordinality
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, worldId)
        statement.setInt(2, GameConst.adultAge.toInt())
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) {
                    val name = rs.getString("name")
                    val nationId = rs.getString("nation_id")?.toIntOrNull()
                    val bornYear = rs.getString("born_year")?.toIntOrNull()
                    if (name != null && nationId != null && bornYear != null) {
                        add(
                            LegacyDeferredAction(
                                eventId = rs.getInt("event_id"),
                                actionIndex = rs.getInt("action_index"),
                                deferredActionCount = rs.getInt("deferred_action_count"),
                                actionName = rs.getString("action_name"),
                                name = name,
                                nationId = nationId,
                                bornYear = bornYear,
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun reconcileLegacyDeferredActions(
        connection: Connection,
        world: WorldRow,
        seedGenerals: List<ScenarioGeneral>,
        legacyDeferredActions: List<LegacyDeferredAction>,
    ) {
        if (legacyDeferredActions.isEmpty()) return
        val rtkCandidates = seedGenerals
            .filter { it.officerNumber != null && !it.rtk14Added }
            .groupBy { ScenarioIdentity(it.name, it.nationId) }
        val reconciled = legacyDeferredActions.mapNotNull { action ->
            val identity = ScenarioIdentity(action.name, action.nationId)
            val identityMatches = rtkCandidates[identity].orEmpty()
            if (identityMatches.isEmpty()) return@mapNotNull null
            val matches = scenarioMatches(
                LegacyGeneralRow(0, action.name, action.nationId, action.bornYear),
                identityMatches,
            )
            if (matches.size != 1) {
                throw FlywayException(
                    "V38 cannot safely reconcile deferred event id=${action.eventId} " +
                        "name=${action.name} nationId=${action.nationId}: " +
                        "expected one effective scenario tuple, found ${matches.size}",
                )
            }
            val general = matches.single()
            val expectedActionName = actionName(general)
            if (action.actionName != expectedActionName) {
                throw FlywayException(
                    "V38 cannot safely reconcile deferred event id=${action.eventId} " +
                        "name=${action.name} nationId=${action.nationId}: " +
                        "expected $expectedActionName, found ${action.actionName}",
                )
            }
            ReconciledDeferredAction(action, general)
        }
        if (reconciled.isEmpty()) return
        reconciled.groupBy { ScenarioIdentity(it.general.name, it.general.nationId) }.forEach { (identity, rows) ->
            if (rows.size != 1) {
                throw FlywayException(
                    "V38 cannot safely reconcile deferred name=${identity.name} nationId=${identity.nationId}: " +
                        "expected one legacy action, found ${rows.size}",
                )
            }
        }

        reconciled
            .filterNot { hasCurrentDeferredAction(connection, world.id, it.general) }
            .groupBy { scheduledYear(it.general) }
            .toSortedMap()
            .forEach { (appearanceYear, rows) ->
                insertDeferredEvent(connection, world.id, appearanceYear, rows.map { it.general })
            }
        removeReconciledLegacyActions(connection, world.id, reconciled.map { it.action })
    }

    private fun hasCurrentDeferredAction(
        connection: Connection,
        worldId: Int,
        general: ScenarioGeneral,
    ): Boolean = connection.prepareStatement(
        """
        SELECT EXISTS(
            SELECT 1
              FROM event e
             CROSS JOIN LATERAL jsonb_array_elements(
                 CASE WHEN jsonb_typeof(e.action) = 'array' THEN e.action ELSE '[]'::jsonb END
             ) action_row
             WHERE e.world_id = ?
               AND e.target_code = 'Month'
               AND e.priority = 1000
               AND e.condition = CAST(? AS jsonb)
               AND action_row = CAST(? AS jsonb)
        )
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, worldId)
        statement.setString(2, MetaJson.encode(listOf("Date", ">=", scheduledYear(general), "1")))
        statement.setString(3, MetaJson.encode(listOf(actionName(general)) + general.rawTuple))
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

    private fun removeReconciledLegacyActions(
        connection: Connection,
        worldId: Int,
        actions: List<LegacyDeferredAction>,
    ) {
        actions.groupBy { it.eventId }.forEach { (eventId, eventActions) ->
            if (eventActions.size == eventActions.first().deferredActionCount) {
                connection.prepareStatement("DELETE FROM event WHERE world_id = ? AND id = ?").use { statement ->
                    statement.setInt(1, worldId)
                    statement.setInt(2, eventId)
                    statement.executeUpdate()
                }
            } else {
                val indexes = connection.createArrayOf("bigint", eventActions.map { it.actionIndex.toLong() }.toTypedArray())
                try {
                    connection.prepareStatement(
                        """
                        UPDATE event e
                           SET action = (
                               SELECT jsonb_agg(action_row.value ORDER BY action_row.ordinality)
                                 FROM jsonb_array_elements(e.action) WITH ORDINALITY AS action_row(value, ordinality)
                                WHERE action_row.ordinality <> ALL(CAST(? AS bigint[]))
                           )
                         WHERE e.world_id = ?
                           AND e.id = ?
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setArray(1, indexes)
                        statement.setInt(2, worldId)
                        statement.setInt(3, eventId)
                        statement.executeUpdate()
                    }
                } finally {
                    indexes.free()
                }
            }
        }
    }

    private fun scheduledYear(general: ScenarioGeneral): Int =
        general.appearanceYear ?: (general.bornYear ?: DEFAULT_BIRTH_YEAR) + GameConst.adultAge.toInt()

    private fun actionName(general: ScenarioGeneral): String =
        if (general.npcType == 6) "RegNeutralNPC" else "RegNPC"

    private fun loadDeferredGeneralIdentities(connection: Connection, worldId: Int): Set<ScenarioIdentity> = connection.prepareStatement(
        """
        SELECT action_row ->> 4 AS nation_id,
               action_row ->> 2 AS name
          FROM event
         CROSS JOIN LATERAL jsonb_array_elements(
             CASE WHEN jsonb_typeof(action) = 'array' THEN action ELSE '[]'::jsonb END
         ) action_row
         WHERE world_id = ?
           AND action_row ->> 0 IN ('RegNPC', 'RegNeutralNPC')
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, worldId)
        statement.executeQuery().use { rs ->
            buildSet {
                while (rs.next()) {
                    val nationId = rs.getString("nation_id")?.toIntOrNull()
                    val name = rs.getString("name")
                    if (nationId != null && name != null) add(ScenarioIdentity(name, nationId))
                }
            }
        }
    }

    private fun insertDeferredEvent(
        connection: Connection,
        worldId: Int,
        appearanceYear: Int,
        generals: List<ScenarioGeneral>,
    ) {
        val actions = generals.map { general -> listOf(actionName(general)) + general.rawTuple } + listOf(listOf("DeleteEvent"))
        val condition = listOf("Date", ">=", appearanceYear, "1")
        connection.prepareStatement(
            """
            INSERT INTO event (world_id, target_code, priority, condition, action)
            VALUES (?, 'Month', 1000, CAST(? AS jsonb), CAST(? AS jsonb))
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, worldId)
            statement.setString(2, MetaJson.encode(condition))
            statement.setString(3, MetaJson.encode(actions))
            statement.executeUpdate()
        }
    }

    private fun removeVerifiedLegacyRows(connection: Connection, worldId: Int, ids: List<Int>) {
        val idArray = connection.createArrayOf("integer", ids.toTypedArray())
        try {
            connection.prepareStatement(
                """
                UPDATE general
                   SET troop_id = 0
                 WHERE world_id = ?
                   AND (
                       id = ANY(CAST(? AS integer[]))
                       OR troop_id IN (
                           SELECT id
                             FROM troop
                            WHERE world_id = ?
                              AND troop_leader = ANY(CAST(? AS integer[]))
                       )
                   )
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, worldId)
                statement.setArray(2, idArray)
                statement.setInt(3, worldId)
                statement.setArray(4, idArray)
                statement.executeUpdate()
            }
            deleteByGeneralIds(connection, "DELETE FROM troop WHERE world_id = ? AND troop_leader = ANY(CAST(? AS integer[]))", worldId, idArray)
            deleteByGeneralIds(connection, "DELETE FROM general_access_log WHERE world_id = ? AND general_id = ANY(CAST(? AS integer[]))", worldId, idArray)
            deleteByGeneralIds(connection, "DELETE FROM general_owner WHERE world_id = ? AND general_id = ANY(CAST(? AS integer[]))", worldId, idArray)
            connection.prepareStatement(
                "UPDATE select_pool SET general_id = NULL WHERE world_id = ? AND general_id = ANY(CAST(? AS integer[]))",
            ).use { statement ->
                statement.setInt(1, worldId)
                statement.setArray(2, idArray)
                statement.executeUpdate()
            }
            deleteByGeneralIds(connection, "DELETE FROM general_turn WHERE world_id = ? AND general_id = ANY(CAST(? AS integer[]))", worldId, idArray)
            deleteByGeneralIds(connection, "DELETE FROM rank_data WHERE world_id = ? AND general_id = ANY(CAST(? AS integer[]))", worldId, idArray)
            deleteByGeneralIds(connection, "DELETE FROM general WHERE world_id = ? AND id = ANY(CAST(? AS integer[]))", worldId, idArray)
        } finally {
            idArray.free()
        }
    }

    private fun deleteByGeneralIds(
        connection: Connection,
        sql: String,
        worldId: Int,
        idArray: java.sql.Array,
    ) {
        connection.prepareStatement(sql).use { statement ->
            statement.setInt(1, worldId)
            statement.setArray(2, idArray)
            statement.executeUpdate()
        }
    }

    private fun refreshNationGeneralCounts(connection: Connection, worldId: Int) {
        connection.prepareStatement(
            """
            UPDATE nation n
               SET meta = jsonb_set(
                   n.meta,
                   '{gennum}',
                   to_jsonb((
                       SELECT count(*)::integer
                         FROM general g
                        WHERE g.world_id = n.world_id
                          AND g.nation_id = n.id
                          AND g.npc_state <> 5
                   )),
                   true
               )
             WHERE n.world_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, worldId)
            statement.executeUpdate()
        }
    }

    private data class WorldRow(
        val id: Int,
        val scenarioCode: String,
        val currentYear: Int,
        val extendedGeneral: Boolean,
    )

    private data class LegacyGeneralRow(
        val id: Int,
        val name: String,
        val nationId: Int,
        val bornYear: Int,
    )

    private data class LegacyDeferredAction(
        val eventId: Int,
        val actionIndex: Int,
        val deferredActionCount: Int,
        val actionName: String,
        val name: String,
        val nationId: Int,
        val bornYear: Int,
    )

    private data class ReconciledDeferredAction(
        val action: LegacyDeferredAction,
        val general: ScenarioGeneral,
    )

    private data class ScenarioIdentity(val name: String, val nationId: Int)

    companion object {
        private const val DEFAULT_BIRTH_YEAR = 180
        private const val SCENARIO_DIR_PLACEHOLDER = "scenario_dir"
    }
}
