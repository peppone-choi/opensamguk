package opensamguk.engine.boot

import opensamguk.infra.seed.SelectedSourceUnavailable
import opensamguk.infra.seed.VerifiedSelectedBundleHandle

/** C4 writer admission check. The installer supplies the C8 verified handle from this invocation. */
class D101SelectedImportGate(
    private val expectationReader: D101SelectedBundleExpectationReader = D101SelectedBundleExpectationReader(),
) {
    fun requireReady(
        inputs: D101SelectedImportInputs,
        handle: VerifiedSelectedBundleHandle,
        coordinatorEffectiveResetExtend: Int,
    ): D101SelectedRosterExpectation.Counts {
        if (coordinatorEffectiveResetExtend != inputs.effectiveResetExtend ||
            inputs.resetExtendInput != inputs.effectiveResetExtend.toString()) throw SelectedSourceUnavailable()
        val binding = handle.binding
        val scenarioPin = binding.originals()["selected-scenario.json"] ?: throw SelectedSourceUnavailable()
        if (binding.scenarioOrigin != inputs.scenarioOriginal.origin ||
            binding.scenarioLogicalId != inputs.scenarioOriginal.logicalId ||
            scenarioPin.rawSha256 != inputs.scenarioOriginal.rawSha256 ||
            scenarioPin.byteLength != inputs.scenarioOriginal.byteLength) throw SelectedSourceUnavailable()
        val world = inputs.selectedWorld
        val topology = world.projection.topology
        if (binding.artifactSetId != world.variant.artifactId || binding.variant != world.variant.name ||
            binding.topologyRevision != topology.topologyRevision ||
            binding.topologyContentHash != topology.contentHash) throw SelectedSourceUnavailable()
        return expectationReader.calculate(handle, inputs.effectiveResetExtend)
    }
}
