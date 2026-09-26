package opensamguk.logic.world

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorldFormatTest {
    @Test fun `current world has an explicit format`() {
        assertEquals(WorldFormat.GENERAL_RETAINER_CAMPAIGN,
            WorldFormat.require(mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN")))
    }

    @Test fun `missing format does not fall back to an old world`() {
        val error = assertFailsWith<IllegalArgumentException> { WorldFormat.require(emptyMap()) }
        assertTrue(error.message.orEmpty().contains("worldFormat is missing"))
    }

    @Test fun `sammo world and unknown format are refused`() {
        for (value in listOf("SAMMO", "HWIHA", "campaign", "future")) {
            val error = assertFailsWith<IllegalArgumentException> {
                WorldFormat.require(mapOf("worldFormat" to value))
            }
            assertTrue(error.message.orEmpty().contains("unsupported worldFormat"))
        }
    }

    @Test fun `retired keys are refused even when the new marker is present`() {
        val config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", "ruleProfile" to "HWIHA")
        val error = assertFailsWith<IllegalArgumentException> { WorldFormat.require(config) }
        assertTrue(error.message.orEmpty().contains("retired world key"))

        val retiredKey = "hwi" + "haCountyWarehouse"
        val meta = mapOf("county" to listOf(mapOf(retiredKey to emptyMap<String, Any>())))
        val nested = assertFailsWith<IllegalArgumentException> {
            WorldFormat.require(mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"), meta)
        }
        assertTrue(nested.message.orEmpty().contains(retiredKey))
    }
}
