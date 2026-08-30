package db.migration

import opensamguk.common.constants.GameConst
import opensamguk.infra.persistence.MetaJson
import opensamguk.infra.seed.ScenarioGeneral
import opensamguk.infra.seed.ScenarioJson
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.nio.charset.StandardCharsets
import java.sql.Connection

class V26__npc_lifecycle_phase_units : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
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

        val world = loadWorld(connection) ?: return
        val underage = loadUnderageRows(connection, world.currentYear)
        if (underage.isEmpty()) {
            refreshNationGeneralCounts(connection)
            return
        }

        val scenario = loadScenario(world.scenarioCode)
        val candidates = scenario.seedGenerals(world.extendedGeneral)
            .groupBy { ScenarioKey(it.name, it.nationId, it.bornYear ?: DEFAULT_BIRTH_YEAR) }
        val matched = underage.map { row ->
            val canonicalName = LEGACY_IMPERIAL_NAMES[row.name] ?: row.name
            val matches = candidates[ScenarioKey(canonicalName, row.nationId, row.bornYear)].orEmpty()
            if (matches.size != 1) {
                throw FlywayException(
                    "V26 cannot safely defer general id=${row.id} name=${row.name}: " +
                        "expected one bundled scenario tuple, found ${matches.size}",
                )
            }
            row to matches.single()
        }

        val existingNames = loadDeferredGeneralNames(connection)
        matched
            .filterNot { (_, general) -> general.name in existingNames }
            .groupBy { (_, general) -> general.bornYear ?: DEFAULT_BIRTH_YEAR }
            .toSortedMap()
            .forEach { (birthYear, rows) ->
                insertDeferredEvent(connection, birthYear, rows.map { it.second })
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

    private fun loadScenario(scenarioCode: String) =
        javaClass.classLoader.getResourceAsStream("scenario/$scenarioCode.json")?.use { stream ->
            ScenarioJson.loadScenario(stream.readBytes().toString(StandardCharsets.UTF_8))
        } ?: throw FlywayException(
            "V26 found underage NPC rows but bundled scenario/$scenarioCode.json is unavailable; refusing data loss",
        )

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

    private fun insertDeferredEvent(connection: Connection, birthYear: Int, generals: List<ScenarioGeneral>) {
        val actions = generals.map { general ->
            listOf(if (general.npcType == 6) "RegNeutralNPC" else "RegNPC") + general.rawTuple
        } + listOf(listOf("DeleteEvent"))
        val condition = listOf("Date", ">=", birthYear + GameConst.adultAge.toInt(), "1")
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

    private data class ScenarioKey(val name: String, val nationId: Int, val bornYear: Int)

    companion object {
        private val LEGACY_IMPERIAL_NAMES = mapOf("소제1" to "유변", "헌제" to "유협")
        private const val DEFAULT_BIRTH_YEAR = 180
    }
}
