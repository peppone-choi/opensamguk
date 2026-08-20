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

    fun requireVariant(config: Map<String, Any?>, meta: Map<String, Any?>): CityConstVariant =
        CityConstRegistry.of(requireName(config, meta))
}
