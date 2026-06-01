package opensamguk.logic.diplomacy

import opensamguk.logic.domain.Diplomacy
import kotlin.math.max
import kotlin.math.min

/**
 * Faithful Kotlin port of TypeScript `processDiplomacyMonth()`
 * (`legacy_core2026/packages/logic/src/diplomacy/index.ts`).
 *
 * Called once per game month to advance every diplomacy row:
 *  1. War-term extension — casualties (`dead`) increase the war duration.
 *  2. War-end detection — when BOTH directional rows have term <= 1, they revert to TRADE.
 *  3. Casualty reset + term decrement — non-war rows reset dead; every row's term--.
 *  4. Expiry handling — NON_AGGRESSION → TRADE, DECLARATION → WAR when term hits 0.
 *
 * The processor returns a **new list** of mutated diplomacy entries (immutable-out idiom).
 */
object DiplomacyMonthProcessor {

    /**
     * Process one month tick for the entire diplomacy matrix.
     *
     * @param diplomacy Current snapshot of all directional diplomacy rows.
     * @param generalCounts Map of nationId → general count (used for casualty→term conversion).
     * @return The next-state diplomacy list.
     */
    fun process(
        diplomacy: List<Diplomacy>,
        generalCounts: Map<Int, Int>,
    ): List<Diplomacy> {
        // --- Step 0: mutable working copies indexed by (me, you) ---
        val entries = diplomacy.map { it.copy() }.toMutableList()
        val index = entries.associateBy { it.me to it.you }.toMutableMap()

        // --- Step 1: War term extension from casualties ---
        for (entry in entries) {
            if (entry.state != DiplomacyState.WAR || entry.dead <= 0) continue
            val genCount = max(1, generalCounts[entry.me] ?: 1)
            val termIncrease = entry.dead / 100 / genCount
            entry.dead -= termIncrease * 100 * genCount
            entry.term = min(entry.term + termIncrease, DiplomacyConst.MAX_WAR_TERM)
        }

        // --- Step 2: War-end detection (both directions term <= 1) ---
        val warPairs = entries.filter { it.state == DiplomacyState.WAR }
        val processedPairs = mutableSetOf<Pair<Int, Int>>()
        for (entry in warPairs) {
            val pair = if (entry.me < entry.you) entry.me to entry.you else entry.you to entry.me
            if (pair in processedPairs) continue
            processedPairs.add(pair)

            val forward = index[entry.me to entry.you]
            val reverse = index[entry.you to entry.me]
            if (forward != null && reverse != null &&
                forward.state == DiplomacyState.WAR && forward.term <= 1 &&
                reverse.state == DiplomacyState.WAR && reverse.term <= 1
            ) {
                forward.state = DiplomacyState.TRADE
                forward.term = 0
                reverse.state = DiplomacyState.TRADE
                reverse.term = 0
            }
        }

        // --- Step 3: Casualty reset + term decrement ---
        for (entry in entries) {
            if (entry.state != DiplomacyState.WAR) {
                entry.dead = 0
            }
            entry.term = max(0, entry.term - 1)
        }

        // --- Step 4: Expiry handling ---
        for (entry in entries) {
            if (entry.term == 0) {
                when (entry.state) {
                    DiplomacyState.NON_AGGRESSION -> {
                        entry.state = DiplomacyState.TRADE
                    }
                    DiplomacyState.DECLARATION -> {
                        entry.state = DiplomacyState.WAR
                        entry.term = DiplomacyConst.DEFAULT_WAR_TERM
                    }
                }
            }
        }

        return entries
    }
}
