package opensamguk.engine.boot

import opensamguk.infra.seed.SelectedSourceUnavailable
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import org.junit.jupiter.api.io.TempDir
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class D101PreIntentSelectedCaptureTest {
    private val options = mapOf(
        "SERVER_NAME" to "빼섭", "SERVER_GENERATION" to "0",
        "SCENARIO_CODE" to "scenario_3190", "SCENARIO_SEED_ENABLED" to "true",
        "SCENARIO_LOOKUP_DIR" to "", "RESET_MAXGENERAL" to "50",
        "RESET_FIRST_TURN" to "immediate", "RESET_EXTEND" to "1",
        "RESET_TURNTERM" to "60", "RESET_BLOCK_GENERAL_CREATE" to "1",
        "RESET_NPCMODE" to "0", "RESET_SHOW_IMG_LEVEL" to "3", "RESET_FICTION" to "1",
    )

    @Test
    fun `same importer selects raw five and topology without JDBC`() {
        val snapshot = D101PreIntentSelectedCapture(D101PreIntentInputsSource {
            D101PreIntentFixedInputs(options, Path.of("../.."))
        }).capture()
        val raw = snapshot.rawOriginals()
        assertEquals(setOf("selected-scenario.json", "classpath-scenario.json", "tiles.json",
            "world.json", "roads.json"), raw.keys)
        assertEquals(options, snapshot.effectiveOptions())
        assertEquals(options - setOf("SERVER_NAME", "SERVER_GENERATION"), snapshot.inputs.parsedImporterOptions)
        assertContentEquals(snapshot.inputs.mapOriginalBytes(), raw.getValue("world.json"))
        assertEquals(snapshot.inputs.selectedWorld.variant.artifactId, snapshot.world.artifactSetId)
        assertEquals(snapshot.world.topologyContentHash,
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(snapshot.world.canonicalTopologyBytes())))
        val topologyInputs = snapshot.world.topologyOriginals()
        assertTrue(topologyInputs.isNotEmpty())
        assertEquals(snapshot.inputs.selectedWorld.projection.topology.artifactHashes.getValue("dryLandProjectionPolicy"),
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(topologyInputs.getValue("dryLandProjectionPolicy"))))
        assertContentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()),
            snapshot.parserClassOriginal().copyOfRange(0, 4))
    }

    @Test
    fun `missing fixed source and altered importer options fail closed`(@TempDir emptyRoot: Path) {
        assertFailsWith<SelectedSourceUnavailable> { D101PreIntentSelectedCapture(null).capture() }
        val altered = options + ("RESET_MAXGENERAL" to "51")
        assertFailsWith<SelectedSourceUnavailable> {
            D101PreIntentSelectedCapture(D101PreIntentInputsSource {
                D101PreIntentFixedInputs(altered, Path.of("../.."))
            }).capture()
        }
        assertFailsWith<SelectedSourceUnavailable> {
            D101PreIntentSelectedCapture(D101PreIntentInputsSource {
                D101PreIntentFixedInputs(options, emptyRoot)
            }).capture()
        }
    }
}
