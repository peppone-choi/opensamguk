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

    /** Missing, retired, and unknown world formats are errors, including on API reads. */
    fun resolve(config: Map<String, Any?>, rollback: Boolean = runtimeRollback): RuleProfile? {
        require(!rollback) { "SAMMO rollback is retired" }
        opensamguk.logic.world.WorldFormat.require(config)
        return RuleProfile.HWIHA
    }

    fun require(config: Map<String, Any?>, rollback: Boolean = runtimeRollback): RuleProfile =
        checkNotNull(resolve(config, rollback))

    /** Called by game-api during startup so an invalid env value fails before requests arrive. */
    fun validateRuntimeConfiguration() {
        runtimeRollback
    }
}
