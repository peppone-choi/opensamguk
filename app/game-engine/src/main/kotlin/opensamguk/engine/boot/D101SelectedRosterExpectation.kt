package opensamguk.engine.boot

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import opensamguk.common.constants.GameConst
import opensamguk.infra.seed.ScenarioGeneral
import opensamguk.infra.seed.ScenarioJson

/** C4's importer-equivalent denominator from bytes supplied by a verified selected-source handle. */
class D101SelectedRosterExpectation {
    data class Counts(
        val scenarioRawSha256: String,
        val scenarioRawByteLength: Int,
        val effectiveResetExtend: Int,
        val selectedGeneralRows: Int,
        val activeGeneralRows: Int,
        val activeRetainerRows: Int,
    )

    fun calculate(
        originalBytes: ByteArray,
        expectedRawSha256: String,
        expectedRawByteLength: Long,
        effectiveResetExtend: Int,
    ): Counts {
        require(effectiveResetExtend == 0 || effectiveResetExtend == 1) { "effective RESET_EXTEND is required" }
        require(originalBytes.isNotEmpty()) { "selected scenario original bytes are unavailable" }
        require(expectedRawByteLength == originalBytes.size.toLong()) { "scenario raw byte length differs" }
        require(expectedRawSha256.matches(Regex("[0-9a-f]{64}"))) { "scenario raw SHA-256 is unavailable" }
        val actualSha = MessageDigest.getInstance("SHA-256").digest(originalBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actualSha == expectedRawSha256) { "scenario original bytes differ from selected-source receipt" }
        val rawText = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(originalBytes)).toString()
        val scenario = ScenarioJson.loadScenario(rawText)
        check(scenario.startYear == 190) { "selected scenario does not start in 190" }
        val selected = scenario.seedGenerals(effectiveResetExtend == 1)
        check(selected.map { it.name }.toSet().size == selected.size) { "selected roster names are not unique" }
        val activeNames = selected.filter { activeAtStart(it, scenario.startYear) }.map { it.name }.toSet()
        val activeRetainers = scenario.retainers.filter { it.general in activeNames }
        check(activeRetainers.all { it.master in activeNames }) { "active retainer has no active master" }
        check(activeRetainers.map { it.general }.toSet().size == activeRetainers.size) {
            "active retainer subjects are not unique"
        }
        return Counts(actualSha, originalBytes.size, effectiveResetExtend, selected.size,
            activeNames.size, activeRetainers.size)
    }

    fun requireDatabaseMatch(counts: Counts, snapshot: D101ProjectionSnapshotReader.Snapshot) {
        check(snapshot.generals.size == counts.activeGeneralRows) { "active DB general count differs from selected bytes" }
        check(snapshot.retainers.size == counts.activeRetainerRows) { "active DB retainer count differs from selected bytes" }
        check(snapshot.seedSettings["extendedGeneral"] == (counts.effectiveResetExtend == 1)) {
            "DB effective RESET_EXTEND differs from selected bytes"
        }
    }

    fun requireDatabaseMatch(counts: Counts, seed: D101ProjectionSnapshotReader.SeedMembership) {
        check(seed.generals.size == counts.activeGeneralRows) { "active seed DB general count differs from selected bytes" }
        check(seed.retainers.size == counts.activeRetainerRows) { "active seed DB retainer count differs from selected bytes" }
        check(seed.extendedGeneral == (counts.effectiveResetExtend == 1)) {
            "seed DB RESET_EXTEND differs from selected bytes"
        }
    }

    private fun activeAtStart(general: ScenarioGeneral, startYear: Int): Boolean {
        val death = general.deadYear ?: 300
        val appearance = general.appearanceYear
        return if (appearance != null) {
            appearance <= startYear && startYear <= death
        } else {
            val birth = general.bornYear ?: 180
            death > startYear && birth + GameConst.adultAge.toInt() <= startYear
        }
    }
}
