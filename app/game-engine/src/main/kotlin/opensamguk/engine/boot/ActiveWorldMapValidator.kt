package opensamguk.engine.boot

import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.world.ActiveWorldMap
import opensamguk.logic.input.RuleProfile

object ActiveWorldMapValidator {
    fun validate(snapshot: WorldSnapshot) {
        // Location authority spec §2.3: every live general must have a position and a
        // province binding. This validator is the default for every product DB load.
        if (snapshot.state.ruleProfile == RuleProfile.HWIHA) {
            val positions = requireNotNull(snapshot.generalPositionSnapshot) { "HWIHA world has no general position snapshot" }
            val missing = snapshot.generals.filter { positions.stateFor(it.id) == null }.map { it.id }
            require(missing.isEmpty()) { "HWIHA world: generals without a position row: $missing" }
            val unbound = snapshot.generals.filter { it.cityId !in snapshot.cityLandProvinceById }.map { it.id to it.cityId }
            require(unbound.isEmpty()) { "HWIHA world: reference cities without a province binding: $unbound" }
        }
        val variant = ActiveWorldMap.requireVariant(snapshot.state.config, snapshot.state.meta, snapshot.state.hanWorldVariant)
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
