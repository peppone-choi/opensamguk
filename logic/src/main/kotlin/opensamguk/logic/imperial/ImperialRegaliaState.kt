package opensamguk.logic.imperial

enum class RegaliaKind {
    STATE_REGALIA,
    OFFICE_INSTRUMENT,
}

enum class AuthenticityAssessment {
    CLAIMED,
    CORROBORATED,
    DISPUTED,
    REJECTED,
}

data class AuthenticityClaim(
    val id: String,
    val assertedIdentity: String,
    val assertedByGeneralId: Int,
    val assessment: AuthenticityAssessment,
    val evidenceIds: List<String>,
) {
    init {
        require(id.isNotBlank() && assertedIdentity.isNotBlank() && assertedByGeneralId > 0)
        require(evidenceIds.all(String::isNotBlank) && evidenceIds.distinct().size == evidenceIds.size)
    }
}

data class OfficeInstrumentScope(
    val credentialId: String,
    val tenureId: String,
    val jurisdictionId: String,
    val validFromTurn: Long,
    val expiresAtTurn: Long?,
) {
    init {
        require(credentialId.isNotBlank() && tenureId.isNotBlank() && jurisdictionId.isNotBlank())
        require(validFromTurn >= 0 && (expiresAtTurn == null || expiresAtTurn >= validFromTurn))
    }

    fun isValidFor(tenureId: String, jurisdictionId: String, turn: Long): Boolean =
        this.tenureId == tenureId && this.jurisdictionId == jurisdictionId &&
            turn >= validFromTurn && (expiresAtTurn == null || turn <= expiresAtTurn)
}

enum class RegaliaCustodyEventType {
    FOUND,
    TRANSFERRED,
    SEIZED,
    LOST,
    DESTROYED,
}

data class RegaliaCustodyEvent(
    val requestId: String,
    val type: RegaliaCustodyEventType,
    val actorGeneralId: Int,
    val fromCustodianGeneralId: Int?,
    val toCustodianGeneralId: Int?,
    val fromCityId: Int?,
    val toCityId: Int?,
    val turn: Long,
) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(actorGeneralId > 0 && turn >= 0)
        require(fromCustodianGeneralId == null || fromCustodianGeneralId > 0)
        require(toCustodianGeneralId == null || toCustodianGeneralId > 0)
        require(fromCityId == null || fromCityId > 0)
        require(toCityId == null || toCityId > 0)
    }
}

data class RegaliaArtifact(
    val id: String,
    val kind: RegaliaKind,
    val claimedIdentity: String,
    val ownerGeneralId: Int?,
    val ownerNationId: Int?,
    val custodianGeneralId: Int?,
    val cityId: Int?,
    val authenticityClaims: List<AuthenticityClaim>,
    val officeScope: OfficeInstrumentScope?,
    val custodyHistory: List<RegaliaCustodyEvent>,
) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_:-]{1,95}")) && claimedIdentity.isNotBlank())
        require(ownerGeneralId == null || ownerGeneralId > 0)
        require(ownerNationId == null || ownerNationId > 0)
        require(ownerGeneralId == null || ownerNationId == null) { "one owner identity per artifact" }
        require(custodianGeneralId == null || custodianGeneralId > 0)
        require(cityId == null || cityId > 0)
        require(authenticityClaims.map { it.id }.distinct().size == authenticityClaims.size)
        require(kind == RegaliaKind.OFFICE_INSTRUMENT || officeScope == null)
        require(custodyHistory.map { it.requestId }.distinct().size == custodyHistory.size)
        require(custodyHistory.zipWithNext().all { (before, after) -> before.turn <= after.turn })
        require(custodyHistory.lastOrNull()?.let {
            it.toCustodianGeneralId == custodianGeneralId && it.toCityId == cityId
        } ?: true) { "custody history disagrees with current location" }
    }
}

data class ImperialRegaliaState(val artifacts: List<RegaliaArtifact>) {
    init {
        require(artifacts.map { it.id }.distinct().size == artifacts.size) {
            "one physical artifact per id in a world"
        }
        require(artifacts.flatMap { it.custodyHistory }.map { it.requestId }.distinct().size ==
            artifacts.sumOf { it.custodyHistory.size })
    }

    /**
     * Custody and authenticity are independent. A seizure never manufactures a
     * corroborated seal, an imperial title, or an office credential.
     */
    fun transferCustody(
        artifactId: String,
        requestId: String,
        type: RegaliaCustodyEventType,
        actorGeneralId: Int,
        expectedCustodianGeneralId: Int?,
        toCustodianGeneralId: Int?,
        toCityId: Int?,
        turn: Long,
    ): ImperialRegaliaState {
        require(type == RegaliaCustodyEventType.TRANSFERRED || type == RegaliaCustodyEventType.SEIZED ||
            type == RegaliaCustodyEventType.LOST)
        require(artifacts.none { artifact -> artifact.custodyHistory.any { it.requestId == requestId } }) {
            "duplicate regalia event"
        }
        val artifact = artifacts.single { it.id == artifactId }
        require(artifact.custodianGeneralId == expectedCustodianGeneralId) { "stale custodian" }
        val event = RegaliaCustodyEvent(requestId, type, actorGeneralId,
            artifact.custodianGeneralId, toCustodianGeneralId, artifact.cityId, toCityId, turn)
        val next = artifact.copy(custodianGeneralId = toCustodianGeneralId, cityId = toCityId,
            custodyHistory = artifact.custodyHistory + event)
        return copy(artifacts = artifacts.map { if (it.id == artifactId) next else it })
    }
}
