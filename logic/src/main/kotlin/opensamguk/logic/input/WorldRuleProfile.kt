package opensamguk.logic.input

/** One-season SAMMO rollback fence for restored worlds with no stored profile. */
object WorldRuleProfile {
    const val ROLLBACK_ENV = "SAMMO_ROLLBACK_ENABLED"
    private val runtimeRollback: Boolean by lazy { rollbackEnabled(System.getenv(ROLLBACK_ENV)) }

    fun rollbackEnabled(raw: String? = System.getenv(ROLLBACK_ENV)): Boolean = when (raw) {
        null, "", "false" -> false
        "true" -> true
        else -> throw IllegalArgumentException("$ROLLBACK_ENV must be true or false")
    }

    /** Fresh scenario imports use HWIHA unless their JSON declares a profile. */
    fun defaultProfile(rollback: Boolean = false): RuleProfile =
        if (rollback) RuleProfile.SAMMO else RuleProfile.HWIHA

    /** An existing world without a profile is unreadable unless rollback is active. */
    fun resolve(config: Map<String, Any?>, rollback: Boolean = runtimeRollback): RuleProfile? {
        if ("ruleProfile" !in config) return if (rollback) RuleProfile.SAMMO else null
        val raw = config["ruleProfile"] as? String ?: return null
        return RuleProfile.entries.firstOrNull { it.name == raw }
    }

    fun require(config: Map<String, Any?>, rollback: Boolean = runtimeRollback): RuleProfile =
        requireNotNull(resolve(config, rollback)) { "invalid ruleProfile in world config" }

    /** Called by game-api during startup so an invalid env value fails before requests arrive. */
    fun validateRuntimeConfiguration() {
        runtimeRollback
    }
}
