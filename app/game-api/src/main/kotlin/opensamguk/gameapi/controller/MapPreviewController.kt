package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.MapPreviewCity
import opensamguk.gameapi.dto.MapPreviewNation
import opensamguk.gameapi.dto.MapPreviewResponse
import opensamguk.gameapi.read.CityReadRepository
import opensamguk.gameapi.read.NationReadRepository
import opensamguk.gameapi.read.WorldStateReadRepository
import opensamguk.infra.seed.ScenarioJson
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

    /** che base-map native pixel dimensions (the gateway scales the dot layer to these). */
    private val cheWidth = 700
    private val cheHeight = 500
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
        if (world == null) {
            return MapPreviewResponse(
                serverName = "",
                year = 0,
                month = 0,
                mapCode = defaultMapCode,
                width = cheWidth,
                height = cheHeight,
                cities = emptyList(),
                nations = emptyList(),
            )
        }

        // serverName: prefer a config/meta-supplied scenario title, else the scenario_code, else a default.
        val serverName = (world.config["title"] ?: world.meta["title"] ?: world.config["serverName"])
            ?.toString()?.takeIf { it.isNotBlank() }
            ?: world.scenarioCode.takeIf { it.isNotBlank() }
            ?: defaultMapCode

        // coord/name merge: id → (name, x, y) from the committed scenario resource.
        val coords = loadCityCoords()

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
            mapCode = world.scenarioCode.substringBefore("_").takeIf { it.isNotBlank() } ?: defaultMapCode,
            width = cheWidth,
            height = cheHeight,
            cities = cities,
            nations = nations,
        )
    }

    /** Decode `scenario/cities_1010.json` (committed, on the classpath via `:infra`) into id → coord. */
    private fun loadCityCoords(): Map<Int, CityCoord> {
        val json = javaClass.classLoader.getResourceAsStream(CITIES_RESOURCE)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: return emptyMap()
        return ScenarioJson.loadCities(json)
            .mapNotNull { c ->
                val x = c.x ?: return@mapNotNull null
                val y = c.y ?: return@mapNotNull null
                c.id to CityCoord(name = c.name, x = x, y = y)
            }
            .toMap()
    }

    private data class CityCoord(val name: String, val x: Int, val y: Int)

    companion object {
        private const val CACHE_TTL_MS = 600_000L // 10 minutes
        private const val CITIES_RESOURCE = "scenario/cities_1010.json"
    }
}
