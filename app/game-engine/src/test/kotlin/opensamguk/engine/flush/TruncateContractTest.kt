package opensamguk.engine.flush

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class TruncateContractTest {
    private fun baselineSql(): File {
        // Probe both the module-root and project-root working directories.
        val candidates = listOf(
            File("../../infra/src/main/resources/db/migration/V1__baseline.sql"),
            File("infra/src/main/resources/db/migration/V1__baseline.sql"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: fail("V1__baseline.sql not found in ${candidates.map { it.absolutePath }}")
    }

    private fun baselineTables(): Set<String> {
        val regex = Regex("""CREATE\s+TABLE\s+`?(\w+)`?""", RegexOption.IGNORE_CASE)
        return regex.findAll(baselineSql().readText())
            .map { it.groupValues[1] }
            .toSet()
    }

    @Test
    fun `inheritance hall and dynasty tables survive truncate`() {
        assertTrue(TruncateContract.isExcludedFromTruncate("hall"))
        assertTrue(TruncateContract.isExcludedFromTruncate("ng_old_nations"))
        assertTrue(TruncateContract.isExcludedFromTruncate("ng_old_generals"))
        assertTrue(TruncateContract.isExcludedFromTruncate("inheritance_point"))
        assertTrue(TruncateContract.isExcludedFromTruncate("inheritance_log"))
        assertTrue(TruncateContract.isExcludedFromTruncate("inheritance_result"))
        assertTrue(TruncateContract.isExcludedFromTruncate("inheritance_user_state"))
    }

    @Test
    fun `per-season tables are truncated not survived`() {
        for (table in listOf("general", "city", "nation", "diplomacy", "log_entry")) {
            assertTrue(table in TruncateContract.TRUNCATED, "$table must be truncated")
            assertFalse(TruncateContract.isExcludedFromTruncate(table), "$table must not survive")
        }
    }

    @Test
    fun `SURVIVE and TRUNCATED are disjoint`() {
        assertTrue((TruncateContract.SURVIVE intersect TruncateContract.TRUNCATED).isEmpty())
    }

    @Test
    fun `every baseline CREATE TABLE is classified`() {
        val tables = baselineTables()
        assertTrue(tables.isNotEmpty(), "parsed no tables from V1__baseline.sql")
        val classified = TruncateContract.SURVIVE + TruncateContract.TRUNCATED
        val unclassified = tables - classified
        assertEquals(emptySet(), unclassified, "every baseline table must be classified (survive or truncate)")
    }
}
