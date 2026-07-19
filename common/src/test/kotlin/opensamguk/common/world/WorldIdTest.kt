package opensamguk.common.world

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith

class WorldIdTest {
    @Test
    fun `positive values are accepted`() {
        assertEquals(1, WorldId(1).value)
    }

    @Test
    fun `zero is rejected`() {
        assertFailsWith<IllegalArgumentException> { WorldId(0) }
    }

    @Test
    fun `negative values are rejected`() {
        assertFailsWith<IllegalArgumentException> { WorldId(-1) }
    }

    @Test
    fun `JSON round-trips as an integer scalar`() {
        val worldId = WorldId(42)

        val encoded = Json.encodeToString(WorldId.serializer(), worldId)

        assertEquals("42", encoded)
        assertEquals(worldId, Json.decodeFromString(WorldId.serializer(), encoded))
    }

    @Test
    fun `JSON decoder accepts a positive integer scalar`() {
        assertEquals(WorldId(42), Json.decodeFromString(WorldId.serializer(), "42"))
    }

    @Test
    fun `JSON decoder rejects a numeric string`() {
        assertFails { Json.decodeFromString(WorldId.serializer(), "\"42\"") }
    }

    @Test
    fun `JSON decoder rejects an object`() {
        assertFails { Json.decodeFromString(WorldId.serializer(), "{\"value\":42}") }
    }

    @Test
    fun `JSON decoder rejects zero and negative scalars`() {
        for (encoded in listOf("0", "-1")) {
            assertFails { Json.decodeFromString(WorldId.serializer(), encoded) }
        }
    }

    @Test
    @OptIn(ExperimentalSerializationApi::class)
    fun `JSON serializer declares an integer scalar descriptor`() {
        assertEquals(PrimitiveKind.INT, WorldId.serializer().descriptor.kind)
    }
}
