package opensamguk.logic.inheritance

import opensamguk.common.constants.GameConst
import opensamguk.logic.util.clamp

/**
 * BuyHiddenBuff — 히든 버프 레벨 구매 (1~5).
 *
 * Costs the CUMULATIVE DIFFERENCE `inheritBuffPoints[level] - inheritBuffPoints[prevLevel]` (PHP
 * `BuyHiddenBuff.php:68`) — upgrading L1→L2 costs 600-200 = 400, not the absolute 600.
 * [GameConst.inheritBuffPoints] = `[0, 200, 600, 1200, 2000, 3000]` is indexed DIRECTLY by level.
 * The purchased buff level is stored in aux.inheritBuff. Level is clamped to 1..[MAX_STEP].
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
     *   - "level": Int — desired level 1..[MAX_STEP]
     *   - "prevLevel": Int (optional, default 0) — the currently-owned level for this buffKey
     *     (PHP reads `aux.inheritBuff[type]`); the cost is charged as the difference.
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

        // Clamp level to 1..MAX_STEP (PHP validator: 1 <= level <= MAX_STEP).
        val level = clamp(requestedLevel.toDouble(), 1.0, MAX_STEP.toDouble()).toInt()
        val prevLevel = (args["prevLevel"] as? Number)?.toInt()?.coerceIn(0, MAX_STEP) ?: 0

        // PHP BuyHiddenBuff.php:61-66 — already-purchased / higher-grade guards.
        if (prevLevel == level) return InheritActionResult.failure("이미 구입했습니다.")
        if (prevLevel > level) return InheritActionResult.failure("이미 더 높은 등급을 구입했습니다.")

        // Cumulative DIFFERENCE cost (PHP BuyHiddenBuff.php:68).
        val levelCost = computeCost(level, prevLevel)
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

    /**
     * Cumulative-difference cost (PHP `BuyHiddenBuff.php:68`):
     * `inheritBuffPoints[level] - inheritBuffPoints[prevLevel]`. [GameConst.inheritBuffPoints] is
     * indexed DIRECTLY by level (`[0, 200, 600, 1200, 2000, 3000]`), so buying L2 after L1 costs
     * `600 - 200 = 400`, and a fresh L1 costs `200 - 0 = 200`.
     */
    private fun computeCost(level: Int, prevLevel: Int): Double {
        val points = GameConst.inheritBuffPoints
        val target = points.getOrElse(level) { 0 }
        val prev = points.getOrElse(prevLevel) { 0 }
        return (target - prev).toDouble()
    }

    companion object {
        const val NAME = "BuyHiddenBuff"

        /** PHP `TriggerInheritBuff::MAX_STEP` — the maximum purchasable buff level. */
        const val MAX_STEP = 5

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
