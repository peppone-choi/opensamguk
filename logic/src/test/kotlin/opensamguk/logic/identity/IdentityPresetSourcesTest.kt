package opensamguk.logic.identity

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class IdentityPresetSourcesTest {
    private val payload = Files.readString(Path.of("..", "data", "curated", "han", "identity-presets.json"))

    @Test
    fun `every preset has a source badge or an explicit game term badge`() {
        val presets = IdentityPresetSources.parse(payload)
        assertEquals(15, presets.size)
        val virtue = presets.single { it.id == "identity.virtue" }
        assertEquals(listOf("게임 용어"), virtue.badges)
        val taiping = presets.single { it.id == "identity.taiping" }
        assertEquals(listOf("三國志 卷08", "後漢書 卷071"), taiping.badges)
    }

    @Test
    fun `game term badge cannot be silently removed`() {
        val broken = payload.replaceFirst("\"displayBadge\": \"게임 용어\"", "\"displayBadge\": \"\"")
        assertFailsWith<IllegalArgumentException> { IdentityPresetSources.parse(broken) }
    }

    @Test
    fun `source priority is the user selected romance policy`() {
        val broken = payload.replaceFirst("\"defaultWhenSourcesConflict\": \"ROMANCE\"",
            "\"defaultWhenSourcesConflict\": \"PRIMARY\"")
        assertFailsWith<IllegalArgumentException> { IdentityPresetSources.parse(broken) }
    }
}
