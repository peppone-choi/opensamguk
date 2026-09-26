package opensamguk.logic.content

import opensamguk.logic.office.OfficeCredentialCodec
import opensamguk.logic.office.OfficeTenureCodec
import opensamguk.logic.vassal.VassalStateCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PersistedMetaJsonTest {
    @Test
    fun `decoded KV objects reach the same office and vassal codecs as hot JSON`() {
        val officeValue = mapOf("version" to 1, "tenures" to emptyList<Any>())
        val credentialValue = mapOf("version" to 1, "credentials" to emptyList<Any>())
        val vassalValue = mapOf("version" to 1, "contracts" to emptyList<Any>(), "receipts" to emptyList<Any>())
        assertEquals(emptyList(), OfficeTenureCodec.decode(PersistedMetaJson.raw(officeValue)))
        assertEquals(emptyList(), OfficeCredentialCodec.decode(PersistedMetaJson.raw(credentialValue)))
        assertEquals(emptyList(), VassalStateCodec.decode(PersistedMetaJson.raw(vassalValue)).contracts)
        assertEquals(OfficeTenureCodec.encode(emptyList()), PersistedMetaJson.raw(OfficeTenureCodec.encode(emptyList())))
    }

    @Test
    fun `nested values survive conversion and invalid keys fail closed`() {
        assertEquals("{\"rows\":[{\"id\":7,\"active\":true,\"missing\":null}]}",
            PersistedMetaJson.raw(mapOf("rows" to listOf(mapOf("id" to 7, "active" to true, "missing" to null)))))
        assertFailsWith<IllegalArgumentException> { PersistedMetaJson.raw(mapOf(1 to "bad key")) }
    }
}
