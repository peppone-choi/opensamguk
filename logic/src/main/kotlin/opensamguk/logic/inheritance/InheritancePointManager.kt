package opensamguk.logic.inheritance

import opensamguk.common.constants.GameConst
import opensamguk.common.constants.EffectiveGameConst
import opensamguk.logic.domain.General
import opensamguk.logic.domain.metaDouble
import opensamguk.logic.domain.metaInt
import opensamguk.logic.util.phpRound
import opensamguk.logic.inheritance.InheritanceKey.Companion.keyName

/**
 * The central inheritance point manager — handles accumulation, derived calculation,
 * merge/apply lifecycle, and the isunited gate.
 *
 * Faithful port of the PHP InheritancePointManager pattern:
 *   - Direct keys (storeType=true): accumulated via addPoint into storage
 *   - Derived keys (storeType=false): computed at merge time from rank_data / general state
 *   - Merge: fold all keys → storage save → snapshot record
 *   - Apply: sum → rebirthCoeff → previous update → storage reset
 *
 * @param storage The KV storage backend (namespace = inheritance_{userId})
 * @param gameConst Effective game constants for costs/coefficients
 */
class InheritancePointManager(
    private val storage: InheritanceStorage,
    private val gameConst: EffectiveGameConst,
) {

    // ═══════════════════════════════════════════════════════════════════════
    //  Point accumulation (called during season play — e.g. turn execution)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Add a point value for a direct key. The value is multiplied by the key's coefficient.
     * NOP if the user is NPC or the key is not a direct key.
     *
     * isunited gate: when env.isunited != 0, only PREVIOUS key is allowed.
     */
    fun addPoint(userId: String, key: InheritanceKey, value: Double, env: GameEnv? = null) {
        if (!key.storeType) return // derived keys are not stored directly
        if (env != null && isGatedByUnited(env) && key != InheritanceKey.PREVIOUS) return
        if (value <= 0) return
        val current = storage.get(userId, key.keyName()) ?: 0.0
        storage.set(userId, key.keyName(), current + value * key.coefficient)
    }

    /** Increment the lived_month counter by 1 (called per executed turn). */
    fun incrementLiveMonth(userId: String, env: GameEnv? = null) {
        addPoint(userId, InheritanceKey.LIVED_MONTH, 1.0, env)
    }

    /**
     * Add active_action points. Each action contributes 1 * coefficient(3) = 3 points.
     * Called from commands like che_출병, che_거병, che_건국, etc.
     */
    fun addActiveAction(userId: String, count: Int = 1, env: GameEnv? = null) {
        addPoint(userId, InheritanceKey.ACTIVE_ACTION, count.toDouble(), env)
    }

    /** Add unifier points (건국 +250 or 통일 +2000). */
    fun addUnifierPoints(userId: String, amount: Double, env: GameEnv? = null) {
        if (env != null && isGatedByUnited(env)) return
        if (amount <= 0) return
        val current = storage.get(userId, InheritanceKey.UNIFIER.keyName()) ?: 0.0
        storage.set(userId, InheritanceKey.UNIFIER.keyName(), current + amount)
    }

    /** Update max_domestic_critical if the new value is higher. */
    fun updateMaxDomesticCritical(userId: String, value: Int, env: GameEnv? = null) {
        if (env != null && isGatedByUnited(env)) return
        val keyName = InheritanceKey.MAX_DOMESTIC_CRITICAL.keyName()
        val current = storage.get(userId, keyName) ?: 0.0
        if (value > current) {
            storage.set(userId, keyName, value.toDouble())
        }
    }

    /** Add tournament points. */
    fun addTournamentPoints(userId: String, amount: Double, env: GameEnv? = null) {
        addPoint(userId, InheritanceKey.TOURNAMENT, amount, env)
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Derived key calculation (computed at merge time from rank_data / general)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Calculate all derived key values for a general at merge time.
     * Returns a map of derived keys to their computed point values.
     *
     * @param general The general whose state feeds the calculation
     * @param rankData The rank_data values (warnum, firenum, etc.)
     * @param betData Optional betting data (if betting is tracked separately from rank_data)
     */
    fun calculateDerivedPoints(
        general: General,
        rankData: RankData,
        betData: BetData? = null,
    ): Map<InheritanceKey, Double> {
        val result = LinkedHashMap<InheritanceKey, Double>()

        // COMBAT: warnum * 5
        result[InheritanceKey.COMBAT] = InheritancePointCalculator.computeCombat(rankData.warnum)

        // SABOTAGE: firenum * 20
        result[InheritanceKey.SABOTAGE] = InheritancePointCalculator.computeSabotage(rankData.firenum)

        // DEX: sum of all arm-type dex values * 0.001
        val dexSum = computeDexSum(general)
        result[InheritanceKey.DEX] = InheritancePointCalculator.computeDex(dexSum)

        // BETTING: betwin * 10 * (betwingold / betgold)^2
        val betwin = betData?.betwin ?: rankData.betwin
        val betgold = betData?.betgold ?: rankData.betgold
        val betwingold = betData?.betwingold ?: rankData.betwingold
        result[InheritanceKey.BETTING] = InheritancePointCalculator.computeBetting(betwin, betwingold, betgold)

        // MAX_BELONG: max(belong, aux.max_belong) * 10
        val belong = extractBelong(general)
        val auxMaxBelong = extractAuxMaxBelong(general)
        result[InheritanceKey.MAX_BELONG] = InheritancePointCalculator.computeMaxBelong(belong, auxMaxBelong)

        return result
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Merge / Apply lifecycle
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * MERGE phase: fold all direct + derived keys, save to storage, record snapshot.
     * Called at season-end (check_united), death (General::kill), and at month==1/7 (event).
     *
     * @param userId The user/owner ID
     * @param general The general whose state feeds derived calculations
     * @param rankData Rank data for derived key calculation
     * @param betData Optional separate betting data
     */
    fun mergeTotalInheritancePoint(
        userId: String,
        general: General,
        rankData: RankData,
        betData: BetData? = null,
    ): Map<InheritanceKey, Double> {
        // 1. Read all direct keys from storage
        val allStored = storage.getAll(userId)
        val merged = LinkedHashMap<InheritanceKey, Double>()

        // Direct keys: read from storage (with coefficient already applied during accumulation)
        for (key in InheritanceKey.DIRECT_KEYS) {
            val storedValue = allStored[key.keyName()] ?: 0.0
            merged[key] = storedValue
        }

        // 2. Calculate derived keys
        val derived = calculateDerivedPoints(general, rankData, betData)
        merged.putAll(derived)

        // 3. Write all values back to storage (for apply phase)
        for ((key, value) in merged) {
            storage.set(userId, key.keyName(), value)
        }

        return merged
    }

    /**
     * APPLY phase: sum all keys → apply rebirthCoeff → update previous → reset storage.
     * Called at season transition, death, or rebirth.
     *
     * @param userId The user/owner ID
     * @param rebirthCoeff 보존률 (1.0 = full keep, 0.5 = 50% keep). Default 1.0 for normal apply.
     */
    fun applyInheritanceUser(userId: String, rebirthCoeff: Double = 1.0): Double {
        val allStored = storage.getAll(userId)

        // Sum all keys (previous is included — it's already the carried-over balance)
        var total = 0.0
        for (key in InheritanceKey.entries) {
            total += allStored[key.keyName()] ?: 0.0
        }

        // Apply rebirth coefficient (환생 보존률)
        val preservedTotal = total * rebirthCoeff

        // Round using PhpRound for parity
        val roundedTotal = phpRound(preservedTotal).toDouble()

        // Set the new previous value
        storage.set(userId, PREVIOUS_KEY, roundedTotal)

        // Clear all keys except previous
        val keepKeys = setOf(PREVIOUS_KEY)
        storage.clear(userId, keepKeys)

        return roundedTotal
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Point queries
    // ═══════════════════════════════════════════════════════════════════════

    /** Get the spendable point balance (the 'previous' key = 이월된 포인트 잔액). */
    fun getSpendablePoints(userId: String): Double {
        return storage.get(userId, PREVIOUS_KEY) ?: 0.0
    }

    /** Get the total of all inheritance point keys (spendable + earned this season). */
    fun getTotalPoints(userId: String): Double {
        val allStored = storage.getAll(userId)
        var total = 0.0
        for (key in InheritanceKey.entries) {
            total += allStored[key.keyName()] ?: 0.0
        }
        return total
    }

    /** Get the per-key breakdown for display/debugging. */
    fun getPointBreakdown(userId: String): Map<InheritanceKey, Double> {
        val allStored = storage.getAll(userId)
        return InheritanceKey.entries.associateWith { allStored[it.keyName()] ?: 0.0 }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  isunited gating
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * isunited != 0 → 천하통일 상태.
     * When united:
     *   - 획득 차단: previous key만 유효, 나머지 모든 키는 억제
     *   - 소비 차단: InheritAction API 전체 비활성화
     *   - Join-time 본너스는 차단되지 않음 (시즌 시작 시 사용)
     */
    fun isGatedByUnited(gameEnv: GameEnv): Boolean = gameEnv.isunited != 0

    // ═══════════════════════════════════════════════════════════════════════
    //  Utility: consume spendable points (for InheritAction purchases)
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Attempt to consume [amount] points from the spendable balance.
     * Returns true if successful, false if insufficient balance.
     */
    fun tryConsumePoints(userId: String, amount: Double): Boolean {
        val current = getSpendablePoints(userId)
        if (current < amount) return false
        storage.set(userId, PREVIOUS_KEY, current - amount)
        return true
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Private helpers
    // ═══════════════════════════════════════════════════════════════════════

    private fun computeDexSum(general: General): Double {
        var sum = 0.0
        for (i in 1..5) {
            sum += metaDouble(general.meta, "dex$i")
        }
        return sum
    }

    /** Extract the general's 'belong' value from meta or return 0. */
    private fun extractBelong(general: General): Int {
        return metaInt(general.meta, "belong")
    }

    /** Extract aux.max_belong from the general's aux meta. */
    private fun extractAuxMaxBelong(general: General): Int? {
        @Suppress("UNCHECKED_CAST")
        val aux = general.meta["aux"] as? Map<String, Any?> ?: return null
        val maxBelong = aux["max_belong"] as? Number ?: return null
        return maxBelong.toInt()
    }

    /**
     * Get a single key's stored value for a user (used by MergeInheritPointRank).
     * Returns 0.0 if the key is not found.
     */
    fun getKeyValue(userId: String, key: InheritanceKey): Double {
        return storage.get(userId, key.keyName()) ?: 0.0
    }

    /**
     * Batch version: get all folded key values for a user.
     * Returns a map of key-name -> point value (excluding 'previous').
     * Used by the MergeInheritWorld seam to feed MergeInheritPointRank.
     */
    fun getFoldedKeyValues(userId: String): Map<String, Double> {
        val allStored = storage.getAll(userId)
        val result = LinkedHashMap<String, Double>()
        for (key in InheritanceKey.entries) {
            if (key == InheritanceKey.PREVIOUS) continue
            result[key.keyName()] = allStored[key.keyName()] ?: 0.0
        }
        return result
    }

    companion object {
        /** The storage key for the spendable (carried-over) point balance. */
        const val PREVIOUS_KEY = "previous"
    }
}
