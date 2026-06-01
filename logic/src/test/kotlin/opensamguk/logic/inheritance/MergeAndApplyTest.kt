package opensamguk.logic.inheritance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MergeAndApplyTest {
    private fun proxy(
        id: Int = 1,
        owner: Int = 1,
        npc: Int = 0,
        vars: Map<String, Int> = emptyMap(),
        rankVars: Map<String, Int> = emptyMap(),
        auxVars: Map<String, Any?> = emptyMap(),
    ) = object : GeneralProxy {
        override val id = id
        override val owner = owner
        override val npc = npc
        override fun getVar(key: String): Int = vars[key] ?: 0
        override fun getRankVar(key: String): Int = rankVars[key] ?: 0
        override fun getAuxVar(key: String): Any? = auxVars[key]
    }

    @Test
    fun `merge computes non storeType keys and writes to store`() {
        val store = InheritancePointStore()
        store.putRaw(1, "previous", 100.0, null)
        val general = proxy(
            vars = mapOf("belong" to 3),
            auxVars = mapOf("max_belong" to 5),
        )
        val results = mutableListOf<InheritanceResultRow>()
        mergeTotalInheritancePoint(
            general,
            isRebirth = false,
            isUnited = false,
            year = 200,
            startYear = 180,
            month = 1,
            store = store,
            serverID = 1,
        ) { results.add(it) }
        assertEquals(50.0, store.get(1, "max_belong")?.first)
        assertEquals(1, results.size)
        assertEquals(1, results[0].serverID)
        assertEquals(1, results[0].ownerID)
    }

    @Test
    fun `merge skips NPC general when pickYearMonth condition met`() {
        val store = InheritancePointStore()
        val general = proxy(npc = 1, auxVars = mapOf("pickYearMonth" to "190-01"))
        val results = mutableListOf<InheritanceResultRow>()
        mergeTotalInheritancePoint(
            general,
            isRebirth = false,
            isUnited = false,
            year = 200,
            startYear = 180,
            month = 1,
            store = store,
            serverID = 1,
        ) { results.add(it) }
        assertEquals(0, results.size)
        assertEquals(emptyMap(), store.getAll(1))
    }

    @Test
    fun `merge does not skip NPC general when pickYearMonth condition not met`() {
        val store = InheritancePointStore()
        val general = proxy(npc = 1, auxVars = mapOf("pickYearMonth" to "189-01"))
        val results = mutableListOf<InheritanceResultRow>()
        mergeTotalInheritancePoint(
            general,
            isRebirth = false,
            isUnited = false,
            year = 200,
            startYear = 180,
            month = 1,
            store = store,
            serverID = 1,
        ) { results.add(it) }
        assertEquals(1, results.size)
    }

    @Test
    fun `apply with rebirth true folds rebirth coefficients and keeps null coeff keys`() {
        val store = InheritancePointStore()
        store.putRaw(1, "previous", 100.0, null)
        store.putRaw(1, "dex", 500.0, null) // rebirthStoreCoeff = 0.5
        store.putRaw(1, "max_belong", 50.0, null) // rebirthStoreCoeff = null
        val logs = mutableListOf<Pair<String, String>>()
        val total = applyInheritanceUser(1, isRebirth = true, store) { text, tag -> logs.add(text to tag) }
        // previous: 100 * 1.0 = 100
        // dex: 500 * 0.5 = 250
        // max_belong: kept, not summed
        assertEquals(350, total)
        assertEquals(350.0, store.get(1, "previous")?.first)
        assertEquals(50.0, store.get(1, "max_belong")?.first)
    }

    @Test
    fun `apply without rebirth sums all values`() {
        val store = InheritancePointStore()
        store.putRaw(1, "previous", 100.0, null)
        store.putRaw(1, "dex", 500.0, null)
        val logs = mutableListOf<Pair<String, String>>()
        val total = applyInheritanceUser(1, isRebirth = false, store) { text, tag -> logs.add(text to tag) }
        assertEquals(600, total)
        assertEquals(600.0, store.get(1, "previous")?.first)
        assertNull(store.get(1, "dex"))
    }

    @Test
    fun `apply returns previous when store only contains previous`() {
        val store = InheritancePointStore()
        store.putRaw(1, "previous", 250.0, null)
        val total = applyInheritanceUser(1, isRebirth = false, store) { _, _ -> }
        assertEquals(250, total)
    }

    @Test
    fun `apply returns zero for empty store`() {
        val store = InheritancePointStore()
        val total = applyInheritanceUser(1, isRebirth = false, store) { _, _ -> }
        assertEquals(0, total)
    }
}
