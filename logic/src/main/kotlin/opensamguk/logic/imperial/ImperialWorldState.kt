package opensamguk.logic.imperial

/**
 * Imperial state belongs to the world, never to a nation's ruler record.
 * A line may be vacant while its court and historical transitions remain.
 */
data class ImperialHouse(
    val code: String,
    val name: String,
    val status: ImperialLineStatus,
    val holderGeneralId: Int?,
    val designatedHeirGeneralId: Int?,
    val dynasticCandidateIds: List<Int>,
    val regentGeneralId: Int?,
    val courtNationId: Int?,
    val courtCityId: Int?,
    val legitimacy: Int,
) {
    init {
        require(code.matches(Regex("[a-z][a-z0-9_]{1,63}")) && name.isNotBlank())
        require(holderGeneralId == null || holderGeneralId > 0)
        require(designatedHeirGeneralId == null || designatedHeirGeneralId > 0)
        require(dynasticCandidateIds.all { it > 0 } && dynasticCandidateIds.distinct().size == dynasticCandidateIds.size)
        require(regentGeneralId == null || regentGeneralId > 0)
        require(courtNationId == null || courtNationId > 0)
        require(courtCityId == null || courtCityId > 0)
        require(legitimacy in 0..100)
        require((status == ImperialLineStatus.ACTIVE) == (holderGeneralId != null))
    }

    fun successionLine(): ImperialLineState =
        ImperialLineState(code, status, holderGeneralId, designatedHeirGeneralId, dynasticCandidateIds)
}

data class ImperialAllegiance(
    val lineCode: String,
    val nationId: Int,
    val relation: ImperialAllegianceRelation,
    val recognition: ImperialRecognition,
    val favor: Int,
) {
    init {
        require(lineCode.isNotBlank() && nationId > 0 && favor in -100..100)
    }
}

data class ImperialTransition(
    val requestId: String,
    val lineCode: String,
    val type: ImperialTransitionType,
    val fromHolderGeneralId: Int?,
    val toHolderGeneralId: Int?,
    val actorGeneralId: Int?,
    val year: Int,
    val month: Int,
    val reasonCode: String,
    val successionSource: ImperialSuccessionSource?,
) {
    init {
        require(requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(lineCode.isNotBlank() && reasonCode.isNotBlank())
        require(year > 0 && month in 1..12)
        require(fromHolderGeneralId == null || fromHolderGeneralId > 0)
        require(toHolderGeneralId == null || toHolderGeneralId > 0)
        require(actorGeneralId == null || actorGeneralId > 0)
    }
}

data class ImperialWorldState(
    val houses: List<ImperialHouse>,
    val allegiances: List<ImperialAllegiance>,
    val transitions: List<ImperialTransition>,
) {
    init {
        require(houses.map { it.code }.distinct().size == houses.size)
        require(houses.mapNotNull { it.holderGeneralId }.distinct().size == houses.mapNotNull { it.holderGeneralId }.size) {
            "one person cannot hold two imperial lines"
        }
        require(allegiances.map { it.lineCode to it.nationId }.distinct().size == allegiances.size)
        require(allegiances.all { allegiance -> houses.any { it.code == allegiance.lineCode } })
        require(transitions.map { it.requestId }.distinct().size == transitions.size)
        require(transitions.all { transition -> houses.any { it.code == transition.lineCode } })
    }

    /** Pure transition. The caller persists the returned state in one world-state flush. */
    fun succeedAfterDeath(
        lineCode: String,
        requestId: String,
        scriptedSuccessorGeneralId: Int?,
        candidates: Collection<ImperialCandidate>,
        year: Int,
        month: Int,
        reasonCode: String,
    ): ImperialWorldState {
        require(transitions.none { it.requestId == requestId }) { "duplicate imperial transition" }
        val house = houses.single { it.code == lineCode }
        require(house.status == ImperialLineStatus.ACTIVE)
        val decision = ImperialSuccession.chooseSuccessor(house.successionLine(), scriptedSuccessorGeneralId, candidates)
        require(decision.successorGeneralId == null ||
            houses.none { it.code != lineCode && it.holderGeneralId == decision.successorGeneralId })
        val next = house.copy(
            status = if (decision.successorGeneralId == null) ImperialLineStatus.VACANT else ImperialLineStatus.ACTIVE,
            holderGeneralId = decision.successorGeneralId,
        )
        val event = ImperialTransition(
            requestId, lineCode,
            if (decision.successorGeneralId == null) ImperialTransitionType.VACANCY else ImperialTransitionType.DEATH_SUCCESSION,
            house.holderGeneralId, decision.successorGeneralId, null, year, month, reasonCode, decision.source,
        )
        return copy(houses = houses.map { if (it.code == lineCode) next else it }, transitions = transitions + event)
    }

    /** Explicit political transition; national ruler succession is a separate operation. */
    fun changeSovereign(
        lineCode: String,
        requestId: String,
        type: ImperialTransitionType,
        toHolderGeneralId: Int?,
        actorGeneralId: Int?,
        year: Int,
        month: Int,
        reasonCode: String,
    ): ImperialWorldState {
        require(type != ImperialTransitionType.DEATH_SUCCESSION) { "death uses successor selection" }
        require(transitions.none { it.requestId == requestId }) { "duplicate imperial transition" }
        val house = houses.single { it.code == lineCode }
        val nextStatus = when (type) {
            ImperialTransitionType.ENTHRONEMENT -> {
                require(house.status == ImperialLineStatus.VACANT && toHolderGeneralId != null)
                ImperialLineStatus.ACTIVE
            }
            ImperialTransitionType.FOUNDATION -> {
                require(house.status != ImperialLineStatus.ACTIVE && toHolderGeneralId != null)
                ImperialLineStatus.ACTIVE
            }
            ImperialTransitionType.ABDICATION, ImperialTransitionType.DEPOSITION,
            ImperialTransitionType.USURPATION -> {
                require(house.status == ImperialLineStatus.ACTIVE)
                if (toHolderGeneralId == null) ImperialLineStatus.VACANT else ImperialLineStatus.ACTIVE
            }
            ImperialTransitionType.ASSASSINATION, ImperialTransitionType.VACANCY -> {
                require(house.status == ImperialLineStatus.ACTIVE && toHolderGeneralId == null)
                ImperialLineStatus.VACANT
            }
            ImperialTransitionType.EXTINCTION -> {
                require(house.status != ImperialLineStatus.ENDED && toHolderGeneralId == null)
                ImperialLineStatus.ENDED
            }
            ImperialTransitionType.DEATH_SUCCESSION -> error("unreachable")
        }
        require(toHolderGeneralId == null || toHolderGeneralId > 0)
        require(toHolderGeneralId == null || houses.none { it.code != lineCode && it.holderGeneralId == toHolderGeneralId })
        require(toHolderGeneralId == null || toHolderGeneralId != house.holderGeneralId)
        val next = house.copy(status = nextStatus, holderGeneralId = toHolderGeneralId)
        val event = ImperialTransition(requestId, lineCode, type, house.holderGeneralId,
            toHolderGeneralId, actorGeneralId, year, month, reasonCode, null)
        return copy(houses = houses.map { if (it.code == lineCode) next else it }, transitions = transitions + event)
    }
}
