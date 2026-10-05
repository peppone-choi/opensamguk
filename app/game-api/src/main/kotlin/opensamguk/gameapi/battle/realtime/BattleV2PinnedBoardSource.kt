package opensamguk.gameapi.battle.realtime

import opensamguk.logic.battle.realtime.BattleSide
import opensamguk.logic.battle.realtime.Battlefield
import opensamguk.logic.battle.realtime.TacticalV2Cell

/** Independently pinned rules and spawn zones; never derive these from the incoming ticket payload. */
interface BattleV2PinnedBoardSource {
    val ruleSha256: String
    fun spawnCellsForBoard(board: Battlefield): Map<BattleSide, Set<TacticalV2Cell>>?
}
