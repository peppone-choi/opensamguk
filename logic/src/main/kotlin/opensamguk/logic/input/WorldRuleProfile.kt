package opensamguk.logic.input

/** Adapter for input handlers while their profile parameter is retired. */
object WorldRuleProfile {
    const val ROLLBACK_ENV = "SAMMO_ROLLBACK_ENABLED"
    private val runtimeRollback: Boolean by lazy { rollbackEnabled(System.getenv(ROLLBACK_ENV)) }

    fun rollbackEnabled(raw: String? = System.getenv(ROLLBACK_ENV)): Boolean = when (raw) {
        null, "", "false" -> false
        else -> throw IllegalArgumentException("$ROLLBACK_ENV is retired; restored worlds must pass worldFormat validation")
    }

    fun defaultProfile(rollback: Boolean = false): RuleProfile {
        require(!rollback) { "SAMMO rollback is retired" }
        return RuleProfile.HWIHA
    }

    /** API production reads pass WorldStateReadRepository's full format validation first.
     * Direct test doubles retain the retired profile projection until those fixtures migrate. */
    fun resolve(config: Map<String, Any?>, rollback: Boolean = runtimeRollback): RuleProfile? {
        require(!rollback) { "SAMMO rollback is retired" }
        val marker = config[opensamguk.logic.world.WorldFormat.CONFIG_KEY]
        if (marker != null) {
            opensamguk.logic.world.WorldFormat.require(config)
            return RuleProfile.HWIHA
        }
        val legacy = config["ruleProfile"] as? String ?: return null
        return RuleProfile.entries.firstOrNull { it.name == legacy }
    }

    fun require(config: Map<String, Any?>, rollback: Boolean = runtimeRollback): RuleProfile =
        requireNotNull(resolve(config, rollback)) { "world profile is missing" }

    /** Called by game-api during startup so an invalid env value fails before requests arrive. */
    fun validateRuntimeConfiguration() {
        runtimeRollback
    }
}
