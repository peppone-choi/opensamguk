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

internal fun v1PersistentTableBaseline(dataSource: DataSource): Set<V2CatalogRelation> {
    Flyway.configure()
        .dataSource(dataSource)
        .locations(V1_FLYWAY_LOCATION)
        .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
        .load()
        .migrate()
    return V2FlywayIsolationAssertions.persistentTableRelations(dataSource)
}

private fun v2SandboxFlyway(dataSource: DataSource): Flyway = Flyway.configure()
    .dataSource(dataSource)
    .locations(V1_FLYWAY_LOCATION, V2_FLYWAY_LOCATION)
    .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
    .load()

@Testcontainers(disabledWithoutDocker = true)
class V2FlywayIsolationConstraintMutationIT {

    @Test
    fun `runtime guard rejects an unscoped standalone unique index and accepts a scoped one`() {
        val sandbox = fixture
        val jdbc = JdbcTemplate(sandbox.dataSource)
        sandbox.assertions().assertV2SandboxRuntime()

        try {
            jdbc.execute("ALTER TABLE v2_sandbox_probe ADD COLUMN external_code integer NOT NULL DEFAULT 0")
            jdbc.execute("CREATE UNIQUE INDEX v2_sandbox_probe_external_code_unique ON v2_sandbox_probe (external_code)")

            val error = assertFailsWith<AssertionError> {
                sandbox.assertions().assertV2SandboxRuntime()
            }
            assertTrue(error.message.orEmpty().contains("world_id"))

            jdbc.execute("DROP INDEX v2_sandbox_probe_external_code_unique")
            jdbc.execute(
                "CREATE UNIQUE INDEX v2_sandbox_probe_world_external_code_unique " +
                    "ON v2_sandbox_probe (world_id, external_code)",
            )
            sandbox.assertions().assertV2SandboxRuntime()
        } finally {
            jdbc.execute("DROP INDEX IF EXISTS v2_sandbox_probe_external_code_unique")
            jdbc.execute("DROP INDEX IF EXISTS v2_sandbox_probe_world_external_code_unique")
            jdbc.execute("ALTER TABLE v2_sandbox_probe DROP COLUMN IF EXISTS external_code")
        }
        sandbox.assertions().assertV2SandboxRuntime()
    }

    @Test
    fun `runtime guard rejects a v2 foreign key when a same named decoy points at world state`() {
        val sandbox = fixture
        val jdbc = JdbcTemplate(sandbox.dataSource)
        sandbox.assertions().assertV2SandboxRuntime()

        try {
            jdbc.execute("ALTER TABLE v2_sandbox_probe DROP CONSTRAINT v2_sandbox_probe_world_id_fkey")
            jdbc.execute("ALTER TABLE nation ADD CONSTRAINT v2_foreign_key_wrong_target_id_key UNIQUE (id)")
            jdbc.execute(
                "ALTER TABLE nation ADD CONSTRAINT v2_sandbox_probe_world_id_fkey " +
                    "FOREIGN KEY (world_id) REFERENCES world_state(id)",
            )
            jdbc.execute(
                "ALTER TABLE v2_sandbox_probe ADD CONSTRAINT v2_sandbox_probe_world_id_fkey " +
                    "FOREIGN KEY (world_id) REFERENCES nation(id)",
            )
            val error = assertFailsWith<AssertionError> {
                sandbox.assertions().assertV2SandboxRuntime()
            }
            assertTrue(error.message.orEmpty().contains("world_state.id"))
        } finally {
            jdbc.execute("ALTER TABLE v2_sandbox_probe DROP CONSTRAINT IF EXISTS v2_sandbox_probe_world_id_fkey")
            jdbc.execute(
                "ALTER TABLE v2_sandbox_probe ADD CONSTRAINT v2_sandbox_probe_world_id_fkey " +
                    "FOREIGN KEY (world_id) REFERENCES world_state(id)",
            )
            jdbc.execute("ALTER TABLE nation DROP CONSTRAINT IF EXISTS v2_sandbox_probe_world_id_fkey")
            jdbc.execute("ALTER TABLE nation DROP CONSTRAINT IF EXISTS v2_foreign_key_wrong_target_id_key")
        }
        sandbox.assertions().assertV2SandboxRuntime()
    }

    @Test
    fun `runtime catalog diff rejects a table created by dynamic SQL`() {
        val sandbox = fixture
        val jdbc = JdbcTemplate(sandbox.dataSource)
        val dynamicTableSql = """
            DO ${'$'}${'$'}
            BEGIN
                EXECUTE 'CREATE TABLE v2_catalog_dynamic_unscoped (external_code integer NOT NULL PRIMARY KEY)';
            END
            ${'$'}${'$'};
        """.trimIndent()

        assertEquals(emptyList(), V2MigrationConvention.createdTables(dynamicTableSql))
        jdbc.execute(dynamicTableSql)

        try {
            assertTrue(
                V2FlywayIsolationAssertions.persistentTableRelations(sandbox.dataSource).any { relation ->
                    relation.table == V2CreatedTable("public", "v2_catalog_dynamic_unscoped") && relation.kind == "r"
                },
                "pg_class catalog diff must include the dynamic ordinary relation",
            )
            val error = assertFailsWith<AssertionError> {
                sandbox.assertions().assertV2SandboxRuntime()
            }
            assertTrue(error.message.orEmpty().contains("world_id"))
            assertTrue(error.message.orEmpty().contains("v2_catalog_dynamic_unscoped"))
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS v2_catalog_dynamic_unscoped")
        }
        sandbox.assertions().assertV2SandboxRuntime()
    }

    @Test
    fun `runtime catalog diff rejects an unscoped foreign table`() {
        val sandbox = fixture
        val jdbc = JdbcTemplate(sandbox.dataSource)
        val foreignTableSql = """
            CREATE FOREIGN TABLE v2_catalog_unscoped_foreign (
                external_code integer NOT NULL
            ) SERVER v2_catalog_foreign_server OPTIONS (table_name 'world_state');
        """.trimIndent()

        assertEquals(emptyList(), V2MigrationConvention.createdTables(foreignTableSql))
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS postgres_fdw")
            jdbc.execute("DROP SERVER IF EXISTS v2_catalog_foreign_server CASCADE")
            jdbc.execute(
                "CREATE SERVER v2_catalog_foreign_server FOREIGN DATA WRAPPER postgres_fdw " +
                    "OPTIONS (host '127.0.0.1', dbname 'postgres', port '5432')",
            )
            jdbc.execute(foreignTableSql)
            assertTrue(
                V2FlywayIsolationAssertions.persistentTableRelations(sandbox.dataSource).any { relation ->
                    relation.table == V2CreatedTable("public", "v2_catalog_unscoped_foreign") && relation.kind == "f"
                },
                "pg_class catalog diff must include the foreign relation",
            )
            val error = assertFailsWith<AssertionError> {
                sandbox.assertions().assertV2SandboxRuntime()
            }
            assertTrue(error.message.orEmpty().contains("world_id"))
            assertTrue(error.message.orEmpty().contains("v2_catalog_unscoped_foreign"))
        } finally {
            jdbc.execute("DROP FOREIGN TABLE IF EXISTS v2_catalog_unscoped_foreign")
            jdbc.execute("DROP SERVER IF EXISTS v2_catalog_foreign_server")
        }
        sandbox.assertions().assertV2SandboxRuntime()
    }

    private fun SandboxFixture.assertions(): V2FlywayIsolationAssertions =
        V2FlywayIsolationAssertions(flyway, dataSource, v1CatalogBaseline)

    private data class SandboxFixture(
        val dataSource: DataSource,
        val flyway: Flyway,
        val v1CatalogBaseline: Set<V2CatalogRelation>,
    )

    private companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:16-alpine")

        val fixture: SandboxFixture by lazy {
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            val v1CatalogBaseline = v1PersistentTableBaseline(dataSource)
            val flyway = v2SandboxFlyway(dataSource)
            flyway.migrate()
            SandboxFixture(dataSource, flyway, v1CatalogBaseline)
        }
    }
}

internal class V2FlywayIsolationAssertions(
    private val flyway: Flyway,
    dataSource: DataSource,
    private val v1CatalogBaseline: Set<V2CatalogRelation>? = null,
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
        assertV2SourceConventions(appliedV2Migrations)

        val baseline = requireNotNull(v1CatalogBaseline) {
            "v2 runtime checks require a catalog snapshot taken after v1 and before v2 migration"
        }
        assertTrue(baseline.isNotEmpty(), "v1 catalog baseline must not be empty")
        val baselineOids = baseline.mapTo(mutableSetOf()) { it.oid }
        val createdRelations = persistentTableRelations(jdbc).filterNot { it.oid in baselineOids }
        assertTrue(createdRelations.isNotEmpty(), "v2 migrations must create at least one persistent table-like relation")
        assertTrue(
            createdRelations.any { it.table == V2CreatedTable("public", "v2_sandbox_probe") },
            "the V900 probe must appear in the v1-to-v2 PostgreSQL catalog diff",
        )
        createdRelations.forEach(::assertWorldScoped)
    }

    private fun resolvedLocations(): List<String> = flyway.configuration.locations.map { it.descriptor }

    private fun appliedV2Migrations(): List<MigrationInfo> = flyway.info().applied().filter { migration ->
        migration.version?.toString()?.toIntOrNull()?.let { it >= V2MigrationConvention.MINIMUM_VERSION } == true
    }

    private fun assertV2SourceConventions(appliedMigrations: List<MigrationInfo>) {
        val violations = appliedMigrations.flatMap { migration ->
            val source = V2MigrationSources.sourceForAppliedScript(migration.script)
            V2MigrationConvention.validate(migration.script, source.readText()).map { violation ->
                "${migration.script}: $violation"
            }
        }
        assertEquals(emptyList(), violations, "applied v2 source conventions")
    }

    private fun assertWorldScoped(relation: V2CatalogRelation) {
        val label = "pg_class ${relation.kind} ${relation.table.schema}.${relation.table.name} (oid=${relation.oid})"
        assertTrue(worldIdIsNotNull(relation), "$label must keep world_id NOT NULL")
        assertTrue(
            everyPrimaryOrUniqueIndexContainsWorldId(relation),
            "$label must scope every primary or unique index by world_id",
        )
        assertTrue(worldIdReferencesWorldState(relation), "$label must foreign-key world_id to world_state.id")
    }

    private fun probeTableExists(): Boolean = persistentTableRelations(jdbc).any { relation ->
        relation.table == V2CreatedTable("public", "v2_sandbox_probe")
    }

    private fun appliedProbeMigrations(): Int = jdbc.queryForObject(
        "SELECT count(*) FROM flyway_schema_history WHERE version = '900' AND success",
        Int::class.java,
    ) ?: 0

    private fun worldIdIsNotNull(relation: V2CatalogRelation): Boolean = queryBoolean(
        """
        SELECT EXISTS (
            SELECT 1
              FROM pg_attribute AS attribute
             WHERE attribute.attrelid = ?
               AND attribute.attname = 'world_id'
               AND attribute.attnum > 0
               AND NOT attribute.attisdropped
               AND attribute.attnotnull
        )
        """.trimIndent(),
        relation.oid,
    )

    private fun everyPrimaryOrUniqueIndexContainsWorldId(relation: V2CatalogRelation): Boolean = queryBoolean(
        """
        SELECT EXISTS (
            SELECT 1
              FROM pg_index AS index_meta
             WHERE index_meta.indrelid = ?
               AND (index_meta.indisprimary OR index_meta.indisunique)
        )
        AND NOT EXISTS (
            SELECT 1
              FROM pg_index AS index_meta
              JOIN pg_class AS source_relation ON source_relation.oid = index_meta.indrelid
             WHERE index_meta.indrelid = ?
               AND (index_meta.indisprimary OR index_meta.indisunique)
               AND NOT EXISTS (
                    SELECT 1
                      FROM unnest(index_meta.indkey) WITH ORDINALITY AS key_column(attribute_number, position)
                      JOIN pg_attribute AS attribute
                        ON attribute.attrelid = source_relation.oid
                       AND attribute.attnum = key_column.attribute_number
                     WHERE key_column.position <= index_meta.indnkeyatts
                       AND attribute.attname = 'world_id'
               )
        )
        """.trimIndent(),
        relation.oid,
        relation.oid,
    )

    private fun worldIdReferencesWorldState(relation: V2CatalogRelation): Boolean = queryBoolean(
        """
        SELECT EXISTS (
            SELECT foreign_key.oid
              FROM pg_constraint AS foreign_key
              JOIN pg_class AS source_relation
                ON source_relation.oid = foreign_key.conrelid
              JOIN pg_namespace AS source_namespace
                ON source_namespace.oid = source_relation.relnamespace
              JOIN pg_class AS referenced_relation
                ON referenced_relation.oid = foreign_key.confrelid
              JOIN pg_namespace AS referenced_namespace
                ON referenced_namespace.oid = referenced_relation.relnamespace
             JOIN pg_attribute AS source_attribute
                ON source_attribute.attrelid = foreign_key.conrelid
               AND source_attribute.attname = 'world_id'
               AND source_attribute.attnum > 0
               AND NOT source_attribute.attisdropped
              JOIN pg_attribute AS referenced_attribute
                ON referenced_attribute.attrelid = foreign_key.confrelid
               AND referenced_attribute.attname = 'id'
               AND referenced_attribute.attnum > 0
               AND NOT referenced_attribute.attisdropped
             WHERE foreign_key.contype = 'f'
               AND foreign_key.conrelid = ?
               AND referenced_namespace.nspname = 'public'
               AND referenced_relation.relname = 'world_state'
               AND EXISTS (
                    SELECT 1
                      FROM generate_subscripts(foreign_key.conkey, 1) AS key_position(position)
                     WHERE foreign_key.conkey[key_position.position] = source_attribute.attnum
                       AND foreign_key.confkey[key_position.position] = referenced_attribute.attnum
               )
        )
        """.trimIndent(),
        relation.oid,
    )

    private fun queryBoolean(sql: String, vararg arguments: Any): Boolean =
        jdbc.queryForObject(sql, Boolean::class.java, *arguments) ?: false

    companion object {
        const val V2_SANDBOX_PROBE_SCRIPT = "V900__v2_sandbox_probe.sql"

        fun persistentTableRelations(dataSource: DataSource): Set<V2CatalogRelation> =
            persistentTableRelations(JdbcTemplate(dataSource))

        private fun persistentTableRelations(jdbc: JdbcTemplate): Set<V2CatalogRelation> = jdbc.query(
            """
            SELECT relation.oid,
                   namespace.nspname AS schema_name,
                   relation.relname,
                   relation.relkind
              FROM pg_class AS relation
              JOIN pg_namespace AS namespace ON namespace.oid = relation.relnamespace
             WHERE relation.relkind IN ('r', 'p', 'f')
               AND relation.relpersistence IN ('p', 'u')
            """.trimIndent(),
        ) { resultSet, _ ->
            V2CatalogRelation(
                oid = resultSet.getLong("oid"),
                table = V2CreatedTable(
                    schema = resultSet.getString("schema_name"),
                    name = resultSet.getString("relname"),
                ),
                kind = resultSet.getString("relkind"),
            )
        }.toSet()
    }
}

internal data class V2CatalogRelation(
    val oid: Long,
    val table: V2CreatedTable,
    val kind: String,
)
