package opensamguk.logic.domestic

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.domain.General
import opensamguk.logic.domain.NpcType
import kotlin.math.pow

/**
 * Unique-item lottery seam — research §Cross-cutting #5, OQ6.
 *
 * Port target:
 *   - func_gamerule.php:954-986  genGenericUniqueRNG[FromGeneral]
 *   - func.php:1611-1703         tryUniqueItemLottery
 *
 * The lottery rides a SEPARATE rng (component-2 literal "unique" — distinct from the per-action
 * "generalCommand"/"nationCommand" seed), so it can never reorder the per-action draw stream. The
 * NPCType>=2 short-circuit returns BEFORE touching the rng, so an NPC's lottery costs zero draws. Fires
 * AFTER all DB writes + checkStatChange.
 *
 * For P2 the prob-derivation (genCount / item-count / year curve) and giveRandomUniqueItem are out of
 * scope — the seam takes the already-derived [prob] / [trialCount] and reports whether a unique was won.
 * For the captured P2-domestic goldens the default general's effective prob is 0, so it wins nothing.
 */

/** func.php:1679 — the per-trial probability multiplier pow(10, 1/4). */
private val MORE_PROB: Double = 10.0.pow(0.25)

/**
 * func_gamerule.php:976-986 genGenericUniqueRNGFromGeneral → the seed for the lottery's SEPARATE rng:
 * simpleSerialize(hiddenSeed, 'unique', year, month, generalID, reason). `reason` is always supplied by
 * the FromGeneral path, so this is the 6-component "unique" fork.
 */
fun uniqueLotterySeed(hiddenSeed: String, year: Int, month: Int, generalId: Int, reason: String): String =
    serializeSeed(hiddenSeed, "unique", year, month, generalId, reason)

/**
 * func.php:1611-1703 (tryUniqueItemLottery), reduced to the seam P2 needs:
 *   - line 1616-1618: getNPCType() >= 2 → `return false` BEFORE any rng draw.
 *   - line 1689-1695: humans draw nextBool(prob) over the trial loop; on success → true (item acquisition),
 *     each miss multiplies prob by MORE_PROB.
 *
 * @return true iff a unique item is acquired. (giveRandomUniqueItem's actual item pick is downstream of P2.)
 */
fun tryUniqueItemLottery(
    rng: RandUtil,
    general: General,
    prob: Double,
    trialCount: Int,
): Boolean {
    // NPC short-circuit — must precede any rng use so the action stream is never perturbed.
    if (NpcType.getNpcType(general) >= NpcType.NPC_SHORT_CIRCUIT) {
        return false
    }

    var p = prob
    repeat(trialCount) {           // PHP: foreach (Util::range($maxCnt) as $_idx)
        if (rng.nextBool(p)) {
            return true            // result = true; break
        }
        p *= MORE_PROB             // prob *= moreProb
    }
    return false
}
