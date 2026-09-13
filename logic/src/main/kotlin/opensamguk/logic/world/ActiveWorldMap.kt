package opensamguk.logic.world

object ActiveWorldMap {
    fun requireName(config: Map<String, Any?>, meta: Map<String, Any?>): String {
        val mapName = sequenceOf(
            config["mapName"],
            (config["map"] as? Map<*, *>)?.get("mapName"),
            config["map"],
            meta["mapName"],
            (meta["map"] as? Map<*, *>)?.get("mapName"),
            meta["map"],
        ).filterIsInstance<String>().firstOrNull { it.isNotBlank() }
            ?: error("world state requires an explicit mapName in config/meta")

        requireNotNull(CityConstRegistry.find(mapName)) {
            "world state has unknown mapName: $mapName"
        }
        return mapName
    }

    fun requireVariant(
        config: Map<String, Any?>,
        meta: Map<String, Any?>,
        hanWorldVariant: HanWorldVariant? = null,
    ): CityConstVariant {
        val name = requireName(config, meta)
        if (hanWorldVariant == null) return CityConstRegistry.of(name)
        require(name == HAN_WORLD_V3_MAP_NAME) { "Historical Han runtime identity requires han-world-v3" }
        return CityConstRegistry.hanWorld(hanWorldVariant)
    }
}
