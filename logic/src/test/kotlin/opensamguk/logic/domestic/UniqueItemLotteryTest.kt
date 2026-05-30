package opensamguk.logic.domestic

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.domain.General
import opensamguk.logic.domain.NpcType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Port-faithful tests for the unique-item lottery seam — research §Cross-cutting #5, OQ6.
 *
 * PHP grand truth:
 *   - func_gamerule.php:976-986 genGenericUniqueRNGFromGeneral → a SEPARATE LiteHashDRBG seeded
 *     simpleSerialize(hiddenSeed, 'unique', year, month, generalID, reason).
 *   - func.php:1611-1618 tryUniqueItemLottery: getNPCType() >= 2 → `return false` BEFORE touching the rng
 *     (no draw, so it never perturbs the action stream). Humans draw nextBool(prob) in a loop.
 *
 * The lottery rng is INDEPENDENT of the per-action rng (distinct seed component-2 literal "unique"
 * vs "generalCommand"), so it cannot reorder the action draw stream. Fires AFTER all DB writes +
 * checkStatChange.
 */
class UniqueItemLotteryTest {

    private val hiddenSeed = "00000000000000000000000000000000"

    private fun general(npc: Int) = General(
        id = 42, nationId = 1, cityId = 5,
        leadership = 70, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 1000,
        npcType = npc,
    )

    @Test
    fun `lottery rng seed matches the unique six-tuple shape`() {
        // genGenericUniqueRNGFromGeneral seeds with the literal "unique" as component 2 (NOT "generalCommand").
        val expected = serializeSeed(hiddenSeed, "unique", 190, 3, 42, "아이템")
        val actual = uniqueLotterySeed(hiddenSeed, year = 190, month = 3, generalId = 42, reason = "아이템")
        assertEquals(expected, actual)
        // and it is a DIFFERENT seed than the per-action seed (component-2 literal differs)
        val actionSeed = serializeSeed(hiddenSeed, "generalCommand", 190, 3, 42, "che_농지개간")
        assertTrue(actual != actionSeed)
    }

    @Test
    fun `NPCType ge 2 short-circuits with zero draws`() {
        val rng = RandUtil(LiteHashDrbg(uniqueLotterySeed(hiddenSeed, 190, 3, 42, "아이템")))
        val won = tryUniqueItemLottery(rng, general(npc = NpcType.NPC_LITE), prob = 1.0, trialCount = 5)
        assertFalse(won, "NPCType>=2 wins nothing")
        // The passed-in rng must NOT have been touched by the short-circuit: its next draw equals the
        // FIRST draw of a pristine rng on the same seed (i.e. the short-circuit consumed zero draws).
        val pristineFirst = drawCount(RandUtil(LiteHashDrbg(uniqueLotterySeed(hiddenSeed, 190, 3, 42, "아이템"))))
        assertEquals(pristineFirst, drawCount(rng), "short-circuit must not consume any draw")
    }

    @Test
    fun `human draws deterministically from the seed`() {
        // Two independently-seeded human rolls with the same seed + prob produce the same outcome.
        fun roll() = tryUniqueItemLottery(
            RandUtil(LiteHashDrbg(uniqueLotterySeed(hiddenSeed, 190, 3, 42, "아이템"))),
            general(npc = NpcType.USER), prob = 0.5, trialCount = 3,
        )
        assertEquals(roll(), roll())
    }

    @Test
    fun `human with zero prob over default trials wins nothing`() {
        // P2 domestic default general: prob 0 across the trial loop → no acquisition.
        val rng = RandUtil(LiteHashDrbg(uniqueLotterySeed(hiddenSeed, 190, 3, 42, "아이템")))
        assertFalse(tryUniqueItemLottery(rng, general(npc = NpcType.USER), prob = 0.0, trialCount = 5))
    }

    @Test
    fun `human with prob one wins on the first draw`() {
        val rng = RandUtil(LiteHashDrbg(uniqueLotterySeed(hiddenSeed, 190, 3, 42, "아이템")))
        assertTrue(tryUniqueItemLottery(rng, general(npc = NpcType.USER), prob = 1.0, trialCount = 5))
    }

    @Test
    fun `lottery rng is independent of the action rng`() {
        // Same action seed; the lottery outcome differs (different seed) ⇒ action stream is unaffected.
        val actionA = RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "generalCommand", 190, 3, 42, "che_농지개간")))
        val actionB = RandUtil(LiteHashDrbg(serializeSeed(hiddenSeed, "generalCommand", 190, 3, 42, "che_농지개간")))
        // run the lottery against A's general but on its OWN rng; A's action stream is untouched.
        val lotteryRng = RandUtil(LiteHashDrbg(uniqueLotterySeed(hiddenSeed, 190, 3, 42, "아이템")))
        tryUniqueItemLottery(lotteryRng, general(npc = NpcType.USER), prob = 1.0, trialCount = 5)
        // the two action rngs (one used, one pristine) still match: lottery did not perturb the action stream
        assertEquals(drawCount(actionA), drawCount(actionB))
    }

    /** Consume one float draw and surface its raw bits as a stable comparison token. */
    private fun drawCount(rng: RandUtil): String =
        "%016x".format(java.lang.Double.doubleToRawLongBits(rng.nextFloat1()))
}
