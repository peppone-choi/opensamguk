package opensamguk.logic.war

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F6 / FS1 — the two DISTINCT battle RNG lineages threaded into [opensamguk.logic.war] (research Units 3/7/8,
 * consolidated OQ #7).
 *
 * PHP grand truth:
 *   - war seed (che_출병.php:245-253):
 *       new LiteHashDRBG(Util::simpleSerialize(hidden, 'war', logger.year, logger.month, general.id, defenderCityID))
 *       warSeed = bin2hex($warRngPre->nextBytes(16))           // 32-char lowercase hex
 *       processWar(): $rng = new RandUtil(new LiteHashDRBG($warSeed))   // ONE stream, process_war.php:11
 *   - ConquerCity seed (process_war.php:549 AND :589, BUILT TWICE, IDENTICAL):
 *       new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
 *           hidden, 'ConquerCity', year, month, attackerNationID, attackerID, cityID)))   // 7-arg
 *       → the stream RESETS to idx 0 after the OccupyCity event (the double-seed).
 *
 * year/month come from the actor's LOGGER (the turn's month bucket), NOT a raw env (OQ #7).
 * REUSES the GREEN :common kernel (serializeSeed == Util::simpleSerialize, LiteHashDrbg, RandUtil) verbatim —
 * F6 introduces NO new RNG primitive, only the 'war'/'ConquerCity' token literals + arities.
 */
class WarSeedTest {

    private val hidden = "8ebfeb6fa932a181ec9ef43b7473f4c9"  // the live UniqueConst::$hiddenSeed fixture

    // ---- WarSeed: the 'war' 6-tuple → nextBytes(16) → lowercase hex ----

    @Test
    fun `warSeed token is the war 6-tuple (str-len-encoded, logger year-month)`() {
        // simpleSerialize(hidden, 'war', year, month, genId, destCityId)
        val expected = serializeSeed(hidden, "war", 200, 3, 42, 17)
        assertEquals(WarSeed.seed(hidden, year = 200, month = 3, genId = 42, destCityId = 17), expected)
    }

    @Test
    fun `warSeed is bin2hex of nextBytes(16) of the LiteHashDrbg over the war token`() {
        val token = serializeSeed(hidden, "war", 200, 3, 42, 17)
        val raw = LiteHashDrbg(token).nextBytes(16)
        val expected = raw.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val actual = WarSeed.build(hidden, year = 200, month = 3, genId = 42, destCityId = 17)
        assertEquals(expected, actual)
        // bin2hex(16 bytes) is exactly 32 lowercase hex chars
        assertEquals(32, actual.length)
        assertTrue(actual.all { it in "0123456789abcdef" }, "warSeed must be lowercase hex: $actual")
    }

    @Test
    fun `warSeed is deterministic — same inputs reproduce the same hex`() {
        val a = WarSeed.build(hidden, year = 200, month = 3, genId = 42, destCityId = 17)
        val b = WarSeed.build(hidden, year = 200, month = 3, genId = 42, destCityId = 17)
        assertEquals(a, b)
    }

    @Test
    fun `warSeed rng is the ONE shared stream — RandUtil over LiteHashDrbg of the hex seed`() {
        val warSeed = WarSeed.build(hidden, year = 200, month = 3, genId = 42, destCityId = 17)
        val expected = RandUtil(LiteHashDrbg(warSeed)).nextRange(0.9, 1.1)
        val actual = WarSeed.rng(warSeed).nextRange(0.9, 1.1)
        assertEquals(expected, actual)
    }

    // ---- ConquerCitySeed: the distinct 7-arg 'ConquerCity' lineage + the double-seed reset ----

    @Test
    fun `ConquerCity token is the 7-arg ConquerCity tuple`() {
        val expected = serializeSeed(hidden, "ConquerCity", 200, 3, 5, 42, 17)
        assertEquals(
            ConquerCitySeed.seed(hidden, year = 200, month = 3, attNationId = 5, attId = 42, cityId = 17),
            expected,
        )
    }

    @Test
    fun `war lineage differs from ConquerCity lineage for the same year-month`() {
        // distinct component-2 token ('war' vs 'ConquerCity') ⇒ distinct seed strings even at the same (y,m)
        val warToken = WarSeed.seed(hidden, year = 200, month = 3, genId = 42, destCityId = 17)
        val conquerToken = ConquerCitySeed.seed(hidden, year = 200, month = 3, attNationId = 5, attId = 42, cityId = 17)
        assertNotEquals(warToken, conquerToken)
    }

    @Test
    fun `ConquerCity rng rebuilt with identical args RESETS — byte-identical draw stream (the double-seed)`() {
        // process_war.php:549 and :589 build the SAME 7-arg DRBG twice; the second build resets the stream to idx 0.
        val first = ConquerCitySeed.rng(hidden, year = 200, month = 3, attNationId = 5, attId = 42, cityId = 17)
        val streamA = (0 until 8).map { first.nextRangeInt(0, 1000) }

        val second = ConquerCitySeed.rng(hidden, year = 200, month = 3, attNationId = 5, attId = 42, cityId = 17)
        val streamB = (0 until 8).map { second.nextRangeInt(0, 1000) }

        assertEquals(streamA, streamB, "the rebuilt ConquerCity rng must reproduce the same draw stream (reset to idx 0)")
    }

    @Test
    fun `ConquerCity rng is deterministic and differs from the war stream`() {
        val warSeed = WarSeed.build(hidden, year = 200, month = 3, genId = 42, destCityId = 17)
        val warDraw = RandUtil(LiteHashDrbg(warSeed)).nextRangeInt(0, 1000)
        val conquerDraw = ConquerCitySeed.rng(hidden, year = 200, month = 3, attNationId = 5, attId = 42, cityId = 17)
            .nextRangeInt(0, 1000)
        // separate lineages ⇒ the streams must not collide (sanity; not a hard parity claim)
        assertNotEquals(warDraw, conquerDraw)
    }
}
