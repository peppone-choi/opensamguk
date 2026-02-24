package com.opensam.command.util

import com.opensam.entity.General

/**
 * Legacy parity: StaticEventHandler::handleEvent() from StaticEventHandler.php
 *
 * In legacy PHP, StaticEventHandler dispatches post-command events to registered
 * handler classes (configured in GameConst::$staticEventHandlers). These handlers
 * implement scenario-specific logic that triggers after certain commands.
 *
 * In the Kotlin backend, this pattern is implemented via the engine's EventService,
 * which fires events at various lifecycle points (pre-month, post-month, etc.).
 *
 * Command-specific post-execution hooks (like the ones in StaticEventHandler) are
 * handled by:
 *  1. The CommandResultApplicator processing the JSON delta
 *  2. The TurnService's post-command processing
 *  3. The EventService for scenario-triggered events
 *
 * Commands that need post-execution event handling should include appropriate
 * flags in their CommandResult.message JSON, which the applicator/engine will process.
 *
 * ### Events fired in legacy:
 * - After 출병 (attack): battle result events, item lottery
 * - After 천도 (capital move): nation static info refresh
 * - After 건국 (founding): nation creation events
 * - After various commands: stat change checks, item lottery
 *
 * ### Kotlin equivalent:
 * - `"battleTriggered": true` → engine processes war
 * - `"tryUniqueLottery": true` → engine runs unique item lottery
 * - `"refreshNationStaticInfo": true` → engine recalculates nation cached data
 * - `"inheritancePoint": {...}` → engine awards inheritance points
 * - `"checkStatChange": true` → engine checks stat level-ups
 */
object CommandEventHandler {
    // Event flag keys for CommandResult.message JSON
    const val BATTLE_TRIGGERED = "battleTriggered"
    const val TRY_UNIQUE_LOTTERY = "tryUniqueLottery"
    const val REFRESH_NATION_STATIC_INFO = "refreshNationStaticInfo"
    const val INHERITANCE_POINT = "inheritancePoint"
    const val CHECK_STAT_CHANGE = "checkStatChange"
    const val CITY_STATE_UPDATE = "cityStateUpdate"
}
