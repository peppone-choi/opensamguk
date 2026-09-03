package db.migration

import opensamguk.infra.seed.EffectiveScenarioResolver
import opensamguk.infra.seed.Scenario
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context
import java.sql.Connection

class V48__rtk14_portrait_cutover : BaseJavaMigration() {
    override fun migrate(context: Context) {
        val connection = context.connection
        createMappingTable(connection)
        loadWorlds(connection).forEach { world ->
            if (!hasSourcePortraitRows(connection, world.id)) return@forEach
            val scenario = resolveEffectiveScenario(context, world)
            val mappings = try {
                portraitMappings(scenario)
            } catch (failure: IllegalArgumentException) {
                throw FlywayException(
                    "V48 found invalid RTK14 portrait mapping for world id=${world.id} scenario=${world.scenarioCode}",
                    failure,
                )
            }
            if (mappings.isEmpty()) {
                throw FlywayException(
                    "V48 found persisted RTK14 officers for world id=${world.id} but the effective scenario has no portrait mapping",
                )
            }
            replaceMappingRows(connection, mappings)
            updateCurrentGenerals(connection, world.id)
            updateDeferredGenerals(connection, world.id)
        }
    }

    private fun loadWorlds(connection: Connection): List<WorldRow> = connection.prepareStatement(
        "SELECT id, scenario_code FROM world_state ORDER BY id",
    ).use { statement ->
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(WorldRow(rs.getInt("id"), rs.getString("scenario_code")))
            }
        }
    }

    private fun hasSourcePortraitRows(connection: Connection, worldId: Int): Boolean = connection.prepareStatement(
        """
        SELECT EXISTS(
                   SELECT 1
                     FROM general
                    WHERE world_id = ?
                      AND COALESCE(meta ->> 'rtk14_officer_number', '') ~ '^[0-9]+$'
               )
            OR EXISTS(
                   SELECT 1
                     FROM event e
                     CROSS JOIN LATERAL jsonb_array_elements(
                         CASE WHEN jsonb_typeof(e.action) = 'array' THEN e.action ELSE '[]'::jsonb END
                     ) AS action_row(value)
                    WHERE e.world_id = ?
                      AND CASE
                              WHEN jsonb_typeof(action_row.value) = 'array'
                                  THEN jsonb_array_length(action_row.value) > 18
                                   AND action_row.value ->> 0 IN ('RegNPC', 'RegNeutralNPC')
                                   AND COALESCE(action_row.value ->> 18, '') ~ '^[0-9]+$'
                              ELSE false
                          END
               )
        """.trimIndent(),
    ).use { statement ->
        statement.setInt(1, worldId)
        statement.setInt(2, worldId)
        statement.executeQuery().use { rs ->
            rs.next()
            rs.getBoolean(1)
        }
    }

    private fun resolveEffectiveScenario(context: Context, world: WorldRow): Scenario = try {
        EffectiveScenarioResolver(
            scenarioDir = context.configuration.placeholders[SCENARIO_DIR_PLACEHOLDER].orEmpty(),
            classLoader = context.configuration.classLoader,
        ).resolve(world.scenarioCode)
    } catch (failure: Exception) {
        throw FlywayException(
            "V48 cannot resolve effective scenario ${world.scenarioCode} for world id=${world.id}",
            failure,
        )
    }

    private fun createMappingTable(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute(
                """
                CREATE TEMP TABLE IF NOT EXISTS v48_rtk14_portrait_mapping (
                    source_number integer PRIMARY KEY,
                    picture text NOT NULL UNIQUE
                ) ON COMMIT DROP
                """.trimIndent(),
            )
        }
    }

    private fun replaceMappingRows(connection: Connection, mappings: Map<Int, String>) {
        connection.createStatement().use { it.executeUpdate("TRUNCATE v48_rtk14_portrait_mapping") }
        connection.prepareStatement(
            "INSERT INTO v48_rtk14_portrait_mapping (source_number, picture) VALUES (?, ?)",
        ).use { statement ->
            mappings.forEach { (sourceNumber, picture) ->
                statement.setInt(1, sourceNumber)
                statement.setString(2, picture)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun updateCurrentGenerals(connection: Connection, worldId: Int) {
        connection.prepareStatement(
            """
            UPDATE general g
               SET picture = mapping.picture,
                   image_server = 0
              FROM v48_rtk14_portrait_mapping mapping
             WHERE g.world_id = ?
               AND COALESCE(g.meta ->> 'rtk14_officer_number', '') ~ '^[0-9]+$'
               AND (g.meta ->> 'rtk14_officer_number')::integer = mapping.source_number
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, worldId)
            statement.executeUpdate()
        }
    }

    private fun updateDeferredGenerals(connection: Connection, worldId: Int) {
        connection.prepareStatement(
            """
            WITH rewritten AS (
                SELECT e.id,
                       jsonb_agg(
                           CASE
                               WHEN mapping.picture IS NOT NULL
                                   THEN jsonb_set(action_row.value, '{3}', to_jsonb(mapping.picture), true)
                               ELSE action_row.value
                           END
                           ORDER BY action_row.ordinality
                       ) AS action
                  FROM event e
                  CROSS JOIN LATERAL jsonb_array_elements(
                      CASE WHEN jsonb_typeof(e.action) = 'array' THEN e.action ELSE '[]'::jsonb END
                  ) WITH ORDINALITY AS action_row(value, ordinality)
                  LEFT JOIN v48_rtk14_portrait_mapping mapping
                    ON mapping.source_number = CASE
                           WHEN jsonb_typeof(action_row.value) = 'array' THEN
                               CASE
                                   WHEN jsonb_array_length(action_row.value) > 18
                                    AND action_row.value ->> 0 IN ('RegNPC', 'RegNeutralNPC')
                                    AND COALESCE(action_row.value ->> 18, '') ~ '^[0-9]+$'
                                       THEN (action_row.value ->> 18)::integer
                                   ELSE NULL
                               END
                           ELSE NULL
                       END
                 WHERE e.world_id = ?
                 GROUP BY e.id
                HAVING bool_or(mapping.picture IS NOT NULL)
            )
            UPDATE event e
               SET action = rewritten.action
              FROM rewritten
             WHERE e.id = rewritten.id
            """.trimIndent(),
        ).use { statement ->
            statement.setInt(1, worldId)
            statement.executeUpdate()
        }
    }

    private data class WorldRow(val id: Int, val scenarioCode: String)

    companion object {
        private const val SOURCE_ID_MIN = 1
        private const val SOURCE_ID_MAX = 1000
        private const val PORTRAIT_ID_MIN = 10001
        private const val PORTRAIT_ID_MAX = 11000
        private const val SCENARIO_DIR_PLACEHOLDER = "scenario_dir"
        private val PORTRAIT_FILE = Regex("^(\\d{5})\\.png$")

        internal fun portraitMappings(scenario: Scenario): Map<Int, String> {
            val sourceRows = scenario.generals.filter { it.officerNumber != null }
            if (sourceRows.isEmpty()) return emptyMap()
            require(sourceRows.size == SOURCE_ID_MAX) {
                "RTK14 portrait mapping must contain exactly 1000 source officers"
            }
            val mapping = linkedMapOf<Int, String>()
            val portraitIds = linkedSetOf<Int>()
            sourceRows.forEach { general ->
                val sourceNumber = requireNotNull(general.officerNumber)
                require(sourceNumber in SOURCE_ID_MIN..SOURCE_ID_MAX) {
                    "RTK14 source officer number is outside 1..1000: $sourceNumber"
                }
                val picture = requireNotNull(general.picture) {
                    "RTK14 source officer $sourceNumber has no portrait picture"
                }
                val portraitId = PORTRAIT_FILE.matchEntire(picture)?.groupValues?.get(1)?.toInt()
                require(portraitId != null && portraitId in PORTRAIT_ID_MIN..PORTRAIT_ID_MAX) {
                    "RTK14 source officer $sourceNumber has invalid portrait picture: $picture"
                }
                require(mapping.put(sourceNumber, picture) == null) {
                    "duplicate RTK14 source officer number: $sourceNumber"
                }
                require(portraitIds.add(portraitId)) {
                    "duplicate RTK14 portrait stable ID: $portraitId"
                }
            }
            require(mapping.keys == (SOURCE_ID_MIN..SOURCE_ID_MAX).toSet()) {
                "RTK14 source officer numbers must be exactly 1..1000"
            }
            require(portraitIds == (PORTRAIT_ID_MIN..PORTRAIT_ID_MAX).toSet()) {
                "RTK14 portrait stable IDs must be exactly 10001..11000"
            }
            return mapping
        }
    }
}
