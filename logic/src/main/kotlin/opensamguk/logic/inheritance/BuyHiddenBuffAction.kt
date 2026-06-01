package opensamguk.logic.inheritance

import opensamguk.common.constants.GameConst
import opensamguk.logic.util.clamp

/**
 * BuyHiddenBuff — 히든 버프 레벨 구매 (1~5).
 *
 * Costs [GameConst.inheritBuffPoints][level] points.
 * The purchased buff level is stored in aux.inheritBuff.
 * Level is clamped to 1..5.
 *
 * @param pointManager The inheritance point manager for balance checks/consumption
 * @param onPurchase Callback invoked on successful purchase: (userId, buffKey, level) -> Unit
 *                   Used to persist the buff update (e.g. update aux.inheritBuff in DB)
 */
class BuyHiddenBuffAction(
    pointManager: InheritancePointManager,
    private val onPurchase: (userId: String, buffKey: String, level: Int) -> Unit,
) : PointConsumingInheritAction(pointManager) {

    override val key: String = NAME

    /** The cost is determined at execution time based on the requested level. */
    override val cost: Double = 0.0 // dynamic — determined per-request

    override fun canExecute(userId: String, env: GameEnv): Boolean {
        if (pointManager.isGatedByUnited(env)) return false
        // Cost check is done at execute time since it depends on level
        return true
    }

    /**
     * Execute the buff purchase.
     * @param args Must contain:
     *   - "buffKey": String — one of the 8 InheritBuff field names
     *   - "level": Int — desired level 1..5
     * @return Success with consumed points, or failure with reason
     */
    override fun execute(userId: String, args: Map<String, Any>): InheritActionResult {
        val buffKey = args["buffKey"] as? String
            ?: return InheritActionResult.failure("buffKey is required")
        val requestedLevel = (args["level"] as? Number)?.toInt()
            ?: return InheritActionResult.failure("level is required")

        // Validate buffKey
        if (buffKey !in VALID_BUFF_KEYS) {
            return InheritActionResult.failure("Invalid buffKey: $buffKey")
        }

        // Clamp level to 1..5
        val level = clamp(requestedLevel.toDouble(), 1.0, 5.0).toInt()

        // Get cost from GameConst
        val levelCost = getLevelCost(level)
        if (levelCost <= 0) {
            return InheritActionResult.failure("Invalid buff level: $level")
        }

        // Check balance
        val currentPoints = pointManager.getSpendablePoints(userId)
        if (currentPoints < levelCost) {
            return InheritActionResult.failure(
                "Insufficient points: need $levelCost, have $currentPoints"
            )
        }

        // Consume points
        if (!pointManager.tryConsumePoints(userId, levelCost)) {
            return InheritActionResult.failure("Point consumption failed")
        }

        // Apply the buff purchase
        onPurchase(userId, buffKey, level)

        return InheritActionResult.success(
            consumed = levelCost,
            message = "Purchased $buffKey level $level for $levelCost points",
            data = mapOf("buffKey" to buffKey, "level" to level),
        )
    }

    private fun getLevelCost(level: Int): Double {
        return when (level) {
            1 -> GameConst.inheritBuffPoints.getOrElse(1) { 200 }.toDouble()
            2 -> GameConst.inheritBuffPoints.getOrElse(2) { 600 }.toDouble()
            3 -> GameConst.inheritBuffPoints.getOrElse(3) { 1200 }.toDouble()
            4 -> GameConst.inheritBuffPoints.getOrElse(4) { 2000 }.toDouble()
            5 -> GameConst.inheritBuffPoints.getOrElse(5) { 3000 }.toDouble()
            else -> 0.0
        }
    }

    companion object {
        const val NAME = "BuyHiddenBuff"

        /** The 8 valid buff field names matching [InheritBuff] properties. */
        val VALID_BUFF_KEYS: Set<String> = setOf(
            "warAvoidRatio",
            "warCriticalRatio",
            "warMagicTrialProb",
            "success",
            "fail",
            "warAvoidRatioOppose",
            "warCriticalRatioOppose",
            "warMagicTrialProbOppose",
        )
    }
}
