package com.opensam.command.util

/**
 * Legacy parity: tryUniqueItemLottery() from func.php
 *
 * This is a marker/documentation utility. The actual lottery logic lives in
 * [com.opensam.engine.UniqueLotteryService] which implements the full probability
 * calculation, item selection, and assignment.
 *
 * Commands signal that a unique lottery should be attempted by including
 * `"tryUniqueLottery": true` in their CommandResult.message JSON.
 *
 * The [com.opensam.command.CommandResultApplicator] detects this flag and delegates
 * to the engine's UniqueLotteryService to perform the actual roll.
 *
 * ### When unique lottery fires (legacy parity):
 * - 출병 (attack): after successful battle — `tryUniqueLottery: true`
 * - 이동 (move): on move completion — `tryUniqueLottery: true`
 * - 강행 (forced march): on move completion — `tryUniqueLottery: true`
 * - 숙련전환 (crew type switch): on completion — `tryUniqueLottery: true`
 * - 인재탐색 (talent search): on finding someone
 * - 등용 (recruitment): on success
 * - Various other commands that award items
 *
 * ### Conditions checked by UniqueLotteryService:
 * - General must not be NPC (npcState < 2)
 * - Must have an available item slot
 * - Probability based on player count, scenario, item type count
 * - Year-based maximum unique item limits
 * - Inherit random unique override (probability = 1.0)
 */
object UniqueItemLotteryUtil {
    /**
     * JSON key used in CommandResult.message to signal lottery attempt.
     */
    const val JSON_KEY = "tryUniqueLottery"
}
