package opensamguk.logic.input

/** Adapter for input handlers while their profile parameter is retired. */
object WorldRuleProfile {
    fun defaultProfile(): RuleProfile = RuleProfile.HWIHA

    /** API production reads pass WorldStateReadRepository's full format validation first.
     * Direct test doubles retain the retired profile projection until those fixtures migrate. */
    fun resolve(config: Map<String, Any?>): RuleProfile? {
        val marker = config[opensamguk.logic.world.WorldFormat.CONFIG_KEY]
        if (marker != null) {
            opensamguk.logic.world.WorldFormat.require(config)
            return RuleProfile.HWIHA
        }
        val legacy = config["ruleProfile"] as? String ?: return null
        return RuleProfile.entries.firstOrNull { it.name == legacy }
    }

    fun require(config: Map<String, Any?>): RuleProfile =
        requireNotNull(resolve(config)) { "world profile is missing" }
}
