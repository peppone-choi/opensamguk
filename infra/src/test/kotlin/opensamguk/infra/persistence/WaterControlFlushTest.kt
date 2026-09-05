package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
import opensamguk.logic.world.WaterBlockadeState
import opensamguk.logic.world.WaterControlState
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.*

class WaterControlFlushTest {
    private val state = WaterControlState("r1", "a".repeat(64), "lake", 3L, listOf(5, 9), WaterBlockadeState.CONTESTED, 6)
    private class RecordingJdbc(val affected: Int = 1) : NamedParameterJdbcTemplate(DriverManagerDataSource()) {
        val calls = mutableListOf<Pair<String, Map<String, Any?>>>()
        override fun update(sql: String, paramSource: SqlParameterSource): Int {
            calls += sql to paramSource.parameterNames!!.associateWith { paramSource.getValue(it) }
            return if ("water_zone_control" in sql) affected else 1
        }
    }
    private class RecordingTransactions : PlatformTransactionManager {
        var commits = 0
        var rollbacks = 0
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus = SimpleTransactionStatus()
        override fun commit(status: TransactionStatus) { commits++ }
        override fun rollback(status: TransactionStatus) { rollbacks++ }
    }
    private fun payload(expected: Long?) = FlushPayload(WorldId(4), mapOf("id" to 4, "current_year" to 200, "current_month" to 1),
        waterControlWrites = WaterControlWriteBatch(listOf(WaterControlWriteRow(expected, state))))

    @Test fun `existing state update pins world zone topology and first expected revision`() {
        val jdbc = RecordingJdbc()
        val transactions = RecordingTransactions()
        JdbcFlushExecutor(jdbc, TransactionTemplate(transactions)).flush(payload(4))
        val (sql, params) = jdbc.calls.single { "water_zone_control" in it.first }
        assertContains(sql, "UPDATE water_zone_control")
        val predicate = sql.substringAfter("WHERE")
        for (key in listOf("world_id", "water_zone_id", "topology_revision", "topology_hash", "revision")) assertContains(predicate, key)
        assertEquals(4, params["world_id"])
        assertEquals(4L, params["expected_revision"])
        assertEquals(6L, params["revision"])
        assertEquals("[5,9]", params["contesting_nation_ids"].toString())
        assertEquals(1, transactions.commits)
    }

    @Test fun `absent state uses conflict do nothing not an overwrite upsert`() {
        val jdbc = RecordingJdbc()
        JdbcFlushExecutor(jdbc, TransactionTemplate(RecordingTransactions())).flush(payload(null))
        val sql = jdbc.calls.single { "water_zone_control" in it.first }.first
        assertContains(sql, "INSERT INTO water_zone_control")
        assertContains(sql, "ON CONFLICT (world_id, water_zone_id) DO NOTHING")
        assertFalse("DO UPDATE" in sql)
    }

    @Test fun `CAS mismatch rolls back whole existing transaction and never commits`() {
        for (expected in listOf(null, 4L)) {
            val jdbc = RecordingJdbc(0)
            val transactions = RecordingTransactions()
            assertFailsWith<StaleWaterControlException> { JdbcFlushExecutor(jdbc, TransactionTemplate(transactions)).flush(payload(expected)) }
            assertTrue(jdbc.calls.first().first.contains("UPDATE world_state"))
            assertEquals(0, transactions.commits)
            assertEquals(1, transactions.rollbacks)
        }
    }

    @Test fun `immutable write batch rejects duplicate zone and invalid revision`() {
        val input = mutableListOf(WaterControlWriteRow(4, state))
        val batch = WaterControlWriteBatch(input)
        input.clear()
        assertEquals(1, batch.size)
        assertFailsWith<UnsupportedOperationException> { (batch as MutableList<*>).clear() }
        assertFailsWith<IllegalArgumentException> { WaterControlWriteBatch(listOf(batch[0], batch[0])) }
        assertFailsWith<IllegalArgumentException> { WaterControlWriteRow(6, state) }
    }
}
