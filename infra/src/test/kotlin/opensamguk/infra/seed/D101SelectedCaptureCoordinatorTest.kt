package opensamguk.infra.seed

import opensamguk.logic.world.WorldMapVariant
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.nio.file.Files
import kotlin.test.*

/** Repository bundle and synthetic scenario/producer. No FINAL_SELECTED claim. */
class D101SelectedCaptureCoordinatorTest {
    private val op = "a".repeat(32)
    private val target = "b".repeat(64)
    private val app = "c".repeat(40)
    private val images = listOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis")
        .associateWith { "sha256:" + "d".repeat(64) }
    private val wire = """ {"title":"same captured original","startYear":180,"map":{},"const":{},"nation":[],"general":[],"general_ex":[],"diplomacy":[]} """.toByteArray()

    @Test fun `selected world copies originals and independently checks complete topology inputs`() {
        val capture = D101WorldArtifactCapture.capture(world)
        assertEquals(world.variant.artifactId, capture.artifactSetId)
        assertEquals(world.projection.topology.contentHash, capture.topologyContentHash)
        assertEquals(world.projection.topology.artifactHashes.keys, capture.topologyOriginals().keys)
        assertEquals(world.projection.topology.contentHash, selectedOriginalSha(capture.canonicalTopologyBytes()))
        val originals = capture.mapOriginals()
        assertEquals(setOf("tiles.json", "world.json", "roads.json"), originals.keys)
        assertContentEquals(world.artifactBytes("data/map/han-land-roads-v1.json"), originals.getValue("roads.json"))
        originals.getValue("world.json").fill(0)
        assertContentEquals(world.artifactBytes("infra/src/main/resources/map/han-world-v3.json"), capture.mapOriginals().getValue("world.json"))
        capture.topologyOriginals().values.forEach { it.fill(0) }
        capture.canonicalTopologyBytes().fill(0)
        assertEquals(world.projection.topology.contentHash, selectedOriginalSha(capture.canonicalTopologyBytes()))
    }

    @Test fun `changed topology original and missing roads stay unavailable`() {
        val inputs = world.projection.topology.artifactHashes.keys.associateWith(world::artifactBytes).toMutableMap()
        inputs["data/map/han-land-roads-v1.json"] = world.artifactBytes("data/map/han-land-roads-v1.json")
        inputs["infra/src/main/resources/map/han-world-v3.json"] = "{}".toByteArray()
        assertFailsWith<SelectedSourceUnavailable> {
            D101WorldArtifactCapture.capture(ResolvedWorldArtifacts(world.variant, world.projection, inputs))
        }
        inputs["infra/src/main/resources/map/han-world-v3.json"] = world.artifactBytes("infra/src/main/resources/map/han-world-v3.json")
        inputs.remove("data/map/han-land-roads-v1.json")
        assertFailsWith<SelectedSourceUnavailable> {
            D101WorldArtifactCapture.capture(ResolvedWorldArtifacts(world.variant, world.projection, inputs))
        }
    }

    @Test fun `absent producer never invokes a writer consumer`() {
        val original = scenario()
        var called = false
        assertFailsWith<SelectedSourceUnavailable> {
            D101SelectedCaptureCoordinator().consume(op, target, app, images, original, world) { _, _, _ -> called = true }
        }
        assertFalse(called)
    }

    @Test fun `same parsed snapshot and handle originals reach consumer then handle closes`() {
        val original = scenario()
        var handle: VerifiedSelectedBundleHandle? = null
        val result = coordinator().consume(op, target, app, images, original, world) { parsed, selected, extend ->
            handle = selected
            assertEquals("same captured original", parsed.title)
            assertEquals(1, extend)
            selected.openOriginal("selected-scenario.json").use { it.readAllBytes() }
        }
        assertContentEquals(wire, result)
        assertFailsWith<SelectedSourceUnavailable> { requireNotNull(handle).openOriginal("selected-scenario.json") }
    }

    @Test fun `consumer failure still closes owned handle`() {
        val original = scenario()
        var handle: VerifiedSelectedBundleHandle? = null
        assertFailsWith<IllegalArgumentException> {
            coordinator().consume(op, target, app, images, original, world) { _, selected, _ ->
                handle = selected
                throw IllegalArgumentException("synthetic consumer failed")
            }
        }
        assertFailsWith<SelectedSourceUnavailable> { requireNotNull(handle).openOriginal("selected-scenario.json") }
    }

    @Test fun `receipt from another selected world cannot reach consumer`() {
        val original = scenario()
        var called = false
        assertFailsWith<SelectedSourceUnavailable> {
            coordinator("f".repeat(64)).consume(op, target, app, images, original, world) { _, _, _ -> called = true }
        }
        assertFalse(called)
    }

    @Test fun `RESET_EXTEND accepts exact typed values with no default`() {
        val original = scenario()
        assertEquals(0, coordinator(resetExtend = "0").consume(op, target, app, images, original, world) { _, _, extend -> extend })
        for (invalid in listOf("", "true", "01", "2")) {
            var called = false
            assertFailsWith<SelectedSourceUnavailable>(invalid) {
                coordinator(resetExtend = invalid).consume(op, target, app, images, original, world) { _, _, _ -> called = true }
            }
            assertFalse(called)
        }
    }

    @Test fun `classpath selection reuses its one snapshot without another resource read`() {
        val original = scenario()
        val forbiddenReader = object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): java.io.InputStream? = error("re-read selected classpath")
        }
        val bytes = coordinator(comparisonClassLoader = forbiddenReader).consume(op, target, app, images, original, world) { _, handle, _ ->
            handle.openOriginal("classpath-scenario.json").use { it.readAllBytes() }
        }
        assertContentEquals(wire, bytes)
    }

    @Test fun `external snapshot keeps selection while infra captures comparison once`(@TempDir temporary: Path) {
        val path = temporary.toRealPath().resolve("scenario_3190.json")
        Files.write(path, wire)
        val original = CapturedScenarioOriginal.external(path, "scenario_3190.json")
        Files.writeString(path, "changed after selection")
        val comparison = "different classpath comparison bytes".toByteArray()
        var reads = 0
        val loader = object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): java.io.InputStream {
                assertEquals("scenario/scenario_3190.json", name)
                reads++
                return ByteArrayInputStream(comparison)
            }
        }
        coordinator(comparisonClassLoader = loader).consume(op, target, app, images, original, world) { parsed, handle, extend ->
            assertEquals("same captured original", parsed.title)
            assertEquals(1, extend)
            assertContentEquals(wire, handle.openOriginal("selected-scenario.json").use { it.readAllBytes() })
            assertContentEquals(comparison, handle.openOriginal("classpath-scenario.json").use { it.readAllBytes() })
            assertEquals(SelectedScenarioOrigin.EXTERNAL, handle.binding.scenarioOrigin)
        }
        assertEquals(1, reads)
    }

    @Test fun `missing comparison fails closed without a consumer fallback`(@TempDir temporary: Path) {
        val path = temporary.toRealPath().resolve("scenario_3190.json")
        Files.write(path, wire)
        val original = CapturedScenarioOriginal.external(path, "scenario_3190.json")
        val missing = object : ClassLoader(null) {}
        var called = false
        assertFailsWith<SelectedSourceUnavailable> {
            coordinator(comparisonClassLoader = missing).consume(op, target, app, images, original, world) { _, _, _ -> called = true }
        }
        assertFalse(called)
    }

    private fun scenario() = CapturedScenarioOriginal.classpath(object : ClassLoader(null) {
        override fun getResourceAsStream(name: String) = ByteArrayInputStream(wire)
    }, "scenario/scenario_3190.json")

    private fun coordinator(contentHash: String = world.projection.topology.contentHash, resetExtend: String = "1", comparisonClassLoader: ClassLoader = object : ClassLoader(null) {
        override fun getResourceAsStream(name: String) = ByteArrayInputStream(wire)
    }) =
        D101SelectedCaptureCoordinator(D101SelectedSourceCustody(SelectedSourceCustodySource { operation, fp, commit, pins, originals ->
            object : SelectedBundleBinding {
                private val receipt = "SYNTHETIC_ONLY producer receipt".toByteArray()
                private val options = mapOf("SERVER_NAME" to "빼섭", "SERVER_GENERATION" to "0", "SCENARIO_CODE" to "scenario_3190",
                    "SCENARIO_SEED_ENABLED" to "true", "SCENARIO_LOOKUP_DIR" to "", "RESET_MAXGENERAL" to "50",
                    "RESET_FIRST_TURN" to "immediate", "RESET_EXTEND" to resetExtend, "RESET_TURNTERM" to "60",
                    "RESET_BLOCK_GENERAL_CREATE" to "1", "RESET_NPCMODE" to "0", "RESET_SHOW_IMG_LEVEL" to "3")
                override val originalOp = operation
                override val typedTargetFingerprint = fp
                override val appSourceSha = commit
                override val selectionStatus = "FINAL_SELECTED" // Synthetic only.
                override val scenarioOrigin = originals.selectedOrigin
                override val scenarioLogicalId = originals.selectedLogicalId
                override val classpathLogicalId = originals.classpathLogicalId
                override val selectedSourceReceiptSha256 = selectedOriginalSha(receipt)
                override val artifactSetId = world.variant.artifactId
                override val variant = world.variant.name
                override val topologyRevision = world.projection.topology.topologyRevision
                override val topologyContentHash = contentHash
                override val topologyContentHashProvenanceSha256 = "e".repeat(64)
                override fun imagePins() = pins
                override fun originalReceipt() = receipt.copyOf()
                override fun originals() = originals.pins()
                override fun effectiveOptions() = options
                override fun optionProvenance() = options.keys.associateWith { "e".repeat(64) }
            }
        }), comparisonClassLoader)

    companion object {
        private val world by lazy {
            WorldArtifactsResolver(Path.of("..").toAbsolutePath().normalize()).artifacts(WorldMapVariant.PROVINCE_WORLD)
        }
    }
}
