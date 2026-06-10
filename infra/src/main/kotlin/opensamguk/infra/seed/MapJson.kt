package opensamguk.infra.seed

import opensamguk.infra.persistence.MetaJson

/**
 * 맵 표시 데이터 디코더 — `map/<code>.json`(che/cr/miniche/chess/…) 런타임 리소스를 읽는다.
 *
 * 좌표는 TS 레거시(devsam-core2026) 맵을 가로세로 ×10/7 비례 확대한 **표시 전용** 값
 * (네이티브 700×500 → 1000×714). 마커(고정 px)가 상대적으로 작아져 도시 겹침이 완화된다.
 * 게임플레이/골든 패러티와 무관하다 — 도시 스탯/연결/레벨은 `CityConst`(Kotlin) 소관이고,
 * 이 디코더는 맵 표시에 필요한 `width/height` + `id → (name, x, y)`만 다룬다.
 */
object MapJson {

    data class MapData(val width: Int, val height: Int, val cities: List<MapCityCoord>)

    data class MapCityCoord(val id: Int, val name: String, val x: Double, val y: Double)

    /**
     * 클래스패스의 `map/<code>.json` 리소스를 읽어 디코드한다 — MapPreview/GetConst가 공유하는
     * 단일 로더(리소스 read + 파싱 중복 금지). 리소스 부재 시 빈 MapData(0×0) — graceful, 날조 없음.
     */
    fun loadFromClasspath(mapCode: String): MapData {
        val json = MapJson::class.java.classLoader.getResourceAsStream("map/$mapCode.json")
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
            MapCityCoord(id = id, name = c["name"] as? String ?: "", x = x, y = y)
        }
        return MapData(width = width, height = height, cities = cities)
    }
}
