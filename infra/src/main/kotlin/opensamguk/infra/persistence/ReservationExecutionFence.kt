package opensamguk.infra.persistence

import opensamguk.common.world.WorldId
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate

/** The claim covers reservation snapshots, effects and the outer JDBC commit, including absent slots. */
class ReservationExecutionFence(
    private val jdbc: NamedParameterJdbcTemplate,
    val transactions: TransactionTemplate = TransactionTemplate(
        DataSourceTransactionManager(requireNotNull(jdbc.jdbcTemplate.dataSource))),
) {
    init {
        val manager = transactions.transactionManager as? DataSourceTransactionManager
            ?: error("Execution fence requires a JDBC transaction manager")
        require(manager.dataSource === jdbc.jdbcTemplate.dataSource) { "Fence and flush require the same DataSource" }
    }

    fun <T : Any> execute(worldId: WorldId, beforeEffects: () -> Unit = {}, block: () -> T): T {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) {
            "Execution generation must own the outer commit"
        }
        return requireNotNull(transactions.execute {
            jdbc.jdbcTemplate.queryForObject("SELECT pg_advisory_xact_lock(?, ?)", String::class.java,
                NAMESPACE, worldId.value)
            beforeEffects()
            block()
        })
    }

    fun tryCancellation(worldId: WorldId): Boolean {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Cancellation fence requires a transaction" }
        check(TransactionSynchronizationManager.hasResource(requireNotNull(jdbc.jdbcTemplate.dataSource))) {
            "Cancellation fence requires the bound JDBC connection"
        }
        return jdbc.jdbcTemplate.queryForObject("SELECT pg_try_advisory_xact_lock_shared(?, ?)",
            Boolean::class.java, NAMESPACE, worldId.value) == true
    }

    companion object {
        // Dedicated two-int PostgreSQL advisory namespace; independent from Flyway's bigint lock.
        const val NAMESPACE = 0x52535645
    }
}
