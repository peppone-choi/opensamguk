package opensamguk.common.constants

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScenarioLifecycleMetaTest {

    @Test
    fun `seeded general lifecycle meta follows PHP reset killturn and deadyear`() {
        val meta = ScenarioLifecycleMeta.initialGeneralMeta(deadYear = 300, turnterm = 60, npcmode = 0)

        assertEquals(80, meta["killturn"])
        assertEquals(300, meta["deadyear"])
    }

    @Test
    fun `loader enrichment preserves existing meta and fills only lifecycle holes`() {
        val meta = linkedMapOf<String, Any?>("explevel" to 10, "killturn" to 7)

        val enriched = ScenarioLifecycleMeta.ensureGeneralMeta(meta, deadYear = 250, turnterm = 120, npcmode = 1)

        assertEquals(10, enriched["explevel"])
        assertEquals(7, enriched["killturn"])
        assertEquals(250, enriched["deadyear"])
        assertTrue(enriched.keys.toList().indexOf("deadyear") > enriched.keys.toList().indexOf("killturn"))
    }
}
