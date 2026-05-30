package opensamguk.logic.tick

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed

/**
 * Month-scoped RNG lineage — P3 AREA F5, Task FR1 (design §11 P3 (f)).
 *
 * The THIRD RNG lineage of the tick (DISTINCT from the per-general `preprocess`/`nationCommand`/
 * `generalCommand` forks proven GREEN in P2). It REUSES the :common kernel verbatim — `RandUtil` over a
 * `LiteHashDrbg` seeded by `serializeSeed` (PHP `Util::simpleSerialize`); F5 introduces NO new RNG primitive,
 * only the seed-token literals + arities the monthly tick needs.
 *
 * Port target (PHP grand truth):
 *   - `TurnExecutionHelper.php:461-466` — `$monthlyRng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
 *         UniqueConst::$hiddenSeed, 'monthly', $gameStor->year, $gameStor->month)))`. Created ONCE per month
 *     (pre-`turnDate`) and passed ONLY to `postUpdateMonthly` — the Month event batch never receives it.
 *   - `RandomizeCityTradeRate.php:18-23` — `simpleSerialize(hidden, 'randomizeCityTradeRate', year, month)`.
 *   - `UpdateNationLevel.php:188-194` — `simpleSerialize(hidden, 'nationLevelUp', year, month, nationID)`
 *     (two-stage lottery: stage-1 winner selection).
 *   - `UpdateNationLevel.php:205-212` — `simpleSerialize(hidden, 'givenUnique', year, month, nationID, winnerID)`
 *     (stage-2 per-item grant for the chosen winner).
 *
 * Each event that needs RNG inside the Month batch (trade-rate randomization, the level-up lottery, etc.) seeds
 * its OWN DRBG internally via the matching sub-seed factory below — it does NOT borrow `$monthlyRng`. Keeping
 * the literal component-2 token distinct per lineage is what guarantees the draw streams never collide; that
 * separation is itself a P3 gate target (gate (f): both RNG lineages byte-match the PHP draw stream).
 *
 * `hiddenSeed` is the install-scoped 32-char lowercase hex threaded from `world_state.hidden_seed` by F4
 * (an intentional config-file→DB-column divergence from legacy `UniqueConst::$hiddenSeed`). Unit tests use the
 * 32-zero placeholder; G1b swaps in the live config hex as the shared golden fixture input.
 */
object MonthScopedRng {

    // ---- seed builders (the raw `serializeSeed` token strings; exposed for golden/byte-shape assertions) ----

    /** `simpleSerialize(hidden, 'monthly', year, month)` — the once-per-month seed (TurnExecutionHelper.php:461). */
    fun monthlySeed(hiddenSeed: String, year: Int, month: Int): String =
        serializeSeed(hiddenSeed, "monthly", year, month)

    /** `simpleSerialize(hidden, 'randomizeCityTradeRate', year, month)` (RandomizeCityTradeRate.php:18). */
    fun tradeRateSeed(hiddenSeed: String, year: Int, month: Int): String =
        serializeSeed(hiddenSeed, "randomizeCityTradeRate", year, month)

    /** `simpleSerialize(hidden, 'nationLevelUp', year, month, nationId)` (UpdateNationLevel.php:188). */
    fun nationLevelUpSeed(hiddenSeed: String, year: Int, month: Int, nationId: Int): String =
        serializeSeed(hiddenSeed, "nationLevelUp", year, month, nationId)

    /** `simpleSerialize(hidden, 'givenUnique', year, month, nationId, winnerId)` (UpdateNationLevel.php:205). */
    fun givenUniqueSeed(hiddenSeed: String, year: Int, month: Int, nationId: Int, winnerId: Int): String =
        serializeSeed(hiddenSeed, "givenUnique", year, month, nationId, winnerId)

    // ---- ready-to-draw RandUtil factories (each owns a FRESH DRBG over the matching seed) ----

    /** The once-per-month `$monthlyRng` (passed ONLY to postUpdateMonthly). */
    fun forMonth(hiddenSeed: String, year: Int, month: Int): RandUtil =
        RandUtil(LiteHashDrbg(monthlySeed(hiddenSeed, year, month)))

    /** Fresh RNG for RandomizeCityTradeRate (self-seeded at tick time, NOT borrowing $monthlyRng). */
    fun forTradeRate(hiddenSeed: String, year: Int, month: Int): RandUtil =
        RandUtil(LiteHashDrbg(tradeRateSeed(hiddenSeed, year, month)))

    /** Fresh RNG for the level-up lottery's stage-1 winner selection (per nation). */
    fun forNationLevelUp(hiddenSeed: String, year: Int, month: Int, nationId: Int): RandUtil =
        RandUtil(LiteHashDrbg(nationLevelUpSeed(hiddenSeed, year, month, nationId)))

    /** Fresh RNG for the level-up lottery's stage-2 per-item grant (per nation, per winner). */
    fun forGivenUnique(hiddenSeed: String, year: Int, month: Int, nationId: Int, winnerId: Int): RandUtil =
        RandUtil(LiteHashDrbg(givenUniqueSeed(hiddenSeed, year, month, nationId, winnerId)))
}
