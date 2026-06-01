package opensamguk.logic.inheritance

/**
 * The inheritance action (소비) API interface.
 * Each action represents a way to spend inheritance points.
 *
 * All actions are gated by isunited != 0 (disabled when 천하통일).
 * Join-time 본너스 APIs are NOT InheritActions — they are season-start benefits
 * that consume points directly from the previous balance.
 */
interface InheritAction {
    /** Registry key (e.g. "BuyHiddenBuff", "BuyRandomUnique") */
    val key: String

    /** The point cost for this action */
    val cost: Double

    /** Check if this action can be executed (sufficient points + not gated by united). */
    fun canExecute(userId: String, env: GameEnv): Boolean

    /** Execute the action. Returns the result with success/failure + consumed points. */
    fun execute(userId: String, args: Map<String, Any>): InheritActionResult
}

/**
 * Result of executing an [InheritAction].
 */
data class InheritActionResult(
    val success: Boolean,
    val consumedPoints: Double = 0.0,
    val message: String = "",
    /** Additional data returned by the action (e.g. purchased buff level, item key) */
    val data: Map<String, Any> = emptyMap(),
) {
    companion object {
        fun failure(message: String) = InheritActionResult(false, message = message)
        fun success(consumed: Double, message: String = "", data: Map<String, Any> = emptyMap()) =
            InheritActionResult(true, consumed, message, data)
    }
}

/**
 * Base class for point-consuming inheritance actions.
 * Provides the isunited gate and point-deduction logic.
 */
abstract class PointConsumingInheritAction(
    protected val pointManager: InheritancePointManager,
) : InheritAction {

    override fun canExecute(userId: String, env: GameEnv): Boolean {
        // isunited != 0 → ALL InheritActions are disabled
        if (pointManager.isGatedByUnited(env)) return false
        // Must have sufficient spendable points
        return pointManager.getSpendablePoints(userId) >= cost
    }

    /** Consume the point cost from the user's spendable balance. Returns true if successful. */
    protected fun consumePoints(userId: String): Boolean {
        return pointManager.tryConsumePoints(userId, cost)
    }
}
