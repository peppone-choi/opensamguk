package opensamguk.logic.imperial

import kotlin.test.Test
import kotlin.test.assertEquals

class ImperialSuccessionTest {
    private fun line(
        designatedHeir: Int? = 2,
        dynasticCandidates: List<Int> = listOf(2, 3, 4),
        status: ImperialLineStatus = ImperialLineStatus.ACTIVE,
    ) = ImperialLineState(
        code = "later_han",
        status = status,
        holderGeneralId = 1,
        designatedHeirGeneralId = designatedHeir,
        dynasticCandidateIds = dynasticCandidates,
    )

    private fun candidate(id: Int, living: Boolean = true, eligible: Boolean = true) =
        ImperialCandidate(id, living, eligible)

    @Test
    fun `scripted successor has precedence over designated and dynastic heirs`() {
        val result = ImperialSuccession.chooseSuccessor(
            line = line(),
            scriptedSuccessorGeneralId = 4,
            candidates = listOf(candidate(2), candidate(3), candidate(4)),
        )

        assertEquals(ImperialSuccessionDecision(4, ImperialSuccessionSource.SCRIPTED), result)
    }

    @Test
    fun `designated living heir is used when there is no scripted successor`() {
        val result = ImperialSuccession.chooseSuccessor(
            line = line(),
            scriptedSuccessorGeneralId = null,
            candidates = listOf(candidate(2), candidate(3)),
        )

        assertEquals(ImperialSuccessionDecision(2, ImperialSuccessionSource.DESIGNATED), result)
    }

    @Test
    fun `dead or ineligible candidates are skipped in dynastic order`() {
        val result = ImperialSuccession.chooseSuccessor(
            line = line(designatedHeir = 2),
            scriptedSuccessorGeneralId = 5,
            candidates = listOf(
                candidate(2, living = false),
                candidate(3, eligible = false),
                candidate(4),
                candidate(5, living = false),
            ),
        )

        assertEquals(ImperialSuccessionDecision(4, ImperialSuccessionSource.DYNASTIC), result)
    }

    @Test
    fun `no living candidate leaves the imperial line vacant`() {
        val result = ImperialSuccession.chooseSuccessor(
            line = line(),
            scriptedSuccessorGeneralId = null,
            candidates = listOf(candidate(2, living = false), candidate(3, living = false)),
        )

        assertEquals(ImperialSuccessionDecision(null, ImperialSuccessionSource.VACANCY), result)
    }

    @Test
    fun `ended line cannot revive through ordinary succession`() {
        val result = ImperialSuccession.chooseSuccessor(
            line = line(status = ImperialLineStatus.ENDED),
            scriptedSuccessorGeneralId = 4,
            candidates = listOf(candidate(4)),
        )

        assertEquals(ImperialSuccessionDecision(null, ImperialSuccessionSource.VACANCY), result)
    }
}
