package opensamguk.gameapi.battle.realtime

import com.fasterxml.jackson.databind.ObjectMapper

/** Exact web v2 frames; no v1 slot conversion. */
class BattleV2WireCodec(private val mapper: ObjectMapper) {
    fun deploymentMove(text: String): BattleV2DeploymentMoveWire =
        throw IllegalArgumentException("v2 deployment frames are not implemented")
}
