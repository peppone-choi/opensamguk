package opensamguk.common.constants

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenarioLifecycleMetaTest {

    @Test
    fun `killturn converts PHP month lifespan to three phase turns`() {
        assertEquals(3816, ScenarioLifecycleMeta.killturnFor(deadYear = 300, startYear = 194, startMonth = 1))
        assertEquals(216, ScenarioLifecycleMeta.killturnFor(deadYear = 200, startYear = 194, startMonth = 1))
        assertEquals(438, ScenarioLifecycleMeta.killturnFor(deadYear = 206, startYear = 194, startMonth = 3))
    }

    @Test
    fun `killturn includes PHP month jitter before phase conversion`() {
        assertEquals(
            ((220 - 182) * 12 + 11 + 2) * GameConst.phasesPerMonth,
            ScenarioLifecycleMeta.killturnFor(
                deadYear = 220,
                startYear = 182,
                startMonth = 3,
                legacyMonthJitter = 11,
            ),
        )
    }

    @Test
    fun `different deadYear yields different killturn (no global collapse)`() {
        val a = ScenarioLifecycleMeta.killturnFor(deadYear = 210, startYear = 194, startMonth = 1)
        val b = ScenarioLifecycleMeta.killturnFor(deadYear = 250, startYear = 194, startMonth = 1)
        val c = ScenarioLifecycleMeta.killturnFor(deadYear = 300, startYear = 194, startMonth = 1)
        // 서로 다른 사망년도 → 서로 다른 killturn (과거 전원 동일값 → 동시 사망 버그 회귀 방지).
        assertEquals(3, setOf(a, b, c).size)
    }

    @Test
    fun `deadYear at or before startYear is clamped to one three-phase month`() {
        assertEquals(3, ScenarioLifecycleMeta.killturnFor(deadYear = 194, startYear = 194, startMonth = 1))
        assertEquals(3, ScenarioLifecycleMeta.killturnFor(deadYear = 180, startYear = 194, startMonth = 1))
    }

    @Test
    fun `seeded general lifecycle meta carries per-general killturn and deadyear`() {
        val meta = ScenarioLifecycleMeta.initialGeneralMeta(deadYear = 300, startYear = 194, startMonth = 1)

        assertEquals(3816, meta["killturn"])
        assertEquals(ScenarioLifecycleMeta.KILLTURN_UNIT_PHASE, meta["killturn_unit"])
        assertEquals(300, meta["deadyear"])
    }

    @Test
    fun `loader enrichment preserves existing meta and fills only lifecycle holes`() {
        val meta = linkedMapOf<String, Any?>("explevel" to 10, "killturn" to 7)

        val enriched = ScenarioLifecycleMeta.ensureGeneralMeta(meta, deadYear = 250, startYear = 194, startMonth = 1)

        assertEquals(10, enriched["explevel"])
        assertEquals(7, enriched["killturn"])
        assertEquals(250, enriched["deadyear"])
        assertTrue(enriched.keys.toList().indexOf("deadyear") > enriched.keys.toList().indexOf("killturn"))
    }

    @Test
    fun `loader enrichment fills missing killturn from deadYear`() {
        val enriched = ScenarioLifecycleMeta.ensureGeneralMeta(
            linkedMapOf<String, Any?>("explevel" to 10),
            deadYear = 250,
            startYear = 194,
            startMonth = 1,
        )

        assertEquals((250 - 194) * 12 * GameConst.phasesPerMonth, enriched["killturn"])
        assertEquals(ScenarioLifecycleMeta.KILLTURN_UNIT_PHASE, enriched["killturn_unit"])
        assertEquals(250, enriched["deadyear"])
    }

    @Test
    fun `loader converts an unmarked NPC killturn exactly once`() {
        val converted = ScenarioLifecycleMeta.ensureGeneralMeta(
            linkedMapOf("killturn" to 72),
            deadYear = 250,
            startYear = 194,
            startMonth = 1,
            convertLegacyNpcKillturn = true,
        )
        val reloaded = ScenarioLifecycleMeta.ensureGeneralMeta(
            converted,
            deadYear = 250,
            startYear = 194,
            startMonth = 1,
            convertLegacyNpcKillturn = true,
        )

        assertEquals(216, converted["killturn"])
        assertEquals(ScenarioLifecycleMeta.KILLTURN_UNIT_PHASE, converted["killturn_unit"])
        assertEquals(216, reloaded["killturn"])
    }
}
