package opensamguk.infra.seed

import opensamguk.common.world.WorldId
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate

class ScenarioSeedCoordinator(
    private val jdbc: JdbcTemplate,
) {
    data class SeedAdmission(
        val seeded: Boolean,
        val counts: ScenarioImporter.ImportCounts?,
    )

    private val transaction = TransactionTemplate(
        DataSourceTransactionManager(requireNotNull(jdbc.dataSource) { "Scenario seed requires a DataSource" }),
    )

    fun importFresh(
        expectedWorldId: WorldId,
        importer: ScenarioImporter,
    ): ScenarioImporter.ImportCounts = inTransaction {
        lockWorldAdmission()
        requireNoWorldExists()
        val counts = importer.importAdmitted(jdbc, expectedWorldId)
        requireCanonicalWorld(expectedWorldId)
        synchronizeWorldStateSequence(expectedWorldId)
        counts
    }

    fun ensureSeeded(
        expectedWorldId: WorldId,
        importer: () -> ScenarioImporter,
    ): SeedAdmission = inTransaction {
        lockWorldAdmission()
        val ids = worldIds()
        val admission = when {
            ids.isEmpty() -> {
                val counts = importer().importAdmitted(jdbc, expectedWorldId)
                requireCanonicalWorld(expectedWorldId)
                SeedAdmission(seeded = true, counts = counts)
            }

            ids == listOf(expectedWorldId.value) -> SeedAdmission(seeded = false, counts = null)
            else -> error(
                "Scenario seed requires exactly configured world_state.id=${expectedWorldId.value}; found $ids",
            )
        }
        synchronizeWorldStateSequence(expectedWorldId)
        admission
    }

    private fun lockWorldAdmission() {
        jdbc.execute("LOCK TABLE world_state IN SHARE ROW EXCLUSIVE MODE")
    }

    private fun requireNoWorldExists() {
        val ids = worldIds()
        check(ids.isEmpty()) { "Scenario import requires an empty world_state table; found $ids" }
    }

    private fun requireCanonicalWorld(expectedWorldId: WorldId) {
        val ids = worldIds()
        check(ids == listOf(expectedWorldId.value)) {
            "Scenario seed must leave exactly world_state.id=${expectedWorldId.value}; found $ids"
        }
    }

    private fun synchronizeWorldStateSequence(expectedWorldId: WorldId) {
        jdbc.queryForObject(
            """
            SELECT setval(
                'world_state_id_seq'::regclass,
                GREATEST((SELECT last_value FROM world_state_id_seq), ?),
                true
            )
            """.trimIndent(),
            Long::class.java,
            expectedWorldId.value.toLong(),
        ) ?: error("world_state identity sequence synchronization did not return a value")
    }

    private fun worldIds(): List<Int> = jdbc.queryForList(
        "SELECT id FROM world_state ORDER BY id",
        Int::class.java,
    )

    private fun <T> inTransaction(body: () -> T): T =
        requireNotNull(transaction.execute { body() })
}
