package opensamguk.gameapi.controller

import com.fasterxml.jackson.databind.ObjectMapper
import java.nio.file.Path
import java.security.MessageDigest
import opensamguk.gameapi.read.ActiveWorldArtifactResolver
import opensamguk.gameapi.read.ActiveWorldArtifactSnapshot
import opensamguk.gameapi.read.WorldStateReadEntity
import opensamguk.infra.seed.WorldArtifactsResolver
import opensamguk.logic.world.WorldMapVariant
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class JuMapControllerTest {
    @Test
    fun `all historical terrain variants expose hash bound canonical Ju`() {
        val worlds = Mockito.mock(ActiveWorldArtifactResolver::class.java)
        val bundles = WorldArtifactsResolver(Path.of("../.."))
        val controller = JuMapController(worlds, "../../data/map/han-ju-index-v1.json")
        val mapper = ObjectMapper()
        for (variant in WorldMapVariant.entries) {
            val selected = bundles.artifacts(variant)
            Mockito.`when`(worlds.resolve()).thenReturn(
                ActiveWorldArtifactSnapshot(WorldStateReadEntity(id = 7), emptyList(), selected),
            )
            val terrain = selected.artifactBytes("data/map/han-tiles.json")
            val hash = MessageDigest.getInstance("SHA-256").digest(terrain)
                .joinToString("") { "%02x".format(it) }
            val response = controller.ju("han-world-v3", null)
            assertEquals(200, response.statusCode.value())
            val json = mapper.readTree(assertNotNull(response.body))
            assertEquals(hash, json.path("sourceSha256").asText())
            assertEquals(mapper.readTree(terrain).path("parentRegions").size(), json.path("juByParent").size())
            assertEquals(304, controller.ju("han-world-v3", response.headers.eTag).statusCode.value())
            assertEquals(404, controller.ju("che", null).statusCode.value())
        }
    }
}
