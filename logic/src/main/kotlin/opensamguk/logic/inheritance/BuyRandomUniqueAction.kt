package opensamguk.logic.inheritance

import opensamguk.common.constants.GameConst

/**
 * BuyRandomUnique — 유니크 아이템 확정 드롭 플래그 구매.
 *
 * Costs [GameConst.inheritItemRandomPoint] points.
 * Sets the inheritRandomUnique flag in the user's meta.
 * When a unique item becomes available, the flag is consumed and the item is granted.
 * If no unique item is available, the points are refunded.
 *
 * @param pointManager The inheritance point manager for balance checks/consumption
 * @param onPurchase Callback invoked on successful purchase: (userId) -> Unit
 *                   Used to set the inheritRandomUnique flag
 * @param onRefund Callback invoked when refund is needed: (userId, amount) -> Unit
 *                 Used to refund points when no item is available
 */
class BuyRandomUniqueAction(
    pointManager: InheritancePointManager,
    private val onPurchase: (userId: String) -> Unit,
    private val onRefund: (userId: String, amount: Double) -> Unit = { userId, amount ->
        // Default refund: add back to previous balance
        pointManager.addPoint(userId, InheritanceKey.PREVIOUS, amount)
    },
) : PointConsumingInheritAction(pointManager) {

    override val key: String = NAME
    override val cost: Double = GameConst.inheritItemRandomPoint.toDouble()

    /**
     * Execute the random unique purchase.
     * @param args Optional: "checkOnly" to verify without purchasing
     * @return Success with consumed points and the flag set
     */
    override fun execute(userId: String, args: Map<String, Any>): InheritActionResult {
        if (!consumePoints(userId)) {
            return InheritActionResult.failure("Insufficient points: need $cost")
        }

        // Set the flag
        onPurchase(userId)

        return InheritActionResult.success(
            consumed = cost,
            message = "Purchased random unique drop flag for $cost points",
            data = mapOf("flag" to "inheritRandomUnique"),
        )
    }

    /**
     * Refund the purchase cost (called when no unique item is available for use).
     */
    fun refund(userId: String) {
        onRefund(userId, cost)
    }

    companion object {
        const val NAME = "BuyRandomUnique"
    }
}
