package opensamguk.infra.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class V2CityCatalogAdapterTest {

    @Test
    fun `loads the pinned production city source with verified provenance`() {
        val snapshot = V2CityCatalogAdapter().load()

        assertEquals(1, snapshot.metadata.schemaVersion)
        assertEquals("cities_1010", snapshot.metadata.id)
        assertEquals(V2ContentStatus.ACTIVE, snapshot.metadata.status)
        assertEquals("scenario/cities_1010.json", snapshot.metadata.source)
        assertEquals("6759a68255cae1a6b9c05cbbaf5736ed8fc9fcb50c6623be44d7e3dfe0b4d393", snapshot.metadata.sha256)
        assertEquals(94, snapshot.metadata.cityCount)
        assertEquals(24, snapshot.metadata.scenarioOwnedCityCount)
        assertEquals(94, snapshot.cities.size)
        assertEquals(24, snapshot.cities.count { it.nationId != 0 })
    }

    @Test
    fun `independent city loads are equal and produce an empty diff`() {
        val adapter = V2CityCatalogAdapter()

        val first = adapter.load()
        val second = adapter.load()

        assertEquals(first, second)
        assertTrue(first.diff(second).isEmpty)
    }

    @Test
    fun `rejects a city source whose bytes do not match metadata`() {
        val adapter = V2CityCatalogAdapter(V2ContentCatalog(HASH_MISMATCH_LOCATION))

        val error = assertFailsWith<IllegalArgumentException> { adapter.load() }

        assertEquals("v2 city source sha256 does not match metadata", error.message)
    }

    @Test
    fun `rejects a city source whose total count does not match metadata`() {
        val adapter = V2CityCatalogAdapter(V2ContentCatalog(CITY_COUNT_MISMATCH_LOCATION))

        val error = assertFailsWith<IllegalArgumentException> { adapter.load() }

        assertEquals("v2 city source count does not match metadata", error.message)
    }

    @Test
    fun `rejects a city source whose owned count does not match metadata`() {
        val adapter = V2CityCatalogAdapter(V2ContentCatalog(OWNED_COUNT_MISMATCH_LOCATION))

        val error = assertFailsWith<IllegalArgumentException> { adapter.load() }

        assertEquals("v2 owned city count does not match metadata", error.message)
    }

    @Test
    fun `rejects duplicate city ids after valid source verification`() {
        val adapter = V2CityCatalogAdapter(V2ContentCatalog(DUPLICATE_CITY_ID_LOCATION))

        val error = assertFailsWith<IllegalArgumentException> { adapter.load() }

        assertEquals("v2 city source contains duplicate city ids", error.message)
    }

    private companion object {
        const val HASH_MISMATCH_LOCATION = "v2-catalog-fixture/hash-mismatch/content/v2"
        const val CITY_COUNT_MISMATCH_LOCATION = "v2-catalog-fixture/city-count-mismatch/content/v2"
        const val OWNED_COUNT_MISMATCH_LOCATION = "v2-catalog-fixture/owned-count-mismatch/content/v2"
        const val DUPLICATE_CITY_ID_LOCATION = "v2-catalog-fixture/duplicate-city-id/content/v2"
    }
}
