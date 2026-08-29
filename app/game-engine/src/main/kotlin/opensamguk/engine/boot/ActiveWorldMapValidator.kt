package opensamguk.engine.boot

import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.world.ActiveWorldMap

object ActiveWorldMapValidator {
    fun validate(snapshot: WorldSnapshot) {
        val variant = ActiveWorldMap.requireVariant(snapshot.state.config, snapshot.state.meta)
        val persistedIds = snapshot.cities.mapTo(linkedSetOf()) { it.id }
        check(persistedIds == variant.all().keys) {
            "worldId=${snapshot.worldId.value} mapName=${variant.mapName} persisted city ids do not match variant"
        }
        snapshot.generals.firstOrNull { it.cityId > 0 && it.cityId !in persistedIds }?.let {
            error("worldId=${snapshot.worldId.value} generalId=${it.id} has unresolved cityId=${it.cityId}")
        }
        snapshot.nations.firstOrNull {
            it.capitalCityId != null && it.capitalCityId > 0 && it.capitalCityId !in persistedIds
        }?.let {
            error("worldId=${snapshot.worldId.value} nationId=${it.id} has unresolved capitalCityId=${it.capitalCityId}")
        }
    }
}
