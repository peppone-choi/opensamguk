package opensamguk.gameapi.dto

/**
 * World-map snapshot for the gateway lobby `MapPreview` (P7 read API).
 *
 * The gateway renders city dots (colored by owning nation) over the `che` base-map image. Field names
 * are a CLIENT CONTRACT — the gateway TS client is built against this exact shape; do not rename.
 *
 *  - `mapCode`/`width`/`height` describe the base image (`che` = 700×500 native px).
 *  - `cities[].nationId` is LIVE ownership (changes as the game runs); `nationId == 0` is neutral and has
 *    NO entry in `nations[]` (the client renders neutral with a default color).
 *  - `cities[].x`/`y` are display coords merged from the committed `scenario/cities_1010.json` (NOT in the
 *    `city` table); a city whose id is absent from the JSON is OMITTED from `cities` (no coord = nothing to draw).
 */
data class MapPreviewResponse(
    val serverName: String,
    val year: Int,
    val month: Int,
    val mapCode: String,
    val width: Int,
    val height: Int,
    val cities: List<MapPreviewCity>,
    val nations: List<MapPreviewNation>,
)

data class MapPreviewCity(
    val id: Int,
    val name: String,
    val level: Int,
    val nationId: Int,
    val x: Int,
    val y: Int,
)

data class MapPreviewNation(
    val id: Int,
    val name: String,
    /** Hex color e.g. "#c62828", taken VERBATIM from the live `nation.color` column. */
    val color: String,
)
