package opensamguk.infra.seed

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Collections

/** Raw byte facts only. Neither this pin nor a captured stream is authority. */
data class SelectedOriginalPin(val logicalArtifactId: String, val rawSha256: String, val byteLength: Long)

/** Returned only by the fixed approved producer after actual source/custody,
 * options, runtime pins and receipt provenance checks. There is no public
 * FINAL_SELECTED builder or production implementation in this source slice. */
interface SelectedBundleBinding {
    val originalOp: String
    val typedTargetFingerprint: String
    val appSourceSha: String
    val selectionStatus: String
    val scenarioOrigin: SelectedScenarioOrigin
    val scenarioLogicalId: String
    val classpathLogicalId: String
    val selectedSourceReceiptSha256: String
    val artifactSetId: String
    val variant: String
    val topologyRevision: String
    val topologyContentHash: String
    val topologyContentHashProvenanceSha256: String
    fun imagePins(): Map<String, String>
    fun originalReceipt(): ByteArray
    fun originals(): Map<String, SelectedOriginalPin>
    fun effectiveOptions(): Map<String, String>
    fun optionProvenance(): Map<String, String>
}

interface VerifiedSelectedBundleHandle : AutoCloseable {
    val binding: SelectedBundleBinding
    fun openOriginal(logicalArtifactId: String): InputStream
}

class SelectedSourceUnavailable : IllegalStateException("selected source custody is unavailable")

/** Internal only: the producer result is copied/checked before construction.
 * No path reopening, resolver invocation or default option exists here. */
internal class CapturedSelectedBundleHandle(
    override val binding: SelectedBundleBinding,
    originals: Map<String, ByteArray>,
) : VerifiedSelectedBundleHandle {
    private val bytes = originals.mapValues { it.value.copyOf() }
    private var closed = false

    @Synchronized
    override fun openOriginal(logicalArtifactId: String): InputStream {
        if (closed) throw SelectedSourceUnavailable()
        val original = bytes[logicalArtifactId] ?: throw SelectedSourceUnavailable()
        // A stream owns its defensive copy. Its close/read cannot change other
        // consumer streams or the same selected object used by the parser.
        return ByteArrayInputStream(original.copyOf())
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            bytes.values.forEach { it.fill(0) }
        }
    }
}

internal class FrozenSelectedBundleBinding(source: SelectedBundleBinding) : SelectedBundleBinding {
    override val originalOp = source.originalOp
    override val typedTargetFingerprint = source.typedTargetFingerprint
    override val appSourceSha = source.appSourceSha
    override val selectionStatus = source.selectionStatus
    override val scenarioOrigin = source.scenarioOrigin
    override val scenarioLogicalId = source.scenarioLogicalId
    override val classpathLogicalId = source.classpathLogicalId
    override val selectedSourceReceiptSha256 = source.selectedSourceReceiptSha256
    override val artifactSetId = source.artifactSetId
    override val variant = source.variant
    override val topologyRevision = source.topologyRevision
    override val topologyContentHash = source.topologyContentHash
    override val topologyContentHashProvenanceSha256 = source.topologyContentHashProvenanceSha256
    private val pins = Collections.unmodifiableMap(source.imagePins().toMap())
    private val receipt = source.originalReceipt().copyOf()
    private val artifacts = Collections.unmodifiableMap(source.originals().toMap())
    private val options = Collections.unmodifiableMap(source.effectiveOptions().toMap())
    private val provenance = Collections.unmodifiableMap(source.optionProvenance().toMap())
    override fun imagePins(): Map<String, String> = pins
    override fun originalReceipt(): ByteArray = receipt.copyOf()
    override fun originals(): Map<String, SelectedOriginalPin> = artifacts
    override fun effectiveOptions(): Map<String, String> = options
    override fun optionProvenance(): Map<String, String> = provenance
}
