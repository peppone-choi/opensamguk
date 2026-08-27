package opensamguk.gameapi.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PublicCommandCatalogIndexTest {
    @Test
    fun `missing legacy surface fails closed`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            PublicCommandCatalogIndex.requireLegacyCode("GENERAL_TURN", "missing-command")
        }

        assertTrue(failure.message.orEmpty().contains("GENERAL_TURN/missing-command"))
    }

    @Test
    fun `same legacy code may exist in different source rings`() {
        val index = PublicCommandCatalogIndex.parse(catalog(command("personal.rest", "general_turn"), command("chief.rest", "nation_turn")))

        assertEquals("personal.rest", index.getValue("GENERAL_TURN" to "휴식").canonicalId)
        assertEquals("chief.rest", index.getValue("NATION_TURN" to "휴식").canonicalId)
    }

    @Test
    fun `duplicate legacy surface in one source ring fails closed`() {
        val failure = assertFailsWith<IllegalStateException> {
            PublicCommandCatalogIndex.parse(catalog(command("personal.rest", "general_turn"), command("personal.rest.duplicate", "general_turn")))
        }

        assertTrue(failure.message.orEmpty().contains("GENERAL_TURN/휴식"))
    }

    private fun catalog(vararg commands: String): String =
        """{"commands":[${commands.joinToString(",")}]}"""

    private fun command(canonicalId: String, ring: String): String = """
        {
          "canonicalId":"$canonicalId",
          "normalizedIntentId":null,
          "layer":"TEST_RING",
          "authority":{"policy":"TEST_AUTHORITY"},
          "contractStatus":"FINAL",
          "deliveryState":"DOMAIN_READY",
          "legacySurfaces":[{
            "ring":"$ring",
            "legacyCode":"휴식",
            "adapterPolicy":"PRESERVE",
            "parityStatus":"LOCKED"
          }]
        }
    """.trimIndent()
}
