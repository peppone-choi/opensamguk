package opensamguk.logic.office

import opensamguk.logic.domestic.DomesticCounty
import opensamguk.logic.domestic.DomesticProjection
import opensamguk.logic.domestic.DomesticRules

data class OfficeReadView(
    val tenureId: String,
    val officeId: String,
    val name: String,
    val holderId: Int,
    val jurisdictionId: String,
    val nominal: Boolean,
    val actualCountyIds: List<Int>,
    val missing: List<OfficeEvidence>,
)

data class CountySeatOfficeView(
    val countyId: Int,
    val seatedPersonId: Int,
    val controllerId: Int,
    val title: String,
)

object OfficeProjections {
    /** County office is read from existing assignment/placement, never from OfficeTenureCodec. */
    fun countySeat(county: DomesticCounty, state: DomesticProjection): CountySeatOfficeView? {
        val seated = DomesticRules.seatedMagistrate(county, state) ?: return null
        return CountySeatOfficeView(county.id, seated.personId, seated.controllerId, "縣令 자리")
    }

    fun forJurisdiction(
        jurisdictionId: String,
        tenures: Collection<OfficeTenure>,
        catalog: OfficeCatalog,
        snapshot: OfficeJurisdictionSnapshot,
        rules: OfficeRules,
    ): List<OfficeReadView> {
        require(jurisdictionId == snapshot.jurisdictionId)
        return tenures.asSequence().filter { it.jurisdictionId == jurisdictionId && it.endedTurn == null }
            .sortedBy { it.id }
            .map { tenure ->
                val definition = requireNotNull(catalog.definition(tenure.officeId)) { "unknown office in tenure" }
                require(!definition.countyPlacementOnly) { "county office must be projected from placement" }
                val actual = OfficeCapabilityResolver.actualJurisdiction(tenure, snapshot, rules)
                OfficeReadView(tenure.id, tenure.officeId, definition.name, tenure.holderId,
                    jurisdictionId, !actual.effective, actual.countyIds, actual.missing)
            }.toList()
    }
}
