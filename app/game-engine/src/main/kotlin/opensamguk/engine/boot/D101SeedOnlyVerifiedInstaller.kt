package opensamguk.engine.boot

import opensamguk.infra.seed.D101SeedOnlyFixedIdentity
import opensamguk.infra.seed.D101SeedOnlyVerifiedMaterial
import opensamguk.infra.seed.D101SelectedCaptureCoordinator
import opensamguk.infra.seed.SelectedSourceUnavailable
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Path

/** ServiceLoader's only seed-only provider. All authority comes from fixed Root pins and signed originals. */
class D101SeedOnlyVerifiedInstaller : D101SeedOnlyInstaller {
    override fun install(): D101SeedOnlyInstallation = try {
        val loader = Thread.currentThread().contextClassLoader ?: throw SelectedSourceUnavailable()
        val identity = D101SeedOnlyFixedIdentity.readInstalled()
        val source = D101SeedInstallationOriginal.readFixed(identity, loader)
        val verified = D101SeedOnlyVerifiedMaterial(
            identity = identity,
            originalOp = source.originalOp,
            expectedIntentSha256 = source.approvalIntentSha256,
            approvalMaterial = source.approvedMaterial(),
            selectedEnvelope = source.selectedEnvelope(),
            configurationOriginal = source.configurationOriginal(),
            typedTargetOriginal = source.typedTargetOriginal(),
            resolverDecisionOriginal = source.resolverDecisionOriginal(),
            actualParserBytecode = source.parserBytecode(),
            actualTopologyRootBytecode = source.topologyRootBytecode(),
            runtimeClassLoader = loader,
        )
        val approval = verified.approvedIntent()
        val generation = D101ApprovedSeedGeneration(approval.originalBytes(), approval.sha256)
        if (source.approvalIntentSha256 != generation.approvalIntentSha256 ||
            source.originalOp != generation.originalOp ||
            source.typedTargetFingerprint != generation.typedTargetFingerprint ||
            source.appSourceSha != generation.appSourceSha ||
            source.imagePins != generation.imagePins ||
            source.actualOptions != generation.options ||
            source.imagePins != identity.imagePins()) throw SelectedSourceUnavailable()
        val jdbc = JdbcTemplate(D101SeedCandidateDataSource.open(source.database))
        object : D101SeedOnlyInstallation {
            override val jdbc: JdbcTemplate = jdbc
            override val artifactsRoot: Path = source.artifactsRoot
            override val actualOptions: Map<String, String> = source.actualOptions.toMap()
            override val originalOp: String = source.originalOp
            override val typedTargetFingerprint: String = source.typedTargetFingerprint
            override val appSourceSha: String = source.appSourceSha
            override val imagePins: Map<String, String> = source.imagePins.toMap()
            override val approvedGeneration: D101ApprovedSeedGeneration = generation

            override fun coordinatorFor(inputs: D101SelectedImportInputs): D101SelectedCaptureCoordinator =
                verified.coordinatorFor(inputs.selectedWorld, actualOptions)

            override fun close() = Unit
        }
    } catch (_: Exception) { throw SelectedSourceUnavailable() }
}
