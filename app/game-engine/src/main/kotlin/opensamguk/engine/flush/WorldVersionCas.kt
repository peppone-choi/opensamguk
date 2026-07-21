package opensamguk.engine.flush

/**
 * OPENSAM-131 — result of the terminal world_state CAS inside a flush transaction.
 */
sealed class WorldVersionCasResult {
    data class Applied(val newVersion: Long) : WorldVersionCasResult()
    data object StaleWriter : WorldVersionCasResult()
}
