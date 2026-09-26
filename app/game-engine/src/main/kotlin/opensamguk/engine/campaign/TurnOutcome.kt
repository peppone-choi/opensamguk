package opensamguk.engine.campaign

/** A staged personal-turn outcome; only the common world/slot/result flush commits it. */
sealed interface TurnOutcome {
    val inputId: String
    data object NoAction : TurnOutcome { override val inputId = "" }
    data class Applied(override val inputId: String, val effects: List<String> = emptyList()) : TurnOutcome
    data class Rejected(override val inputId: String, val code: String, val reason: String) : TurnOutcome
}
