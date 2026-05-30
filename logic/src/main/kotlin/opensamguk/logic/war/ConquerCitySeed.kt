package opensamguk.logic.war

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed

/**
 * F6 / FS1 — the `ConquerCity` RNG lineage (research Unit 8, consolidated OQ #7).
 *
 * DISTINCT from the [WarSeed] battle stream: the conquest sub-stream is SEPARATE from the battle stream and is
 * keyed by a different component-2 token (`'ConquerCity'` vs `'war'`) and a different 7-arg tuple, so the two
 * lineages never collide.
 *
 * Port target (PHP grand truth):
 *   - `process_war.php:549` AND `:589` — the SAME 7-arg DRBG is built TWICE, IDENTICAL:
 *       ```php
 *       $rng = new RandUtil(new LiteHashDRBG(Util::simpleSerialize(
 *           UniqueConst::$hiddenSeed, 'ConquerCity', $year, $month,
 *           $attackerNationID, $attackerID, $cityID)));
 *       ```
 *     The first build (`:549`) drives the conquest side-effects up to the OccupyCity event; after the event the
 *     SECOND build (`:589`) RESETS the stream to idx 0 and drives the defender `onArbitraryAction` collapse loop.
 *
 * The double-seed (built twice, identical → stream RESETS) is reproduced HERE by [rng] returning a FRESH
 * [RandUtil] over the same seed each call: the B2 ConquerCity resolver builds the rng AGAIN after the OccupyCity
 * event rather than threading a single rng through the event handler. No new RNG primitive — the GREEN :common
 * kernel ([serializeSeed] == `Util::simpleSerialize`, [LiteHashDrbg], [RandUtil]) verbatim.
 *
 * year/month come from the actor's LOGGER (the turn's month bucket), NOT a raw env (OQ #7).
 */
object ConquerCitySeed {

    /**
     * The raw `Util::simpleSerialize(hidden, 'ConquerCity', year, month, attNationId, attId, cityId)` token
     * (exposed for golden/byte-shape assertions). 7-arg — note this is distinct from the [WarSeed] 6-tuple.
     */
    fun seed(
        hidden: String,
        year: Int,
        month: Int,
        attNationId: Int,
        attId: Int,
        cityId: Int,
    ): String = serializeSeed(hidden, "ConquerCity", year, month, attNationId, attId, cityId)

    /**
     * A FRESH `new RandUtil(new LiteHashDRBG(simpleSerialize(...)))` over the conquest token. Each call resets the
     * stream to idx 0 — that is the double-seed: `process_war.php:549` and `:589` call this with IDENTICAL args,
     * and the second build reproduces the same draw stream from the start.
     */
    fun rng(
        hidden: String,
        year: Int,
        month: Int,
        attNationId: Int,
        attId: Int,
        cityId: Int,
    ): RandUtil = RandUtil(LiteHashDrbg(seed(hidden, year, month, attNationId, attId, cityId)))
}
