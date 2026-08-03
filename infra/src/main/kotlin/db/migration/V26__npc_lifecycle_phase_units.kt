package db.migration

import opensamguk.common.constants.GameConst
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.EffectiveScenarioResolver
import opensamguk.infra.seed.ScenarioGeneral
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V26__npc_lifecycle_phase_units : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        val world = loadWorld(connection)
        val scenario = world
            ?.takeIf { hasNpcStateRows(connection) }
            ?.let { resolveEffectiveScenario(context, it) }

        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                UPDATE general
                   SET meta = jsonb_set(
                       jsonb_set(meta, '{killturn}', to_jsonb(((meta ->> 'killturn')::integer * 3)), true),
                       '{killturn_unit}', '"phase"'::jsonb, true
                   )
                 WHERE npc_state >= 2
                   AND meta ? 'killturn'
                   AND (meta ->> 'killturn') ~ '^-?[0-9]+$'
                   AND COALESCE(meta ->> 'killturn_unit', '') <> 'phase'
                """.trimIndent(),
            )
        }

        world ?: return
        scenario ?: run {
            refreshNationGeneralCounts(connection)
            return
        }
        val underage = loadUnderageRows(connection, world.currentYear)
        val seedGenerals = scenario.seedGenerals(world.extendedGeneral)
        val candidates = seedGenerals
            .groupBy { ScenarioIdentity(it.name, it.nationId) }
        val underageMatched = underage.map { row ->
            val matches = scenarioMatches(row, candidates[ScenarioIdentity(row.name, row.nationId)].orEmpty())
            if (matches.size != 1) {
                throw FlywayException(
                    "V26 cannot safely defer general id=${row.id} name=${row.name}: " +
                        "expected one bundled scenario tuple, found ${matches.size}",
                )
            }
            row to matches.single()
        }
        // tuple[24] proves that this source row was active in the legacy start world before RTK14
        // enrichment moved its explicit appearance into the future. Without that marker, an adult
        // database row is not a safe migration candidate.
        val futureAppearanceCandidates = seedGenerals
            .filter { it.appearanceYear?.let { year -> year > world.currentYear } == true }
            .filter { it.legacyActiveAtStart == true }
            .groupBy { ScenarioIdentity(it.name, it.nationId) }
        val futureAdultMatched = loadFutureAppearanceRows(
            connection,
            world.currentYear,
            futureAppearanceCandidates.keys.toList(),
        )
            .groupBy { ScenarioIdentity(it.name, it.nationId) }
            .flatMap { (identity, rows) ->
                rows.singleOrNull()?.let { row ->
                    scenarioMatches(row, futureAppearanceCandidates[identity].orEmpty())
                        .singleOrNull()
                        ?.let { general -> listOf(row to general) }
                        .orEmpty()
                }.orEmpty()
            }
        val matched = (underageMatched + futureAdultMatched)
            .distinctBy { (row, _) -> row.id }
            .sortedBy { (row, _) -> row.id }
        if (matched.isEmpty()) {
            refreshNationGeneralCounts(connection)
            return
        }

        val existingNames = loadDeferredGeneralNames(connection)
        matched
            .filterNot { (_, general) -> general.name in existingNames }
            .groupBy { (_, general) ->
                general.appearanceYear
                    ?: (general.bornYear ?: DEFAULT_BIRTH_YEAR) + GameConst.adultAge.toInt()
            }
            .toSortedMap()
            .forEach { (appearanceYear, rows) ->
                insertDeferredEvent(connection, appearanceYear, rows.map { it.second })
            }

        removeVerifiedLegacyRows(connection, matched.map { it.first.id })
        refreshNationGeneralCounts(connection)
    }

    private fun loadWorld(connection: Connection): WorldRow? = connection.prepareStatement(
        """
        SELECT scenario_code,
               current_year,
               COALESCE(
                   NULLIF(config ->> 'extended_general', '')::boolean,
                   NULLIF(meta ->> 'extended_general', '')::boolean,
                   true
               ) AS extended_general
          FROM world_state
         ORDER BY id
         LIMIT 1
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { rs ->
            if (!rs.next()) null else WorldRow(
                scenarioCode = rs.getString("scenario_code"),
                currentYear = rs.getInt("current_year"),
                extendedGeneral = rs.getBoolean("extended_general"),
            )
        }
    }

    private fun hasNpcStateRows(connection: Connection): Boolean = connection.prepareStatement(
        "SELECT EXISTS(SELECT 1 FROM general WHERE npc_state >= 2)",
    ).use { statement ->
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
                "V26 found npc_state >= 2 rows but effective scenario/${world.scenarioCode}.json " +
                    "cannot be resolved; refusing data loss",
                failure,
            )
        }

    private fun loadUnderageRows(connection: Connection, currentYear: Int): List<LegacyGeneralRow> =
        connection.prepareStatement(
            """
            SELECT id, name, nation_id, born_year
              FROM general
             WHERE npc_state >= 2
               AND ? - born_year < ?
             ORDER BY id
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, currentYear)
            statement.setInt(2, GameConst.adultAge.toInt())
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

    private fun loadFutureAppearanceRows(
        connection: Connection,
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
             WHERE g.npc_state >= 2
               AND ? - g.born_year >= ?
             ORDER BY g.id
            """.trimIndent(),
        ).use { statement ->
            var parameterIndex = 1
            identities.forEach { identity ->
                statement.setString(parameterIndex++, identity.name)
                statement.setInt(parameterIndex++, identity.nationId)
            }
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
        // RTK14 source enrichment rewrites tuple birth years. A pre-V26 row has no RTK metadata,
        // so only accept the identity fallback when one source-provenanced RTK candidate exists.
        return if (exactMatches.isNotEmpty()) {
            exactMatches
        } else {
            identityMatches.takeIf { it.size == 1 && it.single().officerNumber != null }.orEmpty()
        }
    }

    private fun loadDeferredGeneralNames(connection: Connection): Set<String> = connection.prepareStatement(
        """
        SELECT action_row ->> 2 AS name
          FROM event
         CROSS JOIN LATERAL jsonb_array_elements(action) action_row
         WHERE action_row ->> 0 IN ('RegNPC', 'RegNeutralNPC')
        """.trimIndent(),
    ).use { statement ->
        statement.executeQuery().use { rs ->
            buildSet {
                while (rs.next()) rs.getString("name")?.let(::add)
            }
        }
    }

    private fun insertDeferredEvent(connection: Connection, appearanceYear: Int, generals: List<ScenarioGeneral>) {
        val actions = generals.map { general ->
            listOf(if (general.npcType == 6) "RegNeutralNPC" else "RegNPC") + general.rawTuple
        } + listOf(listOf("DeleteEvent"))
        val condition = listOf("Date", ">=", appearanceYear, "1")
        connection.prepareStatement(
            """
            INSERT INTO event (target_code, priority, condition, action)
            VALUES ('Month', 1000, CAST(? AS jsonb), CAST(? AS jsonb))
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, MetaJson.encode(condition))
            statement.setString(2, MetaJson.encode(actions))
            statement.executeUpdate()
        }
    }

    private fun removeVerifiedLegacyRows(connection: Connection, ids: List<Int>) {
        connection.createArrayOf("integer", ids.toTypedArray()).also { idArray ->
            connection.prepareStatement(
                "CREATE TEMP TABLE deferred_underage_generals ON COMMIT DROP AS SELECT unnest(CAST(? AS integer[])) AS id",
            ).use { statement ->
                statement.setArray(1, idArray)
                statement.executeUpdate()
            }
            idArray.free()
        }
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                UPDATE general
                   SET troop_id = 0
                 WHERE id IN (SELECT id FROM deferred_underage_generals)
                    OR troop_id IN (
                        SELECT id FROM troop WHERE troop_leader IN (SELECT id FROM deferred_underage_generals)
                    )
                """.trimIndent(),
            )
            statement.executeUpdate(
                "DELETE FROM troop WHERE troop_leader IN (SELECT id FROM deferred_underage_generals)",
            )
            statement.executeUpdate(
                "DELETE FROM general_access_log WHERE general_id IN (SELECT id FROM deferred_underage_generals)",
            )
            statement.executeUpdate(
                "DELETE FROM general_owner WHERE general_id IN (SELECT id FROM deferred_underage_generals)",
            )
            statement.executeUpdate(
                "UPDATE select_pool SET general_id = NULL WHERE general_id IN (SELECT id FROM deferred_underage_generals)",
            )
            statement.executeUpdate(
                "DELETE FROM general_turn WHERE general_id IN (SELECT id FROM deferred_underage_generals)",
            )
            statement.executeUpdate(
                "DELETE FROM rank_data WHERE general_id IN (SELECT id FROM deferred_underage_generals)",
            )
            statement.executeUpdate(
                "DELETE FROM general WHERE id IN (SELECT id FROM deferred_underage_generals)",
            )
        }
    }

    private fun refreshNationGeneralCounts(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.executeUpdate(
                """
                UPDATE nation n
                   SET meta = jsonb_set(
                       n.meta,
                       '{gennum}',
                       to_jsonb((SELECT count(*)::integer FROM general g WHERE g.nation_id = n.id AND g.npc_state <> 5)),
                       true
                   )
                """.trimIndent(),
            )
        }
    }

    private data class WorldRow(
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

    private data class ScenarioIdentity(val name: String, val nationId: Int)

    companion object {
        private const val DEFAULT_BIRTH_YEAR = 180
        private const val SCENARIO_DIR_PLACEHOLDER = "scenario_dir"
    }
}
