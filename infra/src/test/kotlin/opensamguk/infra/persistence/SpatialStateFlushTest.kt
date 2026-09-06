package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
import opensamguk.logic.world.GeneralPositionState
import opensamguk.logic.world.ProvinceControlState
import opensamguk.logic.world.StrategicNodeRef
import opensamguk.logic.world.WaterBlockadeState
import opensamguk.logic.world.WaterControlState
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpatialStateFlushTest {
    private val hash = "a".repeat(64)
    private val province = ProvinceControlState("r1", hash, "p1", 3, 5)
    private val position = GeneralPositionState("r1", hash, 10, StrategicNodeRef.WaterZone("w1"), 8)

    @Test
    fun `province insert and position update use exact world keys pins and CAS forms`() {
        val jdbc = RecordingJdbc()
        val transactions = RecordingTransactions()

        JdbcFlushExecutor(jdbc, TransactionTemplate(transactions)).flush(
            payload(
                provinceWrites = ProvinceControlWriteBatch(listOf(ProvinceControlWriteRow(null, province))),
                positionWrites = GeneralPositionWriteBatch(listOf(GeneralPositionWriteRow(6, position))),
            ),
        )

        val (provinceSql, provinceParams) = jdbc.calls.single { "province_control" in it.first }
        assertContains(provinceSql, "INSERT INTO province_control")
        assertContains(provinceSql, "ON CONFLICT (world_id, province_id) DO NOTHING")
        assertFalse("DO UPDATE" in provinceSql)
        assertEquals(4, provinceParams["world_id"])
        assertEquals(5L, provinceParams["revision"])

        val (positionSql, positionParams) = jdbc.calls.single { "general_spatial_position" in it.first }
        assertContains(positionSql, "UPDATE general_spatial_position")
        val predicate = positionSql.substringAfter("WHERE")
        for (key in listOf("world_id", "general_id", "topology_revision", "topology_hash", "revision")) {
            assertContains(predicate, key)
        }
        assertEquals("WATER_ZONE", positionParams["node_kind"])
        assertEquals("w1", positionParams["node_id"])
        assertEquals(6L, positionParams["expected_revision"])
        assertEquals(1, transactions.commits)
    }

    @Test
    fun `zero affected insert or update is stale and rolls back`() {
        for ((table, expectedException) in listOf(
            "province_control" to StaleProvinceControlException::class,
            "general_spatial_position" to StaleGeneralPositionException::class,
        )) {
            val jdbc = RecordingJdbc(zeroAffectedTable = table)
            val transactions = RecordingTransactions()
            val error = runCatching {
                JdbcFlushExecutor(jdbc, TransactionTemplate(transactions)).flush(
                    if (table == "province_control") {
                        payload(provinceWrites = ProvinceControlWriteBatch(listOf(ProvinceControlWriteRow(null, province))))
                    } else {
                        payload(positionWrites = GeneralPositionWriteBatch(listOf(GeneralPositionWriteRow(null, position))))
                    },
                )
            }.exceptionOrNull()
            assertTrue(expectedException.isInstance(error), "expected ${expectedException.simpleName}, got $error")
            assertEquals(0, transactions.commits)
            assertEquals(1, transactions.rollbacks)
        }
    }

    @Test
    fun `mixed pins across spatial and water channels are rejected before JDBC writes`() {
        val jdbc = RecordingJdbc()
        val executor = JdbcFlushExecutor(jdbc, TransactionTemplate(RecordingTransactions()))
        val water = WaterControlState(
            "r2", "b".repeat(64), "w1", null, emptyList(), WaterBlockadeState.OPEN, 1,
        )

        assertFailsWith<IllegalArgumentException> {
            executor.flush(
                payload(
                    provinceWrites = ProvinceControlWriteBatch(listOf(ProvinceControlWriteRow(null, province))),
                    positionWrites = GeneralPositionWriteBatch(listOf(GeneralPositionWriteRow(6, position))),
                    waterWrites = WaterControlWriteBatch(listOf(WaterControlWriteRow(null, water))),
                ),
            )
        }
        assertTrue(jdbc.calls.isEmpty())
    }

    @Test
    fun `position write for a payload-deleted general is rejected before JDBC writes`() {
        val jdbc = RecordingJdbc()
        val executor = JdbcFlushExecutor(jdbc, TransactionTemplate(RecordingTransactions()))

        assertFailsWith<IllegalArgumentException> {
            executor.flush(
                payload(
                    positionWrites = GeneralPositionWriteBatch(listOf(GeneralPositionWriteRow(null, position))),
                    deletedGenerals = listOf(position.generalId),
                ),
            )
        }
        assertTrue(jdbc.calls.isEmpty())
    }

    private fun payload(
        provinceWrites: ProvinceControlWriteBatch = ProvinceControlWriteBatch(),
        positionWrites: GeneralPositionWriteBatch = GeneralPositionWriteBatch(),
        waterWrites: WaterControlWriteBatch = WaterControlWriteBatch(),
        deletedGenerals: List<Int> = emptyList(),
    ) = FlushPayload(
        worldId = WorldId(4),
        worldStateUpdate = mapOf("id" to 4, "current_year" to 200, "current_month" to 1),
        deletedGenerals = deletedGenerals,
        waterControlWrites = waterWrites,
        provinceControlWrites = provinceWrites,
        generalPositionWrites = positionWrites,
    )

    private class RecordingJdbc(private val zeroAffectedTable: String? = null) :
        NamedParameterJdbcTemplate(DriverManagerDataSource()) {
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()

        override fun update(sql: String, paramSource: SqlParameterSource): Int {
            calls += sql to paramSource.parameterNames!!.associateWith { paramSource.getValue(it) }
            return if (zeroAffectedTable != null && zeroAffectedTable in sql) 0 else 1
        }
    }

    private class RecordingTransactions : PlatformTransactionManager {
        var commits = 0
        var rollbacks = 0
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) { commits++ }
        override fun rollback(status: TransactionStatus) { rollbacks++ }
    }
}
