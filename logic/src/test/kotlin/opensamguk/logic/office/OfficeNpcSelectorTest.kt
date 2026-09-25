package opensamguk.logic.office

import kotlin.test.Test
import kotlin.test.assertEquals

class OfficeNpcSelectorTest {
    private val catalog = OfficeCatalog.loadClasspath()
    private val rules = OfficeRules(50, 1)
    private val first = OfficeVacancy(1, 7, "office.commandery-prefect",
        OfficeJurisdictionSnapshot("hhs-group:109:京兆尹", setOf(100, 101), 100, 100,
            setOf(100, 101), setOf(100), setOf(100), emptySet()))
    private val second = OfficeVacancy(1, 7, "office.commandery-prefect",
        OfficeJurisdictionSnapshot("hhs-group:110:扶風", setOf(200, 201), 200, 200,
            setOf(200, 201), setOf(200), setOf(200), emptySet()))

    @Test
    fun `fills each vacant jurisdiction by merit then aptitude with stable tie break`() {
        val candidates = listOf(
            OfficeNpcCandidate(3, 7, false, 10, 8),
            OfficeNpcCandidate(2, 7, false, 10, 9),
            OfficeNpcCandidate(4, 8, false, 99, 99),
        )
        val expected = listOf(2, 3)
        val choices = OfficeNpcSelector.choose(listOf(second, first), candidates.reversed(), emptyList(), emptySet(), catalog, rules)
        assertEquals(expected, choices.map { it.request.candidateId })
        assertEquals(listOf(10 to 9, 10 to 8), choices.map { it.candidateMerit to it.candidateAptitude })
        assertEquals(choices, OfficeNpcSelector.choose(listOf(first, second), candidates, emptyList(), emptySet(), catalog, rules))
    }

    @Test
    fun `does not propose an occupied office or a seat outside ownership`() {
        val occupied = OfficeTenure("existing", first.officeId, first.jurisdiction.jurisdictionId, 9, 1, 7,
            OfficeClaimOrigin.POLITY_APPOINTMENT, 1, acceptedTurn = 2)
        val unowned = second.copy(jurisdiction = second.jurisdiction.copy(ownedCountyIds = setOf(201)))
        val choices = OfficeNpcSelector.choose(listOf(first, unowned), listOf(OfficeNpcCandidate(2, 7, true, 20, 20)),
            listOf(occupied), emptySet(), catalog, rules)
        assertEquals(emptyList(), choices)
    }
}
