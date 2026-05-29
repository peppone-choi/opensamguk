package opensamguk.common.wire

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TurnDaemonWireTypesTest {
    private val json = Json { encodeDefaults = false; explicitNulls = false }

    @Test
    fun `TurnDaemonState RUNNING serializes lowercase`() {
        assertEquals("\"running\"", json.encodeToString(TurnDaemonState.serializer(), TurnDaemonState.RUNNING))
        assertEquals(TurnDaemonState.FLUSHING, json.decodeFromString(TurnDaemonState.serializer(), "\"flushing\""))
    }

    @Test
    fun `TurnRunBudget round-trips`() {
        val budget = TurnRunBudget(budgetMs = 250L, maxGenerals = 40, catchUpCap = 12)
        val encoded = json.encodeToString(TurnRunBudget.serializer(), budget)
        assertEquals(budget, json.decodeFromString(TurnRunBudget.serializer(), encoded))
    }
}
