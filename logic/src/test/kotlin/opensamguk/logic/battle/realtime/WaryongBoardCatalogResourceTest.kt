package opensamguk.logic.battle.realtime

import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WaryongBoardCatalogResourceTest {
    @Test
    fun `packaged owner accepted catalog retains all original battlefields`() {
        val catalog = WaryongBoardCatalogResource.load()
        assertEquals(WaryongBoardCatalogResource.SHA256, catalog.catalogSha256)
        assertEquals(214, catalog.boards.size)
        assertEquals(188, catalog.boards.count { it.battlefield.kind == "FORTRESS" })
        assertEquals(26, catalog.boards.count { it.battlefield.kind == "FIELD" })
        (209..212).forEach { id -> assertFalse(catalog.boards[id].landEligible) }
    }

    @Test
    fun `same size mutation is rejected before catalog parsing`() {
        val original = checkNotNull(javaClass.classLoader.getResourceAsStream(WaryongBoardCatalogResource.PATH))
            .use { it.readBytes() }
        val changed = original.copyOf().apply { this[100] = (this[100].toInt() xor 1).toByte() }
        val loader = object : ClassLoader(javaClass.classLoader) {
            override fun getResourceAsStream(name: String): InputStream? =
                if (name == WaryongBoardCatalogResource.PATH) ByteArrayInputStream(changed)
                else super.getResourceAsStream(name)
        }
        assertFailsWith<IllegalArgumentException> { WaryongBoardCatalogResource.load(loader) }
    }
}
