package opensamguk.logic.war

import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed

/**
 * F6 / FS1 — the `che_출병` battle RNG lineage (research Units 3/7, consolidated OQ #7).
 *
 * Port target (PHP frozen historical baseline (ADR-LITE-042; not current product authority)):
 *   - `che_출병.php:245-253`:
 *       ```php
 *       $warRngPre = new LiteHashDRBG(Util::simpleSerialize(
 *           UniqueConst::$hiddenSeed, 'war', $logger->getYear(), $logger->getMonth(),
 *           $general->getID(), $defenderCityID));
 *       $warRngSeed = bin2hex($warRngPre->nextBytes(16));
 *       processWar($warRngSeed, ...);
 *       ```
 *   - `process_war.php:11`: `$rng = new RandUtil(new LiteHashDRBG($warSeed))` — the SINGLE shared stream built
 *     ONCE and threaded by reference into EVERY WarUnit + getNextDefender. `warSeed` is only ECHOED into the
 *     진격 log (`process_war.php:252-253`); processWar_NG NEVER re-creates a RandUtil.
 *
 * REUSES the GREEN :common kernel verbatim ([serializeSeed] == `Util::simpleSerialize`, [LiteHashDrbg],
 * [RandUtil]) — F6 introduces NO new RNG primitive, only the `'war'` token literal + the 6-tuple arity.
 *
 * year/month come from the actor's LOGGER (the turn's month bucket), NOT a raw env (OQ #7).
 * `hiddenSeed` is the install-scoped 32-char lowercase hex (legacy `UniqueConst::$hiddenSeed`, threaded by
 * opensamguk from `world_state.hidden_seed`).
 */
object WarSeed {

    /**
     * The raw `Util::simpleSerialize(hidden, 'war', year, month, genId, destCityId)` token string
     * (exposed for golden/byte-shape assertions; the input to the pre-DRBG).
     */
    fun seed(hidden: String, year: Int, month: Int, genId: Int, destCityId: Int): String =
        serializeSeed(hidden, "war", year, month, genId, destCityId)

    /**
     * `bin2hex($warRngPre->nextBytes(16))` — the 32-char lowercase hex war seed that becomes the SINGLE shared
     * battle stream's seed. The pre-DRBG is a throwaway used ONLY to derive this hex; the battle rng is then
     * [rng] over the hex (`process_war.php:11`).
     */
    fun build(hidden: String, year: Int, month: Int, genId: Int, destCityId: Int): String {
        val pre = LiteHashDrbg(seed(hidden, year, month, genId, destCityId))
        return bin2hex(pre.nextBytes(16))
    }

    /**
     * `new RandUtil(new LiteHashDRBG($warSeed))` (`process_war.php:11`) — the ONE shared battle stream over the
     * hex seed. Build this ONCE in `processWar()` and thread it by reference; NEVER re-seed inside processWar_NG.
     */
    fun rng(warSeed: String): RandUtil = RandUtil(LiteHashDrbg(warSeed))
}

/**
 * PHP `bin2hex` — lowercase, two zero-padded hex chars per byte. Java 21 `HexFormat.of()` is lowercase by
 * default, matching PHP byte-for-byte.
 */
internal fun bin2hex(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(bytes)
