package opensamguk.infra.seed

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Clock

/** A concrete seed-only approval/selected-source factory. Identity is loaded
 * from a separate fixed Root trust mount, never constructed from manifest17.
 * The same actual Boot parser/root topology bytes and selected world feed the
 * signed consumer. The topology class SHA identifies its root class only;
 * helper code is bound by the Root-pinned image and actual canonical hash. */
class D101SeedOnlyVerifiedMaterial(
    private val identity: D101SeedOnlyFixedIdentity,
    originalOp: String,
    expectedIntentSha256: String,
    approvalMaterial: ByteArray,
    selectedEnvelope: ByteArray,
    configurationOriginal: ByteArray,
    typedTargetOriginal: ByteArray,
    resolverDecisionOriginal: ByteArray,
    actualParserBytecode: ByteArray,
    actualTopologyRootBytecode: ByteArray,
    private val runtimeClassLoader: ClassLoader,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val approvalWire = approvalMaterial.copyOf()
    private val selectedWire = selectedEnvelope.copyOf()
    private val configuration = configurationOriginal.copyOf()
    private val target = typedTargetOriginal.copyOf()
    private val decision = resolverDecisionOriginal.copyOf()
    private val parser = actualParserBytecode.copyOf()
    private val topologyRoot = actualTopologyRootBytecode.copyOf()
    private val verifier = D101ApprovedSeedIntentVerifier(
        D101SeedAuthorityReader { approvalWire.copyOf() }, identity.publicKeySpki(),
        identity.publicKeySpkiSha256, identity.producerIdentity, originalOp, expectedIntentSha256, clock,
    )
    private val approved = verifier.readVerified()
    private val mapper = ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val intent = mapper.readTree(approved.originalBytes())
    val parserBytecodeSha256: String = selectedOriginalSha(parser)
    val topologyRootBytecodeSha256: String = selectedOriginalSha(topologyRoot)

    init {
        fun classOriginal(bytes: ByteArray) = bytes.size in 4..2*1024*1024 &&
            bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0xCA.toByte(), 0xFE.toByte(), 0xBA.toByte(), 0xBE.toByte()))
        if (!classOriginal(parser) || !classOriginal(topologyRoot) || selectedWire.isEmpty() || selectedWire.size > 96*1024 ||
            configuration.isEmpty() || configuration.size > 64*1024 || decision.isEmpty() || decision.size > 64*1024 ||
            target.isEmpty() || target.size > 16*1024 || selectedOriginalSha(target) != intent["targetFingerprint"].textValue()) unavailable()
        val pins = intent["newImageDigests"]
        if (pins.fieldNames().asSequence().associateWith { pins[it].textValue() } != identity.imagePins()) unavailable()
    }

    /** Rechecks the original signed grant's freshness; never renews it. C4
     * constructs D101ApprovedSeedGeneration from these same bytes and SHA. */
    fun approvedIntent(): D101ApprovedSeedIntentVerifier.D101VerifiedSeedApproval = verifier.readVerified().also {
        if (it.sha256 != approved.sha256 || !it.originalBytes().contentEquals(approved.originalBytes())) unavailable()
    }

    fun coordinatorFor(selectedWorld: ResolvedWorldArtifacts, actualParsedFullOptions: Map<String, String>): D101SelectedCaptureCoordinator {
        approvedIntent()
        if (actualParsedFullOptions.keys != D101EffectiveSeedOptionsProvenance.ALLOWED) unavailable()
        val world = D101WorldArtifactCapture.capture(selectedWorld)
        val observedOptions = D101EffectiveSeedOptionsProvenance.capture(
            configuration, selectedOriginalSha(configuration), actualParsedFullOptions.toMap(), parser, parserBytecodeSha256,
        )
        val source = D101SelectedSourceReceiptSource(
            D101SignedSelectedReceiptReader { selectedWire.copyOf() }, identity.publicKeySpki(),
            identity.publicKeySpkiSha256, identity.producerIdentity, world, observedOptions,
            topologyRoot, target, decision, clock,
        )
        val sameApprovedSource = SelectedSourceCustodySource { op, fingerprint, app, images, originals ->
            val result = source.readVerified(op, fingerprint, app, images, originals)
            if (result.selectedSourceReceiptSha256 != intent["selectedSourceReceiptSha256"].textValue() ||
                op != intent["operationId"].textValue() || fingerprint != intent["targetFingerprint"].textValue() ||
                app != intent["appSourceSha"].textValue() || images != identity.imagePins()) unavailable()
            result
        }
        return D101SelectedCaptureCoordinator(D101SelectedSourceCustody(sameApprovedSource), runtimeClassLoader)
    }

    private fun unavailable(): Nothing = throw SelectedSourceUnavailable()
}
