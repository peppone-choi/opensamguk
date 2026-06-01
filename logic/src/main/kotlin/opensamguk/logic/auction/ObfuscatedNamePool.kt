package opensamguk.logic.auction

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed

/**
 * Faithful port of PHP `Auction::genObfuscatedName` — the lazy-shuffle obfuscated name pool.
 *
 * The pool is built row-major: `firstName × middleName × lastName` (duplicates kept), shuffled once
 * with a `RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "obfuscatedNamePool")))`, then cached.
 * On daemon restart the cached pool is read back and NEVER re-shuffled.
 */
object ObfuscatedNamePool {

    /** The cartesian product size: firstName.count × middleName.count × lastName.count. */
    val poolSize: Int
        get() = GameConst.randGenFirstName.size * GameConst.randGenMiddleName.size * GameConst.randGenLastName.size

    /**
     * Build the full pool, shuffle it with the scenario hidden seed, and return the shuffled list.
     * The caller (engine rehydrate or auction resolver) is responsible for persisting to KV.
     */
    fun buildPool(hiddenSeed: String): List<String> {
        val pool = ArrayList<String>(poolSize)
        for (first in GameConst.randGenFirstName) {
            for (middle in GameConst.randGenMiddleName) {
                for (last in GameConst.randGenLastName) {
                    pool.add("$first$middle$last")
                }
            }
        }
        val rng = RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "obfuscatedNamePool")))
        return rng.shuffle(pool)
    }

    /**
     * Decode an obfuscated name from the pool.
     *
     * PHP (`Auction.php:59-65`):
     * ```php
     * $dupIdx = intdiv($id, count($namePool));
     * $subIdx = $id % count($namePool);
     * if ($dupIdx == 0) {
     *     return $namePool[$subIdx];
     * }
     * return "{$namePool[$subIdx]}{$dupIdx}";
     * ```
     */
    fun decode(id: Int, pool: List<String>): String {
        val dupIdx = id / pool.size
        val subIdx = id % pool.size
        val name = pool[subIdx]
        return if (dupIdx == 0) name else "${name}${dupIdx}"
    }
}
