package opensamguk.engine.boot

import opensamguk.infra.seed.D101SelectedCaptureCoordinator
import opensamguk.infra.seed.SelectedSourceUnavailable

/** Frozen values retained after C8 closes its selected handle. Not an operational receipt. */
data class D101SelectedImportObservation(
    val counts: D101SelectedRosterExpectation.Counts,
    val selectedSourceReceiptSha256: String,
    val effectiveOptions: Map<String, String>,
    val optionProvenance: Map<String, String>,
)

/** C4's read-only consumer. The approved installer owns coordinator and runtime pins. */
class D101SelectedImportConsumer(
    /** Constructed for the same selected objects, because C8 source needs this selected world. */
    private val coordinatorFor: (D101SelectedImportInputs) -> D101SelectedCaptureCoordinator,
    private val gate: D101SelectedImportGate = D101SelectedImportGate(),
) {
    fun verifyBeforeWrite(
        inputs: D101SelectedImportInputs,
        originalOp: String,
        typedTargetFingerprint: String,
        appSourceSha: String,
        imagePins: Map<String, String>,
    ): D101SelectedRosterExpectation.Counts = verifyBeforeWriteDetailed(
        inputs, originalOp, typedTargetFingerprint, appSourceSha, imagePins,
    ).counts

    fun verifyBeforeWriteDetailed(
        inputs: D101SelectedImportInputs,
        originalOp: String,
        typedTargetFingerprint: String,
        appSourceSha: String,
        imagePins: Map<String, String>,
    ): D101SelectedImportObservation = coordinatorFor(inputs).consume(
        originalOp = originalOp,
        typedTargetFingerprint = typedTargetFingerprint,
        appSourceSha = appSourceSha,
        imagePins = imagePins,
        selectedScenario = inputs.scenarioOriginal,
        selectedWorld = inputs.selectedWorld,
    ) { parsedAgain, handle, boundExtend ->
        if (parsedAgain != inputs.parsedScenario) throw SelectedSourceUnavailable()
        if (inputs.mapResourceLogicalId != "map/han-world-v3.json") throw SelectedSourceUnavailable()
        val parsedMapOriginal = inputs.mapOriginalBytes()
        val worldPin = handle.binding.originals()["world.json"] ?: throw SelectedSourceUnavailable()
        if (worldPin.byteLength != parsedMapOriginal.size.toLong() ||
            !handle.openOriginal("world.json").use { it.readNBytes(parsedMapOriginal.size + 1) }
                .contentEquals(parsedMapOriginal)) throw SelectedSourceUnavailable()
        if (handle.binding.effectiveOptions().filterKeys { it in IMPORTER_OPTION_KEYS } != inputs.parsedImporterOptions) {
            throw SelectedSourceUnavailable()
        }
        val counts = gate.requireReady(inputs, handle, boundExtend)
        D101SelectedImportObservation(counts, handle.binding.selectedSourceReceiptSha256,
            handle.binding.effectiveOptions().toMap(), handle.binding.optionProvenance().toMap())
    }

    private companion object {
        val IMPORTER_OPTION_KEYS = setOf("SCENARIO_CODE", "SCENARIO_SEED_ENABLED", "SCENARIO_LOOKUP_DIR",
            "RESET_MAXGENERAL", "RESET_FIRST_TURN", "RESET_EXTEND", "RESET_TURNTERM",
            "RESET_BLOCK_GENERAL_CREATE", "RESET_NPCMODE", "RESET_SHOW_IMG_LEVEL", "RESET_FICTION")
    }
}
