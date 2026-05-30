package opensamguk.logic.domain

/**
 * NPCType taxonomy — a cross-cutting guard (research OQ8 / lines 207-208, 907).
 *
 * PHP `GeneralBase::getNPCType()` (GeneralBase.php:79-82) simply returns `$this->raw['npc']` (the
 * V1 `general.npc_state` column). The observed values are 0,1,2,3,5,6,9:
 *   0 = user (human-controlled)
 *   1 = NPC (basic)
 *   2 = NPC-lite (the `>= 2` short-circuit threshold)
 *   3 = NPC
 *   5 = NPC variant that SKIPS the 하야 gennum decrement (`!= 5`)
 *   6 = NPC
 *   9 = 이민족 (gates ⓞ-nation join)
 *
 * The load-bearing thresholds (research line 907):
 *   - `>= 2` short-circuits the unique-item lottery (no draw) AND enables trade without a trader.
 *   - `!= 5` decrements gennum on 하야.
 *   - `== 9` is 이민족 (join gate).
 */
object NpcType {
    const val USER: Int = 0
    const val NPC_BASIC: Int = 1
    const val NPC_LITE: Int = 2
    const val NPC_3: Int = 3
    const val NPC_NO_GENNUM_DROP: Int = 5
    const val NPC_6: Int = 6
    const val FOREIGN: Int = 9   // 이민족

    /** Threshold at and above which the unique-item lottery short-circuits / trade needs no trader. */
    const val NPC_SHORT_CIRCUIT: Int = 2

    /** PHP `getNPCType()` — the general's `npc_state` value. */
    fun getNpcType(general: General): Int = general.npcType

    /** `getNPCType() >= 2` — lottery short-circuit / trade-without-trader (no action-stream draw). */
    fun isLotteryShortCircuit(general: General): Boolean = getNpcType(general) >= NPC_SHORT_CIRCUIT

    /** `getNPCType() != 5` — 하야 decrements gennum (npc=5 keeps gennum). */
    fun decrementsGennumOnRetire(general: General): Boolean = getNpcType(general) != NPC_NO_GENNUM_DROP

    /** `getNPCType() == 9` — 이민족 (gates ⓞ-nation join). */
    fun isForeign(general: General): Boolean = getNpcType(general) == FOREIGN
}
