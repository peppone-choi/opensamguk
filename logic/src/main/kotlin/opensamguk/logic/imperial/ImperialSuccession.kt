package opensamguk.logic.imperial

/** Current lifecycle of one imperial line inside a world. */
enum class ImperialLineStatus {
    ACTIVE,
    VACANT,
    ENDED,
}

/** Why a successor was selected. The value is persisted in transition metadata. */
enum class ImperialSuccessionSource {
    SCRIPTED,
    DESIGNATED,
    DYNASTIC,
    VACANCY,
}

enum class ImperialTransitionType {
    ENTHRONEMENT,
    DEATH_SUCCESSION,
    ABDICATION,
    DEPOSITION,
    FOUNDATION,
    VACANCY,
    EXTINCTION,
}

/** A nation's political position toward one imperial line, separate from ordinary diplomacy. */
enum class ImperialAllegianceRelation {
    COURT_GUARDIAN,
    LOYAL,
    INVESTED,
    TRIBUTARY,
    NEUTRAL,
    REJECTED,
    HOSTILE,
    PRETENDER,
}

enum class ImperialRecognition {
    RECOGNIZED,
    CONTESTED,
    REJECTED,
}

class ImperialLineState(
    val code: String,
    val status: ImperialLineStatus,
    val holderGeneralId: Int?,
    val designatedHeirGeneralId: Int?,
    dynasticCandidateIds: List<Int>,
) {
    val dynasticCandidateIds: List<Int> = dynasticCandidateIds.toList()
}

data class ImperialCandidate(
    val generalId: Int,
    val living: Boolean,
    val eligible: Boolean = true,
)

data class ImperialSuccessionDecision(
    val successorGeneralId: Int?,
    val source: ImperialSuccessionSource,
)

/**
 * Draw-free imperial successor selection.
 *
 * Historical scripts are authoritative, then the named heir, then the line's declared dynastic
 * order. This intentionally does not use ability, dedication, affinity, officer level, or RNG: those
 * belong to the separate nation-ruler succession system.
 */
object ImperialSuccession {
    fun chooseSuccessor(
        line: ImperialLineState,
        scriptedSuccessorGeneralId: Int?,
        candidates: Collection<ImperialCandidate>,
    ): ImperialSuccessionDecision {
        if (line.status == ImperialLineStatus.ENDED) {
            return ImperialSuccessionDecision(null, ImperialSuccessionSource.VACANCY)
        }

        val eligibleById = candidates
            .asSequence()
            .filter { it.living && it.eligible && it.generalId != line.holderGeneralId }
            .associateBy { it.generalId }

        if (scriptedSuccessorGeneralId != null && scriptedSuccessorGeneralId in eligibleById) {
            return ImperialSuccessionDecision(scriptedSuccessorGeneralId, ImperialSuccessionSource.SCRIPTED)
        }

        val designated = line.designatedHeirGeneralId
        if (designated != null && designated in eligibleById) {
            return ImperialSuccessionDecision(designated, ImperialSuccessionSource.DESIGNATED)
        }

        val dynastic = line.dynasticCandidateIds.firstOrNull { it in eligibleById }
        if (dynastic != null) {
            return ImperialSuccessionDecision(dynastic, ImperialSuccessionSource.DYNASTIC)
        }

        return ImperialSuccessionDecision(null, ImperialSuccessionSource.VACANCY)
    }
}
