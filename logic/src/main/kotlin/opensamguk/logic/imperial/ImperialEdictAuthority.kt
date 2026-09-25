package opensamguk.logic.imperial

/** Bridges the world court and physical regalia to the edict's seal stage. */
object ImperialEdictAuthority {
    fun sealWithRegalia(
        edict: ImperialEdict,
        world: ImperialWorldState,
        regalia: ImperialRegaliaState,
        artifactId: String,
        custodianGeneralId: Int,
        authorizedCustodianIds: Set<Int>,
    ): ImperialEdict {
        require(edict.stage == EdictStage.REGISTERED)
        val house = world.houses.single { it.code == edict.proposal.imperialLineCode }
        require(house.status == ImperialLineStatus.ACTIVE && house.holderGeneralId == edict.proposal.emperorId)
        val review = requireNotNull(edict.review)
        require(review.emperorActorId == house.holderGeneralId && review.emperorDecision != EmperorEdictDecision.REFUSE)
        require(edict.registrarId != null && edict.registerId != null)
        val courtCityId = requireNotNull(house.courtCityId) { "court has no physical seat" }
        val artifact = regalia.artifacts.single { it.id == artifactId }
        require(artifact.kind == RegaliaKind.STATE_REGALIA) { "office instrument cannot seal an imperial edict" }
        require(custodianGeneralId in authorizedCustodianIds) { "custodian lacks court authority" }
        require(artifact.custodianGeneralId == custodianGeneralId && artifact.cityId == courtCityId)
        require(artifact.authenticityClaims.any {
            it.assertedIdentity == artifact.claimedIdentity &&
                it.assessment == AuthenticityAssessment.CORROBORATED && it.evidenceIds.isNotEmpty()
        }) { "no corroborated seal identity" }
        return ImperialEdictPipeline.seal(edict, artifact.id, authorizedForEdicts = true)
    }

    /** Central office confirmation is evidence for a new claim, not a tenure-origin mutation. */
    fun centralConfirmationProof(
        edict: ImperialEdict,
        catalog: CentralOfficeCatalog,
        world: ImperialWorldState,
        courtPolityId: Int,
    ): CourtConfirmationProof? {
        require(courtPolityId > 0)
        val grant = ImperialEdictPipeline.acceptedCentralGrant(edict, catalog) ?: return null
        val house = world.houses.single { it.code == edict.proposal.imperialLineCode }
        require(house.courtNationId == courtPolityId) { "confirmation polity is not the issuing court" }
        return CourtConfirmationProof(edict.proposal.id, grant.officeId, grant.holderId, courtPolityId)
    }
}
