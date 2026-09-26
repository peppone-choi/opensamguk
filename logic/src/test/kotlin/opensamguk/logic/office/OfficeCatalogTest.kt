package opensamguk.logic.office

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OfficeCatalogTest {
    @Test
    fun `loads seven cited offices and keeps county offices projection only`() {
        val catalog = OfficeCatalog.loadClasspath()
        assertEquals(7, catalog.definitions.size)
        assertEquals(OfficeJurisdiction.JUN, catalog.definition("office.commandery-prefect")?.jurisdiction)
        assertTrue(catalog.definitions.filter { it.jurisdiction == OfficeJurisdiction.COUNTY }.all { it.countyPlacementOnly })
        assertTrue(catalog.definitions.all { it.sources.isNotEmpty() })
    }

    @Test
    fun `damaged citation is rejected at runtime`() {
        val raw = requireNotNull(javaClass.getResourceAsStream("/office/local-offices.json")).use { it.readBytes().toString(Charsets.UTF_8) }
        assertFailsWith<IllegalArgumentException> { OfficeCatalog.fromJson(raw.replace("\"quote\": \"外十二州，毎州刺史一人，六百石。\"", "\"quote\": \"\"")) }
    }
}
