package opensamguk.logic.office

/** The game supplies its existing merit and aptitude measures; selection adds no hidden weights. */
data class OfficeNpcCandidate(
    val generalId: Int,
    val nationId: Int,
    val isHuman: Boolean,
    val merit: Int,
    val aptitude: Int,
) {
    init { require(generalId > 0 && nationId >= 0 && merit >= 0 && aptitude >= 0) }
}

data class OfficeVacancy(
    val issuerId: Int,
    val issuerNationId: Int,
    val officeId: String,
    val jurisdiction: OfficeJurisdictionSnapshot,
) {
    init { require(issuerId > 0 && issuerNationId > 0 && officeId.isNotBlank()) }
}

data class OfficeNpcChoice(
    val request: OfficeAppointmentRequest,
    val candidateMerit: Int,
    val candidateAptitude: Int,
    val candidateIsHuman: Boolean,
)

/** Deterministic NPC proposal. Execution must reassess against its fresh authoritative state. */
object OfficeNpcSelector {
    fun choose(
        vacancies: Collection<OfficeVacancy>,
        candidates: Collection<OfficeNpcCandidate>,
        activeTenures: Collection<OfficeTenure>,
        centralOfficeIds: Set<String>,
        catalog: OfficeCatalog,
        rules: OfficeRules,
    ): List<OfficeNpcChoice> {
        require(vacancies.map { it.officeId to it.jurisdiction.jurisdictionId }.distinct().size == vacancies.size)
        require(candidates.map { it.generalId }.distinct().size == candidates.size)
        val chosenCounts = mutableMapOf<Int, Int>()
        val filledScopes = mutableSetOf<Pair<String, OfficeJurisdiction>>()
        return vacancies.sortedWith(compareBy({ it.jurisdiction.jurisdictionId }, { it.officeId })).mapNotNull { vacancy ->
            val scope = vacancy.jurisdiction.jurisdictionId to (catalog.definition(vacancy.officeId)?.jurisdiction ?: return@mapNotNull null)
            if (scope in filledScopes) return@mapNotNull null
            candidates.asSequence()
                .sortedWith(compareByDescending<OfficeNpcCandidate> { it.merit }
                    .thenByDescending { it.aptitude }.thenBy { it.generalId })
                .firstOrNull { candidate ->
                    val alreadyHeld = activeTenures.count { it.isActive && it.holderId == candidate.generalId }
                    if (alreadyHeld + chosenCounts.getOrDefault(candidate.generalId, 0) >= rules.maximumConcurrentLocalTenures) {
                        false
                    } else {
                        val request = vacancy.request(candidate.generalId)
                        OfficeAppointmentRules.assess(
                            request,
                            OfficeAppointmentContext(
                                vacancy.issuerId, vacancy.issuerNationId, true,
                                candidate.generalId, candidate.nationId, candidate.isHuman,
                                vacancy.jurisdiction, activeTenures.toList(), centralOfficeIds,
                            ),
                            catalog, rules,
                        ) is OfficeAppointmentAssessment.Allowed
                    }
                }?.let { candidate ->
                    chosenCounts[candidate.generalId] = chosenCounts.getOrDefault(candidate.generalId, 0) + 1
                    filledScopes += scope
                    OfficeNpcChoice(vacancy.request(candidate.generalId), candidate.merit, candidate.aptitude, candidate.isHuman)
                }
        }
    }

    private fun OfficeVacancy.request(candidateId: Int) = OfficeAppointmentRequest(
        issuerId, candidateId, officeId, jurisdiction.jurisdictionId, jurisdiction.seatCountyId,
    )
}
