package opensamguk.logic.input

/** Explicit event-owned lord status; never inferred from office, troops, or retinue size. */
data class EnlistmentGeneral(val id: Int, val nationId: Int, val isLord: Boolean, val isHuman: Boolean)
data class EnlistmentBond(val masterId: Int, val generalId: Int)

/** Three retained direct actions, sharing one transition contract. */
enum class EnlistmentMode { NATION, RANDOM, GENERAL }
data class EnlistmentRequest(val actorId: Int, val mode: EnlistmentMode, val targetId: Int? = null)

data class EnlistmentSnapshot(
    val profile: RuleProfile,
    val generals: List<EnlistmentGeneral>,
    val bonds: List<EnlistmentBond>,
    /** Explicit sovereign event/seed state, not an officer-level heuristic. */
    val sovereignByNation: Map<Int, Int>,
    val acceptingLordIds: Set<Int>,
    val freeRenownByLord: Map<Int, Int>,
    /** Price of the actor's card only; their personal retinue stays on their own budget. */
    val actorCardCost: Int,
    /** Includes unlinked recruited cards: storage requires unique names per master. */
    val nameConflictingLordIds: Set<Int> = emptySet(),
)

enum class EnlistmentFailure {
    WRONG_RULE_PROFILE, INVALID_REQUEST, ACTOR_NOT_FOUND, ALREADY_SERVING,
    ALREADY_BOUND, INVALID_RETINUE, HUMAN_RETAINER_REQUIRES_LORD,
    TARGET_NOT_FOUND, TARGET_NOT_LORD, TARGET_NOT_ACCEPTING, INSUFFICIENT_RENOWN,
    NO_ELIGIBLE_NATION, DUPLICATE_RETAINER_NAME,
}

/** This is an intent, not a committed result. It contains no teleport or asset transfer. */
data class EnlistmentPlan(
    val actorId: Int,
    val masterId: Int,
    val nationId: Int,
    val joiningGeneralIds: List<Int>,
    val relinquishLordStatus: Boolean,
    val masterRenownCost: Int,
)

sealed interface EnlistmentAssessment {
    data class Eligible(val choices: List<EnlistmentPlan>) : EnlistmentAssessment
    data class Rejected(val reason: EnlistmentFailure) : EnlistmentAssessment
}

/** Pure shared precheck/execution rules. Execution must supply a fresh complete snapshot. */
object HwihaEnlistmentRules {
    fun assess(request: EnlistmentRequest, state: EnlistmentSnapshot): EnlistmentAssessment {
        fun deny(reason: EnlistmentFailure) = EnlistmentAssessment.Rejected(reason)
        if (state.profile != RuleProfile.HWIHA) return deny(EnlistmentFailure.WRONG_RULE_PROFILE)
        if (request.actorId <= 0 || (request.mode == EnlistmentMode.RANDOM) != (request.targetId == null) ||
            request.targetId?.let { it <= 0 } == true
        ) return deny(EnlistmentFailure.INVALID_REQUEST)
        require(state.actorCardCost > 0 && state.freeRenownByLord.values.all { it >= 0 }) {
            "explicit nonnegative renown budget and positive card cost required"
        }
        val generals = state.generals.associateBy { it.id }
        require(generals.size == state.generals.size && generals.values.all { it.id > 0 && it.nationId >= 0 }) {
            "unique positive general ids and nonnegative nation ids required"
        }
        val actor = generals[request.actorId] ?: return deny(EnlistmentFailure.ACTOR_NOT_FOUND)
        if (actor.nationId != 0) return deny(EnlistmentFailure.ALREADY_SERVING)
        val parent = state.bonds.associate { it.generalId to it.masterId }
        if (parent.size != state.bonds.size || state.bonds.any {
                it.generalId == it.masterId || it.generalId !in generals || it.masterId !in generals ||
                    generals.getValue(it.generalId).nationId != generals.getValue(it.masterId).nationId ||
                    (it.masterId != actor.id && !generals.getValue(it.masterId).isLord &&
                        generals.getValue(it.generalId).isHuman)
            }
        ) return deny(EnlistmentFailure.INVALID_RETINUE)
        if (actor.id in parent) return deny(EnlistmentFailure.ALREADY_BOUND)
        // Check the entire supplied forest before using it for selection or a transition.
        for (id in generals.keys) {
            val seen = mutableSetOf<Int>()
            var cursor: Int? = id
            while (cursor != null) {
                if (!seen.add(cursor)) return deny(EnlistmentFailure.INVALID_RETINUE)
                cursor = parent[cursor]
            }
        }
        val children = state.bonds.groupBy({ it.masterId }, { it.generalId })
        val joining = sortedSetOf<Int>()
        val pending = ArrayDeque<Int>().apply { add(actor.id) }
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            joining.add(id)
            pending.addAll(children[id].orEmpty())
        }
        if (joining.any { generals.getValue(it).nationId != actor.nationId }) {
            return deny(EnlistmentFailure.INVALID_RETINUE)
        }
        // After enlistment the actor is no longer a lord. Do not silently reparent people.
        if (children[actor.id].orEmpty().any { generals.getValue(it).isHuman }) {
            return deny(EnlistmentFailure.HUMAN_RETAINER_REQUIRES_LORD)
        }
        fun evaluate(masterId: Int): EnlistmentAssessment {
            val master = generals[masterId] ?: return deny(EnlistmentFailure.TARGET_NOT_FOUND)
            if (!master.isLord || master.nationId <= 0 || master.id in joining) {
                return deny(EnlistmentFailure.TARGET_NOT_LORD)
            }
            if (master.id !in state.acceptingLordIds) return deny(EnlistmentFailure.TARGET_NOT_ACCEPTING)
            if (master.id in state.nameConflictingLordIds) return deny(EnlistmentFailure.DUPLICATE_RETAINER_NAME)
            val budget = state.freeRenownByLord[master.id]
                ?: return deny(EnlistmentFailure.INSUFFICIENT_RENOWN)
            if (budget < state.actorCardCost) return deny(EnlistmentFailure.INSUFFICIENT_RENOWN)
            return EnlistmentAssessment.Eligible(listOf(EnlistmentPlan(
                actor.id, master.id, master.nationId, joining.toList(), actor.isLord, state.actorCardCost,
            )))
        }
        fun nation(nationId: Int): EnlistmentAssessment {
            val masterId = state.sovereignByNation[nationId]
                ?: return deny(EnlistmentFailure.TARGET_NOT_FOUND)
            if (generals[masterId]?.nationId != nationId) return deny(EnlistmentFailure.TARGET_NOT_LORD)
            return evaluate(masterId)
        }
        return when (request.mode) {
            EnlistmentMode.NATION -> nation(request.targetId!!)
            EnlistmentMode.GENERAL -> {
                var target = generals[request.targetId] ?: return deny(EnlistmentFailure.TARGET_NOT_FOUND)
                // Following a general means entering that general's nearest explicit lord's retinue.
                while (!target.isLord) {
                    val masterId = parent[target.id] ?: return deny(EnlistmentFailure.TARGET_NOT_LORD)
                    target = generals.getValue(masterId)
                }
                evaluate(target.id)
            }
            EnlistmentMode.RANDOM -> {
                val choices = state.sovereignByNation.keys.sorted().flatMap {
                    (nation(it) as? EnlistmentAssessment.Eligible)?.choices.orEmpty()
                }
                if (choices.isEmpty()) deny(EnlistmentFailure.NO_ELIGIBLE_NATION)
                else EnlistmentAssessment.Eligible(choices)
            }
        }
    }

    /** Only RANDOM draws. The engine provides its scoped deterministic RNG after rechecking. */
    fun select(assessment: EnlistmentAssessment.Eligible, drawIndex: (Int) -> Int): EnlistmentPlan {
        require(assessment.choices.isNotEmpty())
        if (assessment.choices.size == 1) return assessment.choices.single()
        val index = drawIndex(assessment.choices.size)
        require(index in assessment.choices.indices) { "random choice out of bounds" }
        return assessment.choices[index]
    }
}
