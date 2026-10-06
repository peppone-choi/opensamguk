package opensamguk.infra.seed

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Synthetic input and a synthetic installed producer only. None of these
 * fixtures supply operating FINAL_SELECTED, native FD custody or approval. */
class D101SelectedSourceCustodyTest {
    private val operation = "a".repeat(32)
    private val target = "b".repeat(64)
    private val app = "c".repeat(40)
    private val images = listOf("game-api", "game-engine", "web-game", "game-postgres", "game-redis").associateWith { "sha256:" + "d".repeat(64) }
    private val mapBytes = listOf("tiles.json", "world.json", "roads.json").associateWith { "synthetic $it".toByteArray() }
    private val scenarioWire = """ {"title":"원문 그대로","startYear":180,"map":{},"const":{},"nation":[],"general":[],"general_ex":[],"diplomacy":[]} """.toByteArray()

    @Test
    fun `external snapshot parser hash and stream share one read even after file replacement`(@TempDir temporary: Path) {
        val directory = temporary.toRealPath()
        val path = directory.resolve("scenario_3190.json")
        Files.write(path, scenarioWire)
        val original = CapturedScenarioOriginal.external(path, "scenario_3190.json")
        Files.writeString(path, "different later bytes")
        assertEquals(SelectedScenarioOrigin.EXTERNAL, original.origin)
        assertEquals(selectedOriginalSha(scenarioWire), original.rawSha256)
        assertEquals(scenarioWire.size.toLong(), original.byteLength)
        assertEquals("원문 그대로", ScenarioJson.loadScenario(original.utf8()).title)
        assertContentEquals(scenarioWire, original.openOriginal().use { it.readAllBytes() })
        val copy = original.originalBytes()
        copy.fill(0)
        assertContentEquals(scenarioWire, original.originalBytes())
    }

    @Test
    fun `classpath capture closes its one original stream and never reads again`() {
        val reads = AtomicInteger()
        val closes = AtomicInteger()
        val raw = scenarioWire.copyOf()
        val loader = object : ClassLoader(null) {
            override fun getResourceAsStream(name: String): InputStream? {
                assertEquals("scenario/scenario_3190.json", name)
                reads.incrementAndGet()
                return object : ByteArrayInputStream(raw) { override fun close() { closes.incrementAndGet(); super.close() } }
            }
        }
        val original = CapturedScenarioOriginal.classpath(loader, "scenario/scenario_3190.json")
        raw.fill(0)
        assertEquals(1, reads.get())
        assertEquals(1, closes.get())
        assertContentEquals(scenarioWire, original.originalBytes())
        assertEquals("원문 그대로", ScenarioJson.loadScenario(original.utf8()).title)
        assertEquals(1, reads.get())
    }

    @Test
    fun `malformed empty oversized or missing originals stay unavailable without replacement decoding`() {
        val invalid = classpath(byteArrayOf(0xff.toByte()))
        assertFailsWith<CharacterCodingException> { invalid.utf8() }
        assertFailsWith<SelectedSourceUnavailable> {
            D101SelectedSourceCustody(SelectedSourceCustodySource { _, _, _, _, _ -> error("invalid UTF8 invoked producer") })
                .capture(operation, target, app, images, invalid, invalid, mapBytes)
        }
        for (wire in listOf(byteArrayOf(), ByteArray(16 * 1024 * 1024 + 1))) {
            assertFailsWith<SelectedSourceUnavailable> { classpath(wire) }
        }
        assertFailsWith<SelectedSourceUnavailable> { CapturedScenarioOriginal.classpath(object : ClassLoader(null) {}, "missing") }
    }

    @Test
    fun `selected external symlink parent symlink and directory do not become another payload`(@TempDir temporary: Path) {
        val directory = temporary.toRealPath()
        val original = directory.resolve("scenario_3190.json")
        Files.write(original, scenarioWire)
        val link = directory.resolve("link.json")
        Files.createSymbolicLink(link, original)
        assertFailsWith<SelectedSourceUnavailable> { CapturedScenarioOriginal.external(link, "scenario_3190.json") }
        val alias = directory.resolve("alias")
        Files.createSymbolicLink(alias, directory)
        assertFailsWith<SelectedSourceUnavailable> { CapturedScenarioOriginal.external(alias.resolve("scenario_3190.json"), "scenario_3190.json") }
        assertFailsWith<SelectedSourceUnavailable> { CapturedScenarioOriginal.external(directory, "scenario_3190.json") }
    }

    @Test
    fun `missing actual producer never supplies Verified or FINAL_SELECTED`() {
        val selected = classpath(scenarioWire)
        assertFailsWith<SelectedSourceUnavailable> {
            D101SelectedSourceCustody().capture(operation, target, app, images, selected, selected, mapBytes)
        }
    }

    @Test
    fun `synthetic installed producer binds every original and freezes independent consumer streams`() {
        val selected = classpath(scenarioWire)
        val mutableMaps = mapBytes.mapValues { it.value.copyOf() }
        lateinit var sourceBinding: SyntheticBinding
        val source = SelectedSourceCustodySource { op, fp, commit, pins, originals ->
            for ((id, expected) in originals.pins()) {
                val wire = originals.openOriginal(id).use { it.readAllBytes() }
                assertEquals(expected.rawSha256, selectedOriginalSha(wire))
                assertEquals(expected.byteLength, wire.size.toLong())
            }
            SyntheticBinding(op, fp, commit, pins.toMutableMap(), originals).also { sourceBinding = it }
        }
        val handle = D101SelectedSourceCustody(source).capture(operation, target, app, images, selected, selected, mutableMaps)
        mutableMaps.values.forEach { it.fill(0) }
        sourceBinding.mutableImages.clear()
        sourceBinding.receipt.fill(0)
        sourceBinding.mutableOptions.clear()
        sourceBinding.mutableArtifacts.clear()
        assertEquals(images, handle.binding.imagePins())
        assertTrue(handle.binding.effectiveOptions().isNotEmpty())
        assertEquals(5, handle.binding.originals().size)
        assertEquals(handle.binding.selectedSourceReceiptSha256, selectedOriginalSha(handle.binding.originalReceipt()))
        val receiptCopy = handle.binding.originalReceipt(); receiptCopy.fill(0)
        assertEquals(handle.binding.selectedSourceReceiptSha256, selectedOriginalSha(handle.binding.originalReceipt()))
        val first = handle.openOriginal("selected-scenario.json")
        val second = handle.openOriginal("selected-scenario.json")
        assertEquals(scenarioWire[0].toInt(), first.read())
        assertContentEquals(scenarioWire, second.use { it.readAllBytes() })
        first.close()
        assertFailsWith<SelectedSourceUnavailable> { handle.openOriginal("unselected.json") }
        handle.close(); handle.close()
        assertFailsWith<SelectedSourceUnavailable> { handle.openOriginal("selected-scenario.json") }
    }

    @Test
    fun `binding drift missing provenance and synthetic status never promote a handle`() {
        val selected = classpath(scenarioWire)
        for (mode in listOf("op", "target", "app", "images", "status", "origin", "logical", "raw-hash", "raw-length", "receipt", "topology-provenance", "missing-options", "option-provenance", "secret-option")) {
            val source = SelectedSourceCustodySource { op, fp, commit, pins, originals ->
                val fixture = SyntheticBinding(op, fp, commit, pins.toMutableMap(), originals)
                when (mode) {
                    "op" -> fixture.op = "f".repeat(32)
                    "target" -> fixture.target = "f".repeat(64)
                    "app" -> fixture.app = "f".repeat(40)
                    "images" -> fixture.mutableImages["game-api"] = "sha256:" + "f".repeat(64)
                    "status" -> fixture.status = "SYNTHETIC_ONLY"
                    "origin" -> fixture.origin = SelectedScenarioOrigin.EXTERNAL
                    "logical" -> fixture.logical = "another-scenario.json"
                    "raw-hash" -> fixture.mutableArtifacts["tiles.json"] = fixture.mutableArtifacts.getValue("tiles.json").copy(rawSha256 = "f".repeat(64))
                    "raw-length" -> fixture.mutableArtifacts["tiles.json"] = fixture.mutableArtifacts.getValue("tiles.json").copy(byteLength = 0)
                    "receipt" -> fixture.receiptHash = "f".repeat(64)
                    "topology-provenance" -> fixture.topologyProvenance = "UNKNOWN"
                    "missing-options" -> fixture.mutableOptions.remove("RESET_EXTEND")
                    "option-provenance" -> fixture.provenance["RESET_EXTEND"] = "UNKNOWN"
                    "secret-option" -> { fixture.mutableOptions["ADMIN_PASSWORD"] = "synthetic-forbidden"; fixture.provenance["ADMIN_PASSWORD"] = "e".repeat(64) }
                }
                fixture
            }
            assertFailsWith<SelectedSourceUnavailable>(mode) {
                D101SelectedSourceCustody(source).capture(operation, target, app, images, selected, selected, mapBytes)
            }
        }
    }

    @Test
    fun `same classpath selection cannot be paired with a different comparison read`() {
        val selected = classpath(scenarioWire)
        val different = classpath("different comparison".toByteArray())
        val source = SelectedSourceCustodySource { _, _, _, _, _ -> error("mismatched capture invoked producer") }
        assertFailsWith<SelectedSourceUnavailable> { D101SelectedSourceCustody(source).capture(operation, target, app, images, selected, different, mapBytes) }
    }

    private fun classpath(wire: ByteArray): CapturedScenarioOriginal = CapturedScenarioOriginal.classpath(
        object : ClassLoader(null) { override fun getResourceAsStream(name: String) = ByteArrayInputStream(wire) }, "scenario/scenario_3190.json",
    )

    private class SyntheticBinding(var op: String, var target: String, var app: String, val mutableImages: MutableMap<String, String>, originals: SelectedCapturedOriginals) : SelectedBundleBinding {
        var status = "FINAL_SELECTED" // Synthetic test source only; never operating evidence.
        var origin = originals.selectedOrigin
        var logical = originals.selectedLogicalId
        var receipt = "synthetic installed producer fixture".toByteArray()
        var receiptHash = selectedOriginalSha(receipt)
        val mutableArtifacts = originals.pins().toMutableMap()
        val mutableOptions = mutableMapOf("SERVER_NAME" to "빼섭", "SERVER_GENERATION" to "0", "SCENARIO_CODE" to "scenario_3190", "SCENARIO_SEED_ENABLED" to "true", "SCENARIO_LOOKUP_DIR" to "",
            "RESET_MAXGENERAL" to "50", "RESET_FIRST_TURN" to "immediate", "RESET_EXTEND" to "1", "RESET_TURNTERM" to "60", "RESET_BLOCK_GENERAL_CREATE" to "1", "RESET_NPCMODE" to "0", "RESET_SHOW_IMG_LEVEL" to "3")
        val provenance = mutableOptions.keys.associateWith { "e".repeat(64) }.toMutableMap()
        var topologyProvenance = "f".repeat(64)
        override val originalOp get() = op
        override val typedTargetFingerprint get() = target
        override val appSourceSha get() = app
        override val selectionStatus get() = status
        override val scenarioOrigin get() = origin
        override val scenarioLogicalId get() = logical
        override val classpathLogicalId = originals.classpathLogicalId
        override val selectedSourceReceiptSha256 get() = receiptHash
        override val artifactSetId = "synthetic-set"
        override val variant = "synthetic-variant"
        override val topologyRevision = "synthetic-revision"
        override val topologyContentHash = "1".repeat(64)
        override val topologyContentHashProvenanceSha256 get() = topologyProvenance
        override fun imagePins(): Map<String, String> = mutableImages
        override fun originalReceipt(): ByteArray = receipt
        override fun originals(): Map<String, SelectedOriginalPin> = mutableArtifacts
        override fun effectiveOptions(): Map<String, String> = mutableOptions
        override fun optionProvenance(): Map<String, String> = provenance
    }
}
