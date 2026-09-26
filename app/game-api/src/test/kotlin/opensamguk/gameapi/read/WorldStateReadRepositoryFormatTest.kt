package opensamguk.gameapi.read

import opensamguk.gameapi.config.GameApiProcessWorld
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.Optional
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WorldStateReadRepositoryFormatTest {
    private val raw = mock(WorldStateReadRawRepository::class.java)
    private val repository = WorldStateReadRepository(raw, GameApiProcessWorld(1))

    @Test fun `current world is readable through every process-world entry`() {
        val world = WorldStateReadEntity(id = 1,
            config = mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN"))
        `when`(raw.findById(1)).thenReturn(Optional.of(world))
        assertEquals(world, repository.findProcessWorld())
        assertEquals(listOf(world), repository.findAll())
        assertEquals(world, repository.findById(1).orElseThrow())
    }

    @Test fun `old, unmarked, and sammo worlds return an explicit API conflict`() {
        val bad = listOf(
            emptyMap<String, Any?>() to "worldFormat is missing",
            mapOf("ruleProfile" to "HWIHA") to "retired world key",
            mapOf("worldFormat" to "SAMMO") to "unsupported worldFormat",
            mapOf("worldFormat" to "GENERAL_RETAINER_CAMPAIGN", ("hwi" + "haCountyWarehouse") to true)
                to "retired world key",
        )
        bad.forEach { (config, expected) ->
            `when`(raw.findById(1)).thenReturn(Optional.of(WorldStateReadEntity(id = 1, config = config)))
            for (read in listOf<() -> Any?>({ repository.findProcessWorld() }, { repository.findAll() },
                { repository.findById(1) })) {
                val error = assertFailsWith<ResponseStatusException> { read() }
                assertEquals(HttpStatus.CONFLICT, error.statusCode)
                assertTrue(error.reason.orEmpty().contains(expected), error.reason)
            }
        }
    }
}
