package opensamguk.logic.inheritance

/**
 * Registry for all [InheritAction] instances.
 * Provides lookup by key and listing of available actions (respecting isunited gating).
 *
 * The registry is initialized with all available actions and provides
 * the routing layer for the InheritAction API.
 */
class InheritActionRegistry(
    actions: List<InheritAction>,
) {
    private val actionMap: Map<String, InheritAction> = actions.associateBy { it.key }

    /** Get an action by its registry key. Returns null if not found. */
    fun getAction(key: String): InheritAction? = actionMap[key]

    /** List all registered action keys. */
    fun listActionKeys(): List<String> = actionMap.keys.toList()

    /** List actions that can execute for the given user in the given environment. */
    fun listAvailableActions(userId: String, env: GameEnv): List<InheritAction> {
        return actionMap.values.filter { it.canExecute(userId, env) }
    }

    /** Check if any action is available (used by UI to show/hide the inheritance menu). */
    fun hasAvailableActions(userId: String, env: GameEnv): Boolean {
        return actionMap.values.any { it.canExecute(userId, env) }
    }

    companion object {
        /**
         * Create the standard registry with all P6 InheritActions.
         *
         * @param pointManager The shared point manager
         * @param onBuffPurchase Callback for buff purchase persistence
         * @param onRandomUniquePurchase Callback for random unique flag persistence
         */
        fun createStandardRegistry(
            pointManager: InheritancePointManager,
            onBuffPurchase: (userId: String, buffKey: String, level: Int) -> Unit = { _, _, _ -> },
            onRandomUniquePurchase: (userId: String) -> Unit = { _ -> },
        ): InheritActionRegistry {
            val actions = listOf(
                BuyHiddenBuffAction(pointManager, onBuffPurchase),
                BuyRandomUniqueAction(pointManager, onRandomUniquePurchase),
            )
            return InheritActionRegistry(actions)
        }
    }
}
