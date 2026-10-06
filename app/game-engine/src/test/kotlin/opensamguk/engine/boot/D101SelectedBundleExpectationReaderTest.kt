package opensamguk.engine.boot

import opensamguk.infra.seed.SelectedBundleBinding
import opensamguk.infra.seed.SelectedOriginalPin
import opensamguk.infra.seed.SelectedScenarioOrigin
import opensamguk.infra.seed.SelectedSourceUnavailable
import opensamguk.infra.seed.VerifiedSelectedBundleHandle
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A synthetic handle tests the C4 consumer; it is never an approved source receipt. */
class D101SelectedBundleExpectationReaderTest {
    private val fixture = requireNotNull(javaClass.classLoader.getResourceAsStream("scenario/scenario_3190.json"))
        .use { it.readBytes() }
    private val fixtureSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture))

    @Test
    fun `selected handle original derives the classpath fixture roster`() {
        val counts = D101SelectedBundleExpectationReader().calculate(handle(fixture), 1)
        assertEquals(fixtureSha, counts.scenarioRawSha256)
        assertEquals(fixture.size, counts.scenarioRawByteLength)
        assertEquals(384, counts.activeGeneralRows)
    }

    @Test
    fun `reopened original drift and missing effective option close the consumer`() {
        val reader = D101SelectedBundleExpectationReader()
        assertFailsWith<SelectedSourceUnavailable> { reader.calculate(handle(fixture, fixture.copyOf().also { it[0] = 0 }), 1) }
        assertFailsWith<SelectedSourceUnavailable> { reader.calculate(handle(fixture, effectiveExtend = null), 1) }
        assertFailsWith<SelectedSourceUnavailable> { reader.calculate(handle(fixture, status = "CANDIDATE"), 1) }
        assertFailsWith<SelectedSourceUnavailable> { reader.calculate(handle(fixture), 0) }
    }

    private fun handle(
        pinned: ByteArray,
        exposed: ByteArray = pinned,
        effectiveExtend: String? = "1",
        status: String = "FINAL_SELECTED",
    ): VerifiedSelectedBundleHandle {
        val pin = SelectedOriginalPin("selected-scenario.json", fixtureSha, pinned.size.toLong())
        val selectedBinding = object : SelectedBundleBinding {
            override val originalOp = "0".repeat(32)
            override val typedTargetFingerprint = "0".repeat(64)
            override val appSourceSha = "0".repeat(40)
            override val selectionStatus = status
            override val scenarioOrigin = SelectedScenarioOrigin.CLASSPATH
            override val scenarioLogicalId = "scenario/scenario_3190.json"
            override val classpathLogicalId = scenarioLogicalId
            override val selectedSourceReceiptSha256 = "0".repeat(64)
            override val artifactSetId = "synthetic"
            override val variant = "synthetic"
            override val topologyRevision = "synthetic"
            override val topologyContentHash = "0".repeat(64)
            override val topologyContentHashProvenanceSha256 = "0".repeat(64)
            override fun imagePins(): Map<String, String> = emptyMap()
            override fun originalReceipt(): ByteArray = byteArrayOf()
            override fun originals(): Map<String, SelectedOriginalPin> = mapOf(pin.logicalArtifactId to pin)
            override fun effectiveOptions(): Map<String, String> = effectiveExtend?.let { mapOf("RESET_EXTEND" to it) } ?: emptyMap()
            override fun optionProvenance(): Map<String, String> = mapOf("RESET_EXTEND" to "0".repeat(64))
        }
        return object : VerifiedSelectedBundleHandle {
            override val binding = selectedBinding
            override fun openOriginal(logicalArtifactId: String): InputStream {
                require(logicalArtifactId == pin.logicalArtifactId)
                return ByteArrayInputStream(exposed)
            }
            override fun close() = Unit
        }
    }
}
