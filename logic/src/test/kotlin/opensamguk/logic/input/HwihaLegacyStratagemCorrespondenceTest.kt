package opensamguk.logic.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HwihaLegacyStratagemCorrespondenceTest {
    private val catalog = HwihaInputCatalog.load()

    @Test fun `every old scheme has one card correspondence`() {
        assertEquals(12, HwihaLegacyStratagemCorrespondences.rows.size)
        assertEquals(emptyList(), HwihaLegacyStratagemCorrespondences.mismatches(catalog))
    }

    @Test fun `removing one correspondence turns the coverage gate red`() {
        val missing = HwihaLegacyStratagemCorrespondences.rows.dropLast(1)
        assertTrue(HwihaLegacyStratagemCorrespondences.mismatches(catalog, missing)
            .contains("unmapped:stratagem.reciprocity/che_피장파장"))
    }
}
