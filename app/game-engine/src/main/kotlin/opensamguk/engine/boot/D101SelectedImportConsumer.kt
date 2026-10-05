package opensamguk.engine.boot

import opensamguk.infra.seed.D101SelectedCaptureCoordinator
import opensamguk.infra.seed.SelectedSourceUnavailable

/** C4's read-only consumer. The approved installer owns coordinator and runtime pins. */
class D101SelectedImportConsumer(
    private val coordinator: D101SelectedCaptureCoordinator,
    private val gate: D101SelectedImportGate = D101SelectedImportGate(),
) {
    fun verifyBeforeWrite(
        inputs: D101SelectedImportInputs,
        originalOp: String,
        typedTargetFingerprint: String,
        appSourceSha: String,
        imagePins: Map<String, String>,
    ): D101SelectedRosterExpectation.Counts = coordinator.consume(
        originalOp = originalOp,
        typedTargetFingerprint = typedTargetFingerprint,
        appSourceSha = appSourceSha,
        imagePins = imagePins,
        selectedScenario = inputs.scenarioOriginal,
        selectedWorld = inputs.selectedWorld,
    ) { parsedAgain, handle, boundExtend ->
        if (parsedAgain != inputs.parsedScenario) throw SelectedSourceUnavailable()
        gate.requireReady(inputs, handle, boundExtend)
    }
}
