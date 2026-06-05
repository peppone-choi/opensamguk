package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.MapPreviewCity
import opensamguk.gameapi.dto.MapPreviewNation
import opensamguk.gameapi.dto.MapPreviewResponse
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.seed.MapJson
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * P7 read endpoint — `GET /api/map/preview`. Serves a per-server world-map snapshot for the gateway
 * lobby `MapPreview` (city dots colored by owning nation, over the `che` base image).
 *
 * **Data sources** (matching the field contract in [MapPreviewResponse]):
 *  - cities `id`/`level`/`nationId`: LIVE from [CityReadRepository] (ownership changes as the game runs);
 *  - cities `name`/`x`/`y`: merged by city-id from the committed `scenario/cities_1010.json` resource
 *    (those fields are NOT in the `city` table). `ScenarioJson.loadCities` is the SAME reusable decoder
 *    the F1 seed importer uses — game-api sees the resource via its `:infra` dependency. A city whose id
 *    has no coord row is OMITTED (nothing to draw);
 *  - nations `id`/`name`/`color`: LIVE from [NationReadRepository] (`nation.color` hex, verbatim);
 *  - `serverName`/`year`/`month`/`mapCode`: from the singleton [WorldStateReadRepository] row.
 *
 * **Caching.** The assembled response is cached for 10 minutes in a tiny manual holder (volatile snapshot
 * + epoch-millis stamp; recompute when older than [CACHE_TTL_MS]). Per-process is fine — single server,
 * single daemon. We DON'T pull in Spring Cache / Caffeine: neither is a declared game-api dependency,
 * and a static lobby preview needs nothing fancier than a double-checked volatile.
 *
 * **Public access.** game-api has NO Spring Security on the classpath (no `SecurityFilterChain`), so this
 * is open by default — matching the other plain `@RestController`s ([AuctionController], [DiplomacyController]).
 *
 * **Empty-world safety.** If `world_state` is unseeded, returns 200 with empty cities/nations + year/month 0
 * (never 500) so the gateway can show a placeholder.
 */
@RestController
@RequestMapping("/api/map")
class MapPreviewController(
    private val cityReadRepository: CityReadRepository,
    private val nationReadRepository: NationReadRepository,
    private val worldStateReadRepository: WorldStateReadRepository,
) {

    /** 시나리오가 맵을 특정하지 못할 때의 기본 맵 코드. dims/coords는 `map/<code>.json`에서 읽는다
     *  (하드코딩 X) — 좌표는 가로세로 ×10/7 비례 확대된 표시 전용 값(1000×714, 마커 겹침 완화). */
    private val defaultMapCode = "che"

    // ── manual 10-minute cache (volatile snapshot + epoch stamp) ──
    @Volatile private var cached: MapPreviewResponse? = null
    @Volatile private var cachedAtMs: Long = 0L

    @GetMapping("/preview")
    fun preview(): ResponseEntity<MapPreviewResponse> {
        val now = System.currentTimeMillis()
        val snapshot = cached
        if (snapshot != null && now - cachedAtMs < CACHE_TTL_MS) {
            return ResponseEntity.ok(snapshot)
        }
        val fresh = synchronized(this) {
            val again = cached
            if (again != null && System.currentTimeMillis() - cachedAtMs < CACHE_TTL_MS) {
                again
            } else {
                val built = build()
                cached = built
                cachedAtMs = System.currentTimeMillis()
                built
            }
        }
        return ResponseEntity.ok(fresh)
    }

    private fun build(): MapPreviewResponse {
        // world clock — the singleton row. Unseeded ⇒ empty snapshot (year/month 0), never 500.
        val world = worldStateReadRepository.findAll().firstOrNull()

        // 맵 표시 데이터(dims + id→좌표)를 map/<code>.json에서 읽는다 — 백엔드가 dims/coords를 하드코딩하지
        // 않는다. (시나리오→맵 매핑은 후속; 현재는 defaultMapCode=che. 8종 맵 리소스가 모두 준비돼 있다.)
        val mapCode = defaultMapCode
        val mapData = loadMapData(mapCode)

        if (world == null) {
            return MapPreviewResponse(
                serverName = "",
                year = 0,
                month = 0,
                mapCode = mapCode,
                width = mapData.width,
                height = mapData.height,
                cities = emptyList(),
                nations = emptyList(),
            )
        }

        // serverName: prefer a config/meta-supplied scenario title, else the scenario_code, else a default.
        val serverName = (world.config["title"] ?: world.meta["title"] ?: world.config["serverName"])
            ?.toString()?.takeIf { it.isNotBlank() }
            ?: world.scenarioCode.takeIf { it.isNotBlank() }
            ?: defaultMapCode

        // coord/name merge: id → (name, x, y) from the map/<code>.json resource.
        val coords = mapData.cities.associateBy { it.id }

        // 수도 city id 집합(nation.capital_city_id) — 수도 별 아이콘(event51) 판정용.
        val allNations = nationReadRepository.findAll()
        val capitalIds = allNations.mapNotNull { it.capitalCityId }.toSet()

        val cities = cityReadRepository.findAll()
            .mapNotNull { city ->
                val coord = coords[city.id] ?: return@mapNotNull null // no coord → nothing to draw
                MapPreviewCity(
                    id = city.id,
                    name = coord.name,
                    level = city.level,
                    nationId = city.nationId,
                    x = coord.x,
                    y = coord.y,
                    state = city.frontState,
                    supply = city.supplyState != 0,
                    isCapital = city.id in capitalIds,
                )
            }
            .sortedBy { it.id }

        val nations = allNations
            .filter { it.id != 0 } // neutral (nationId 0) has no entry
            .map { MapPreviewNation(id = it.id, name = it.name, color = it.color) }
            .sortedBy { it.id }

        return MapPreviewResponse(
            serverName = serverName,
            year = world.currentYear,
            month = world.currentMonth,
            mapCode = mapCode,
            width = mapData.width,
            height = mapData.height,
            cities = cities,
            nations = nations,
        )
    }

    /** Decode `map/<code>.json` (committed, on the classpath via `:infra`) → 표시 dims + id→좌표.
     *  리소스가 없으면 빈 맵(0×0, 도시 없음) → gateway가 placeholder를 그린다. */
    private fun loadMapData(mapCode: String): MapJson.MapData {
        val json = javaClass.classLoader.getResourceAsStream("map/$mapCode.json")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return MapJson.MapData(width = 0, height = 0, cities = emptyList())
        return MapJson.loadMap(json)
    }

    companion object {
        private const val CACHE_TTL_MS = 600_000L // 10 minutes
    }
}
