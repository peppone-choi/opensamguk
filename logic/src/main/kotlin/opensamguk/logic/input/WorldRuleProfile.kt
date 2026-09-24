package opensamguk.logic.input

/** One-season SAMMO rollback fence. It changes only the default for a missing profile. */
object WorldRuleProfile {
    const val ROLLBACK_ENV = "SAMMO_ROLLBACK_ENABLED"

    fun rollbackEnabled(raw: String? = System.getenv(ROLLBACK_ENV)): Boolean = when (raw) {
        null, "", "false" -> false
        "true" -> true
        else -> throw IllegalArgumentException("$ROLLBACK_ENV must be true or false")
    }

    fun defaultProfile(rollback: Boolean = rollbackEnabled()): RuleProfile =
        if (rollback) RuleProfile.SAMMO else RuleProfile.HWIHA

    /** Missing key takes the product default; explicit null, unknown text and non-strings fail closed. */
    fun resolve(config: Map<String, Any?>, rollback: Boolean = rollbackEnabled()): RuleProfile? {
        if ("ruleProfile" !in config) return defaultProfile(rollback)
        val raw = config["ruleProfile"] as? String ?: return null
        return RuleProfile.entries.firstOrNull { it.name == raw }
    }

    fun require(config: Map<String, Any?>, rollback: Boolean = rollbackEnabled()): RuleProfile =
        requireNotNull(resolve(config, rollback)) { "invalid ruleProfile in world config" }
}
