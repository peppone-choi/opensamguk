package opensamguk.engine.boot

import opensamguk.infra.seed.SelectedSourceUnavailable
import opensamguk.infra.seed.VerifiedSelectedBundleHandle
import java.security.MessageDigest
import java.util.HexFormat

/** Independently rereads the verified handle's selected original before calculating the 190 roster. */
class D101SelectedBundleExpectationReader(
    private val expectation: D101SelectedRosterExpectation = D101SelectedRosterExpectation(),
) {
    fun calculate(handle: VerifiedSelectedBundleHandle): D101SelectedRosterExpectation.Counts {
        val binding = handle.binding
        if (binding.selectionStatus != "FINAL_SELECTED") throw SelectedSourceUnavailable()
        val pin = binding.originals()["selected-scenario.json"] ?: throw SelectedSourceUnavailable()
        if (pin.logicalArtifactId != "selected-scenario.json" || pin.byteLength !in 1L..MAX_SCENARIO_BYTES ||
            !SHA.matches(pin.rawSha256)) throw SelectedSourceUnavailable()
        val extend = when (binding.effectiveOptions()["RESET_EXTEND"]) {
            "0" -> 0
            "1" -> 1
            else -> throw SelectedSourceUnavailable()
        }
        if (!SHA.matches(binding.optionProvenance()["RESET_EXTEND"] ?: "")) throw SelectedSourceUnavailable()
        val raw = handle.openOriginal(pin.logicalArtifactId).use { it.readNBytes(pin.byteLength.toInt() + 1) }
        if (raw.size.toLong() != pin.byteLength) throw SelectedSourceUnavailable()
        val actualSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw))
        if (actualSha != pin.rawSha256) throw SelectedSourceUnavailable()
        return expectation.calculate(raw, pin.rawSha256, pin.byteLength, extend)
    }

    companion object {
        private const val MAX_SCENARIO_BYTES = 16 * 1024 * 1024L
        private val SHA = Regex("[a-f0-9]{64}")
    }
}
