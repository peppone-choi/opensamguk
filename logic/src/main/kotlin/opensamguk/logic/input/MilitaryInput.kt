package opensamguk.logic.input

/** Direct military actions use the acting general's saved position; targets and cost come from the server. */
data class MilitaryRequest(val actorId: Int, val inputId: String)

object MilitaryInput {
    const val CONSCRIPT = "action.conscript"
    const val RAISE_VOLUNTEERS = "action.raiseVolunteers"
    const val TRAIN = "action.train"
    const val BOOST_MORALE = "action.boostMorale"
    const val MUSTER = "action.muster"
    const val DEMOBILIZE = "action.demobilize"
    val INPUT_IDS = linkedSetOf(CONSCRIPT, RAISE_VOLUNTEERS, TRAIN, BOOST_MORALE, MUSTER, DEMOBILIZE)
    val CITY_INPUT_IDS = INPUT_IDS - MUSTER

    fun parse(actorId: Int, inputId: String, rawJson: String?): MilitaryRequest? {
        if (actorId <= 0 || inputId !in INPUT_IDS || rawJson == null) return null
        return try {
            if (FlatArguments(rawJson).read().isNotEmpty()) null else MilitaryRequest(actorId, inputId)
        } catch (_: IllegalArgumentException) { null }
    }

    fun canonicalJson(request: MilitaryRequest): String {
        require(request.actorId > 0 && request.inputId in INPUT_IDS)
        return "{}"
    }
}
