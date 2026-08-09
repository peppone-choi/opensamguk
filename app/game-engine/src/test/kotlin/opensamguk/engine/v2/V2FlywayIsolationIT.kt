package opensamguk.engine.v2

import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

internal const val V1_FLYWAY_LOCATION = "classpath:db/migration"
internal const val V2_FLYWAY_LOCATION = "classpath:db/migration_v2"
internal const val V2_SANDBOX_FLYWAY_LOCATIONS = "$V1_FLYWAY_LOCATION,$V2_FLYWAY_LOCATION"

@Testcontainers(disabledWithoutDocker = true)
class V2FlywayIsolationConstraintMutationIT {

    @Test
    fun `runtime guard rejects an unscoped unique constraint beside a world scoped primary key`() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val flyway = Flyway.configure()
            .dataSource(dataSource)
            .locations(V1_FLYWAY_LOCATION, V2_FLYWAY_LOCATION)
            .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
            .load()
        flyway.migrate()

        val jdbc = JdbcTemplate(dataSource)
        V2FlywayIsolationAssertions(flyway, dataSource).assertV2SandboxRuntime()
        jdbc.execute("ALTER TABLE v2_sandbox_probe ADD COLUMN external_code integer NOT NULL DEFAULT 0")
        jdbc.execute("ALTER TABLE v2_sandbox_probe ADD CONSTRAINT v2_sandbox_probe_external_code_key UNIQUE (external_code)")

        val error = assertFailsWith<AssertionError> {
            V2FlywayIsolationAssertions(flyway, dataSource).assertV2SandboxRuntime()
        }
        assertTrue(error.message.orEmpty().contains("every primary or unique key"))
    }

    private companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")
    }
}

internal class V2FlywayIsolationAssertions(
    private val flyway: Flyway,
    dataSource: DataSource,
) {
    private val jdbc = JdbcTemplate(dataSource)

    fun assertV1DefaultRuntime() {
        assertEquals(listOf(V1_FLYWAY_LOCATION), resolvedLocations(), "resolved v1 Flyway locations")
        assertFalse(probeTableExists(), "v1 defaults must not discover db/migration_v2")
        assertEquals(0, appliedProbeMigrations(), "v1 Flyway history must not contain V900")
        assertTrue(appliedV2Migrations().isEmpty(), "v1 Flyway must not apply any V900+ migration")
    }

    fun assertV2SandboxRuntime() {
        assertEquals(
            listOf(V1_FLYWAY_LOCATION, V2_FLYWAY_LOCATION),
            resolvedLocations(),
            "resolved v2 sandbox Flyway locations",
        )
        assertTrue(probeTableExists(), "the explicit v2 sibling location must apply V900")
        assertEquals(1, appliedProbeMigrations(), "the test-only V900 probe must be applied once")

        val appliedV2Migrations = appliedV2Migrations()
        assertTrue(
            appliedV2Migrations.any { it.script == V2_SANDBOX_PROBE_SCRIPT },
            "the applied V900 migration must be the test-only v2 sandbox probe",
        )
        val createdTables = appliedV2Migrations.flatMap { migration ->
            val source = V2MigrationSources.sourceForAppliedScript(migration.script)
            V2MigrationConvention.createdTables(source.readText()).map { table ->
                V2AppliedMigrationTable(migration.script, table)
            }
        }
        assertTrue(createdTables.isNotEmpty(), "applied v2 migrations must expose every CREATE TABLE to runtime checks")
        createdTables.forEach { applied -> assertWorldScoped(applied) }
    }

    private fun resolvedLocations(): List<String> = flyway.configuration.locations.map { it.descriptor }

    private fun appliedV2Migrations(): List<MigrationInfo> = flyway.info().applied().filter { migration ->
        migration.version?.toString()?.toIntOrNull()?.let { it >= V2MigrationConvention.MINIMUM_VERSION } == true
    }

    private fun assertWorldScoped(applied: V2AppliedMigrationTable) {
        val table = applied.table
        val label = "${applied.script} -> ${table.schema}.${table.name}"
        assertTrue(tableExists(table), "$label must exist after its applied migration")
        assertTrue(worldIdIsNotNull(table), "$label must keep world_id NOT NULL")
        assertTrue(everyPrimaryOrUniqueKeyContainsWorldId(table), "$label must scope every primary or unique key by world_id")
        assertTrue(worldIdReferencesWorldState(table), "$label must foreign-key world_id to world_state")
    }

    private fun probeTableExists(): Boolean = tableExists(V2CreatedTable("public", "v2_sandbox_probe"))

    private fun appliedProbeMigrations(): Int = jdbc.queryForObject(
        "SELECT count(*) FROM flyway_schema_history WHERE version = '900' AND success",
        Int::class.java,
    ) ?: 0

    private fun tableExists(table: V2CreatedTable): Boolean = queryBoolean(
        """
        SELECT EXISTS (
            SELECT 1
              FROM information_schema.tables
             WHERE table_schema = ?
               AND table_name = ?
        )
        """.trimIndent(),
        table.schema,
        table.name,
    )

    private fun worldIdIsNotNull(table: V2CreatedTable): Boolean = queryBoolean(
        """
        SELECT EXISTS (
            SELECT 1
              FROM information_schema.columns
             WHERE table_schema = ?
               AND table_name = ?
               AND column_name = 'world_id'
               AND is_nullable = 'NO'
        )
        """.trimIndent(),
        table.schema,
        table.name,
    )

    private fun everyPrimaryOrUniqueKeyContainsWorldId(table: V2CreatedTable): Boolean = queryBoolean(
        """
        SELECT EXISTS (
            SELECT 1
              FROM information_schema.table_constraints AS table_constraint
             WHERE table_constraint.table_schema = ?
               AND table_constraint.table_name = ?
               AND table_constraint.constraint_type IN ('PRIMARY KEY', 'UNIQUE')
        )
        AND NOT EXISTS (
            SELECT 1
              FROM information_schema.table_constraints AS table_constraint
             WHERE table_constraint.table_schema = ?
               AND table_constraint.table_name = ?
               AND table_constraint.constraint_type IN ('PRIMARY KEY', 'UNIQUE')
               AND NOT EXISTS (
                    SELECT 1
                      FROM information_schema.key_column_usage AS key_column
                     WHERE key_column.constraint_catalog = table_constraint.constraint_catalog
                       AND key_column.constraint_schema = table_constraint.constraint_schema
                       AND key_column.constraint_name = table_constraint.constraint_name
                       AND key_column.column_name = 'world_id'
               )
        )
        """.trimIndent(),
        table.schema,
        table.name,
        table.schema,
        table.name,
    )

    private fun worldIdReferencesWorldState(table: V2CreatedTable): Boolean = queryBoolean(
        """
        SELECT EXISTS (
            SELECT 1
              FROM information_schema.table_constraints AS table_constraint
              JOIN information_schema.key_column_usage AS key_column
                ON table_constraint.constraint_catalog = key_column.constraint_catalog
               AND table_constraint.constraint_schema = key_column.constraint_schema
               AND table_constraint.constraint_name = key_column.constraint_name
              JOIN information_schema.constraint_column_usage AS referenced_column
                ON table_constraint.constraint_catalog = referenced_column.constraint_catalog
               AND table_constraint.constraint_schema = referenced_column.constraint_schema
               AND table_constraint.constraint_name = referenced_column.constraint_name
             WHERE table_constraint.table_schema = ?
               AND table_constraint.table_name = ?
               AND table_constraint.constraint_type = 'FOREIGN KEY'
               AND key_column.column_name = 'world_id'
               AND referenced_column.table_schema = 'public'
               AND referenced_column.table_name = 'world_state'
        )
        """.trimIndent(),
        table.schema,
        table.name,
    )

    private fun queryBoolean(sql: String, vararg arguments: Any): Boolean =
        jdbc.queryForObject(sql, Boolean::class.java, *arguments) ?: false

    private data class V2AppliedMigrationTable(
        val script: String,
        val table: V2CreatedTable,
    )

    private companion object {
        const val V2_SANDBOX_PROBE_SCRIPT = "V900__v2_sandbox_probe.sql"
    }
}
