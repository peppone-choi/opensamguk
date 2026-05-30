package opensamguk.logic.ai.families

import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General

/**
 * L-GENFOUND — the founding / nation-selection `do<한글>` command family (거병/해산/건국/선양/국가선택/사망대비/중립).
 *
 * **STATUS (FQ1, F-QUAR):** this file currently holds ONLY the `ORDER BY RAND()` deterministic-substitute
 * helpers (the F-QUAR quarantine). The full `do<한글>` method bodies are ADDED LATER by L-GENFOUND (Wave-1);
 * this is the substitute's home because `do선양` (`:3320`) and `do국가선택`-오랑캐 (`:3334`) live in this family.
 *
 * ---
 *
 * ## The `ORDER BY RAND()` quarantine (decision #6, G4, R-GATE §1) — the ONLY two non-DRBG random picks in the AI
 *
 * GRAND TRUTH = PHP `GeneralAI.php`:
 * ```php
 * // :3324  do선양     SELECT `no`   FROM general WHERE nation = %i AND npc != 5                ORDER BY RAND() LIMIT 1 → destGeneralID
 * // :3345  do국가선택  SELECT nation FROM general WHERE officer_level=12 AND npc=9 and nation    ORDER BY RAND() limit 1 → rulerNation
 * ```
 * Both are **MySQL/MariaDB-side `RAND()`** — they draw **ZERO bytes** off the `RandUtil(LiteHashDrbg)` DRBG
 * stream, so the AI's per-general rng cursor (`stateIdx`/`bufferIdx`) is **UNAFFECTED** (no downstream desync).
 * The ONLY non-determinism is *which row id is chosen among ties*; PHP itself is non-deterministic here and a
 * captured id would fail the "byte-identical across two runs" install rule.
 *
 * **The faithful substitute (CLAUDE.md parity rule #5: quarantine-with-proof, NEVER fabricate):**
 * a deterministic `min(no)` / `min(nation)` over the SAME WHERE-filtered candidate set. The candidate-set
 * ITERATION order is general.no insertion order (G13), but the PICK is `min(no)` so it is order-independent
 * and reviewer-legible. **These helpers MUST NOT pull a single draw off [rng]** — they take the [RandUtil] only
 * to make the 0-draw contract explicit at the call site (a reviewer must not "fix" this by inserting a draw;
 * the cursor is a parity target). `@ParityQuarantine("G4-order-by-rand")`.
 *
 * **Gate reachability — ZERO at gate start (R-GATE §1, G4 §3):** scenario 1010 has **0 npc==5 and 0 npc==9**
 * generals of 678 (install assigns only npc 2/6). `can선양` requires npc==5 (`AutorunGeneralPolicy.php:98-100`);
 * 오랑캐임관 requires npc==9 (`GeneralAI.php:3343`); the 2 officer_level==12 rulers (하진/장각) are both npc==2.
 * ⇒ NEITHER `ORDER BY RAND()` site fires in the P5 gate — tail paths, not gate paths. DO NOT fabricate an id,
 * weaken a test, or seed npc 5/9 into 1010. Registered in `.context/p5-research/QUARANTINE-REGISTER.md`.
 */
object GenFoundFamily {

    /**
     * `do선양` `ORDER BY RAND()` substitute (`GeneralAI.php:3324`):
     * `SELECT \`no\` FROM general WHERE nation = %i AND npc != 5 ORDER BY RAND() LIMIT 1`.
     *
     * Deterministic substitute = `min(no)` over the same WHERE-filtered set (own [nationId], `npcType != 5`).
     * Returns `null` when the set is empty (PHP `queryFirstField` → null — no fabrication).
     *
     * **0-draw quarantine (G4):** [rng] is accepted only to document the contract at the call site; this method
     * consumes NO draws — the AI's DRBG cursor (stateIdx/bufferIdx) is unaffected, exactly like MySQL `RAND()`.
     */
    // @ParityQuarantine("G4-order-by-rand"): MySQL RAND() is un-replayable; DRBG stream unaffected (0 draws);
    // deterministic min(no) substitute; reachable only for an npc==5 officer_level==12 ruler — absent in 1010.
    @Suppress("UNUSED_PARAMETER")
    fun seonyangDestGeneralId(nationId: Int, candidates: List<General>, rng: RandUtil): Int? =
        candidates
            .filter { it.nationId == nationId && it.npcType != 5 }
            .minByOrNull { it.id }
            ?.id

    /**
     * `do국가선택`-오랑캐 `ORDER BY RAND()` substitute (`GeneralAI.php:3345`):
     * `SELECT nation FROM general WHERE \`officer_level\`=12 AND npc=9 and nation ORDER BY RAND() limit 1`.
     *
     * The SQL projects the `nation` column ordered by RAND(); the deterministic substitute (G4 §4: "min(nation)
     * = the nation of the smallest such `no`") is the `nationId` of the `min(no)` matching row. The trailing
     * `and nation` is the falsy-`nation` guard (`nation != 0`). Returns `null` when no 오랑캐-ruled nation exists
     * (PHP `queryFirstField` → null).
     *
     * **0-draw quarantine (G4):** consumes NO draws off [rng]; the DRBG cursor is unaffected.
     */
    // @ParityQuarantine("G4-order-by-rand"): MySQL RAND() un-replayable; DRBG stream unaffected (0 draws);
    // deterministic nation-of-min(no) substitute; reachable only for an npc==9 오랑캐 general — absent in 1010.
    @Suppress("UNUSED_PARAMETER")
    fun orankaeRulerNation(candidates: List<General>, rng: RandUtil): Int? =
        candidates
            .filter { it.officerLevel == 12 && it.npcType == 9 && it.nationId != 0 }
            .minByOrNull { it.id }
            ?.nationId
}
