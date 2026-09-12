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
        /** Stable physical identity used to bind version-scoped runtime policy. */
        val physicalPlaceRef: String? = null,
        /** Stable route-node identity; required by han-world-v3 policy rows. */
        val routeNodeKey: String? = null,
        /**
         * `meta.nameCh` — 그 城 자신의 행정 단위가 붙은 원 표기("长安县" · "甘陵郡" · "伯濟國").
         * 끝 글자가 县/縣 이면 그 城 은 縣 이다. 화면 표기를 「뭐뭐현」으로 통일하는 데 쓴다
         * (web/shared/src/iso/cityName.ts). 소속 郡의 治所 이름인 `meta.seat` 과 다른 값이다.
         *
         * **맨 끝에 둔다** — 이 data class 는 위치 인자로 부르는 시험이 있어서(game-engine
         * SpatialSupplyNetworkWiringTest 등) 가운데에 끼우면 x·y 가 밀려 컴파일이 깨진다.
         */
        val nameCh: String? = null,
        /**
         * `meta.displayName` — 화면·로그에 적을 이름("경조윤 장안현"). [name] 은 식별자다.
         *
         * 「보이는 이름으로 통일하란 말이야」(2026-09-12). 생성기가 계산해 지도 JSON 에 싣는다
         * (tools/scenario/build_han_world.py display_name + 전역 충돌 해소). 클라이언트가
         * 규칙만으로는 못 내는 표기가 있어서(같은 郡 안 同音異字 縣 6 곳) 값을 실어 보낸다.
         *
         * **맨 끝에 둔다** — [nameCh] 와 같은 이유로 위치 인자 시험이 밀린다.
         */
        val displayName: String? = null,
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
        /**
         * `meta.displayName` — 화면·로그에 적을 이름("장안현"). [name] 은 식별자다("장안(京兆尹)").
         *
         * 「로그와 맵의 현 이름을 같게 만들어」(2026-09-11). 값은 생성기가 계산해 지도 JSON 에
         * 싣는다(tools/scenario/build_han_world.py display_name). 없는 맵(che·han·han-780)은
         * null 이고 그때는 [name] 을 그대로 쓴다.
         *
         * **맨 끝에 둔다** — MapCityCoord.nameCh 와 같은 이유로 위치 인자 시험이 밀린다.
         */
        val displayName: String? = null,
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
                nameCh = (meta?.get("nameCh") as? String)?.takeIf { it.isNotBlank() },
                x = x,
                y = y,
                regionName = (meta?.get("ju") as? String)?.takeIf { it.isNotBlank() },
                commanderyName = (meta?.get("jun") as? String)?.takeIf { it.isNotBlank() },
                isCommanderySeat = meta?.get("isSeat") == true,
                provinceId = intOrNull(c["provinceId"]),
                physicalPlaceRef = (c["physicalPlaceRef"] as? String)?.takeIf { it.isNotBlank() }
                    ?: (c["physicalPlaceId"] as? String)?.takeIf { it.isNotBlank() }
                        ?.let { "chgis:v6:cnty:$it" },
                routeNodeKey = (c["routeNodeKey"] as? String)?.takeIf { it.isNotBlank() },
                displayName = (meta?.get("displayName") as? String)?.takeIf { it.isNotBlank() },
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
                displayName = ((c["meta"] as? Map<*, *>)?.get("displayName") as? String)
                    ?.takeIf { it.isNotBlank() },
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
