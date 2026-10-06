package opensamguk.engine.boot

import opensamguk.infra.seed.CapturedScenarioOriginal
import opensamguk.infra.seed.ResolvedWorldArtifacts
import opensamguk.infra.seed.Scenario

/** Same admitted seed invocation, before its first DB write. Not a source receipt. */
class D101SelectedImportInputs(
    val scenarioOriginal: CapturedScenarioOriginal,
    val parsedScenario: Scenario,
    val selectedWorld: ResolvedWorldArtifacts,
    val mapResourceLogicalId: String,
    mapOriginalBytes: ByteArray,
    val effectiveResetExtend: Int,
    /** Raw configured value; null/blank means the existing PHP-derived default was used. */
    val resetExtendInput: String?,
    /** Values this SeedBootstrap actually parsed. Server identity is supplied by the approved installer. */
    val parsedImporterOptions: Map<String, String>,
) {
    private val mapOriginal = mapOriginalBytes.copyOf()
    fun mapOriginalBytes(): ByteArray = mapOriginal.copyOf()
}
