package opensamguk.infra.seed

import opensamguk.infra.persistence.MetaJson

object MapJson {

    /** Resolve logical map aliases to the committed gameplay resource name. */
    fun resourceCode(mapCode: String): String =
        if (mapCode == "han-world-v2") "han" else mapCode

    data class MapData(val width: Int, val height: Int, val cities: List<MapCityCoord>)

    data class MapCityCoord(
        val id: Int,
        val name: String,
        val x: Double,
        val y: Double,
        val regionName: String? = null,
        val commanderyName: String? = null,
        val isCommanderySeat: Boolean = false,
        /** Canonical han-tiles provinceRecords array index. Null means not yet historically adjudicated. */
        val provinceId: Int? = null,
    )

    data class MapCityDetail(
        val id: Int,
        val name: String,
        val level: Int,
        val region: Int,
        val x: Double?,
        val y: Double?,
        val populationMax: Int,
        val agricultureMax: Int,
        val commerceMax: Int,
        val securityMax: Int,
        val defenceMax: Int,
        val wallMax: Int,
        val populationInit: Int?,
        val agricultureInit: Int?,
        val commerceInit: Int?,
        val securityInit: Int?,
        val defenceInit: Int?,
        val wallInit: Int?,
        val connections: List<Int>,
    )

    /**
     * 클래스패스의 `map/<code>.json` 리소스를 읽어 디코드한다 — MapPreview/GetConst가 공유하는
     * 단일 로더(리소스 read + 파싱 중복 금지). 리소스 부재 시 빈 MapData(0×0) — graceful, 날조 없음.
     */
    fun loadFromClasspath(mapCode: String): MapData {
        val resourceCode = resourceCode(mapCode)
        val json = MapJson::class.java.classLoader.getResourceAsStream("map/$resourceCode.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return MapData(width = 0, height = 0, cities = emptyList())
        return loadMap(json)
    }

    fun loadMap(json: String): MapData {
        val root = MetaJson.decode(json)
        val width = (root["width"] as? Number)?.toInt() ?: 0
        val height = (root["height"] as? Number)?.toInt() ?: 0
        val cities = (root["cities"] as? List<*> ?: emptyList<Any?>()).mapNotNull { raw ->
            val c = raw as? Map<*, *> ?: return@mapNotNull null
            val id = (c["id"] as? Number)?.toInt() ?: return@mapNotNull null
            val x = (c["x"] as? Number)?.toDouble() ?: return@mapNotNull null
            val y = (c["y"] as? Number)?.toDouble() ?: return@mapNotNull null
            val meta = c["meta"] as? Map<*, *>
            MapCityCoord(
                id = id,
                name = c["name"] as? String ?: "",
                x = x,
                y = y,
                regionName = (meta?.get("ju") as? String)?.takeIf { it.isNotBlank() },
                commanderyName = (meta?.get("jun") as? String)?.takeIf { it.isNotBlank() },
                isCommanderySeat = meta?.get("isSeat") == true,
                provinceId = intOrNull(c["provinceId"]),
            )
        }
        return MapData(width = width, height = height, cities = cities)
    }

    fun loadCityDetailsFromClasspath(mapCode: String): List<MapCityDetail> {
        val resourceCode = resourceCode(mapCode)
        val json = MapJson::class.java.classLoader.getResourceAsStream("map/$resourceCode.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return emptyList()
        return loadCityDetails(json)
    }

    fun loadCityDetails(json: String): List<MapCityDetail> {
        val root = MetaJson.decode(json)
        return (root["cities"] as? List<*> ?: emptyList<Any?>()).mapNotNull { raw ->
            val c = raw as? Map<*, *> ?: return@mapNotNull null
            val id = intOrNull(c["id"]) ?: return@mapNotNull null
            val name = c["name"] as? String ?: return@mapNotNull null
            val max = c["max"] as? Map<*, *> ?: return@mapNotNull null
            val initial = c["initial"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            MapCityDetail(
                id = id,
                name = name,
                level = intOrNull(c["level"]) ?: return@mapNotNull null,
                region = intOrNull(c["region"]) ?: 0,
                x = doubleOrNull(c["x"]),
                y = doubleOrNull(c["y"]),
                populationMax = intOrNull(max["population"]) ?: 0,
                agricultureMax = intOrNull(max["agriculture"]) ?: 0,
                commerceMax = intOrNull(max["commerce"]) ?: 0,
                securityMax = intOrNull(max["security"]) ?: 0,
                defenceMax = intOrNull(max["defence"]) ?: 0,
                wallMax = intOrNull(max["wall"]) ?: 0,
                populationInit = intOrNull(initial["population"]),
                agricultureInit = intOrNull(initial["agriculture"]),
                commerceInit = intOrNull(initial["commerce"]),
                securityInit = intOrNull(initial["security"]),
                defenceInit = intOrNull(initial["defence"]),
                wallInit = intOrNull(initial["wall"]),
                connections = (c["connections"] as? List<*>)
                    ?.mapNotNull { intOrNull(it) }
                    ?: emptyList(),
            )
        }
    }

    private fun intOrNull(value: Any?): Int? = when (value) {
        is Int -> value
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private fun doubleOrNull(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }
}
