package opensamguk.infra.seed

import opensamguk.logic.imperial.ImperialLineStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ScenarioImperialSeedResolverTest {
    private val seed = ScenarioImperialSeed(
        houses = listOf(ScenarioImperialHouse("test_line", "시험 계통", ImperialLineStatus.ACTIVE,
            "황제", "후계자", listOf("후계자"), null, 5, 11, 70)),
        allegiances = emptyList(),
    )

    @Test
    fun `active names resolve to assigned world IDs regardless of roster order`() {
        val roster = listOf("황제" to 1002, "후계자" to 1001)
        val a = ScenarioImperialSeedResolver.resolve(seed, roster)
        val b = ScenarioImperialSeedResolver.resolve(seed, roster.reversed())
        assertEquals(a, b)
        assertEquals(1002, a.houses.single().holderGeneralId)
        assertEquals(1001, a.houses.single().designatedHeirGeneralId)
        assertEquals(emptyList(), a.transitions)
    }

    @Test
    fun `deferred or duplicate names cannot become invented imperial IDs`() {
        assertFailsWith<IllegalArgumentException> {
            ScenarioImperialSeedResolver.resolve(seed, listOf("후계자" to 1001))
        }
        assertFailsWith<IllegalArgumentException> {
            ScenarioImperialSeedResolver.resolve(seed,
                listOf("황제" to 1002, "황제" to 1003, "후계자" to 1001))
        }
    }
}
