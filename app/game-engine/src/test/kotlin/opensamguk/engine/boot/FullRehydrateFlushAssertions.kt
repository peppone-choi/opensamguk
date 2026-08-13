package opensamguk.engine.boot

import opensamguk.engine.run.TurnRunService
import opensamguk.infra.persistence.JdbcFlushExecutor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class FullRehydrateFlushAssertions(
    private val executor: JdbcFlushExecutor,
) {
    fun assertTechnologyFlush(result: TurnRunService.TickResult) {
        assertTrue(result.flushedGenerals > 0, "technology research must flush its general mutation")
        assertTrue(result.flushedLogs > 0, "technology research must flush its Korean action log")
        assertOneWorldStateFlush()
        assertFlushedTables(
            "world_state",
            "general",
            "nation",
            "log_entry",
            "general_turn_pull",
            "command_result",
            "command_outbox",
        )
    }

    fun assertFarmFlush(result: TurnRunService.TickResult) {
        assertTrue(result.flushedGenerals > 0, "farm development must flush its general mutation")
        assertTrue(result.flushedCities > 0, "farm development must flush its city mutation")
        assertTrue(result.flushedLogs > 0, "farm development must flush its Korean action log")
        assertOneWorldStateFlush()
        assertFlushedTables(
            "world_state",
            "general",
            "city",
            "log_entry",
            "general_turn_pull",
            "command_result",
            "command_outbox",
        )
    }

    private fun assertOneWorldStateFlush() {
        assertEquals(
            1,
            executor.lastOps().count { it.table == "world_state" },
            "each TurnRunService.runTick must commit exactly one world_state operation",
        )
    }

    private fun assertFlushedTables(vararg expected: String) {
        val actual = executor.lastOps().filter { it.count > 0 }.map { it.table }.toSet()
        for (table in expected) {
            assertTrue(table in actual, "expected $table in the real JDBC flush, got $actual")
        }
    }
}
