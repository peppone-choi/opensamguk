package opensamguk.infra.seed

/** One selected snapshot feeds both parser and RO consumers before any writer.
 * The owning invocation closes the handle after the consumer returns or fails.
 * A missing approved producer cannot fall back to ordinary scenario import. */
class D101SelectedCaptureCoordinator(
    private val custody: D101SelectedSourceCustody = D101SelectedSourceCustody(),
    private val classLoader: ClassLoader = EffectiveScenarioResolver::class.java.classLoader,
) {
    fun <T> consume(
        originalOp: String,
        typedTargetFingerprint: String,
        appSourceSha: String,
        imagePins: Map<String, String>,
        selectedScenario: CapturedScenarioOriginal,
        selectedWorld: ResolvedWorldArtifacts,
        consumer: (Scenario, VerifiedSelectedBundleHandle, Int) -> T,
    ): T {
        // The comparison is captured in the same infra module. Engine consumers
        // never access internal factories, reopen the selection or need friend paths.
        val classpathResource = "scenario/scenario_3190.json"
        val classpathScenario = when (selectedScenario.origin) {
            SelectedScenarioOrigin.CLASSPATH -> selectedScenario.also {
                if (it.logicalId != classpathResource) throw SelectedSourceUnavailable()
            }
            SelectedScenarioOrigin.EXTERNAL -> {
                if (selectedScenario.logicalId != "scenario_3190.json") throw SelectedSourceUnavailable()
                CapturedScenarioOriginal.classpath(classLoader, classpathResource)
            }
        }
        val world = D101WorldArtifactCapture.capture(selectedWorld)
        return custody.capture(originalOp, typedTargetFingerprint, appSourceSha, imagePins,
            selectedScenario, classpathScenario, world.mapOriginals()).use { handle ->
            val binding = handle.binding
            if (binding.artifactSetId != world.artifactSetId || binding.variant != world.variant ||
                binding.topologyRevision != world.topologyRevision ||
                binding.topologyContentHash != world.topologyContentHash) throw SelectedSourceUnavailable()
            val effectiveResetExtend = when (binding.effectiveOptions()["RESET_EXTEND"]) {
                "0" -> 0
                "1" -> 1
                else -> throw SelectedSourceUnavailable()
            }
            // The original selected object is parsed, never a path reopened or a
            // receipt's text re-encoded. Consumers independently hash handle streams.
            val scenario = ScenarioJson.loadScenario(selectedScenario.utf8())
            consumer(scenario, handle, effectiveResetExtend)
        }
    }
}
