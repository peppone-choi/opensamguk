package opensamguk.engine.hwiha

/** A staged personal-turn outcome; only the common world/slot/result flush commits it. */
sealed interface HwihaTurnOutcome {
    val inputId: String
    data class Applied(override val inputId: String) : HwihaTurnOutcome
    data class Rejected(override val inputId: String, val code: String, val reason: String) : HwihaTurnOutcome
}
