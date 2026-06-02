package opensamguk.gameapi.dto

/**
 * F2 Wave 1 DTOs — the read contracts web/game Wave 2 builds against. Field names are STABLE; serialized
 * with Jackson default (camelCase). All identity endpoints resolve the caller's general from the verified
 * JWT principal; the 404/empty shapes for "no character" are documented per-endpoint on the controllers.
 */

// ── possession (장수 점유) ────────────────────────────────────────────────────

/** A claimable (unowned NPC) candidate for GET /api/generals/claimable. */
data class ClaimableGeneral(
    val generalId: Int,
    val name: String,
    val nationId: Int,
    val nationName: String?,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val officerLevel: Int,
    val picture: String?,
    val imageServer: Int,
)

/** GET /api/generals/claimable body. */
data class ClaimableResponse(
    val result: Boolean,
    val hasGeneral: Boolean,
    val candidates: List<ClaimableGeneral>,
)

/** POST /api/general/claim body (and response). */
data class ClaimRequest(val generalId: Int)

data class ClaimResponse(
    val result: Boolean,
    val generalId: Int?,
    val reason: String?,
)

// ── front-info (§3 GameInfo + identity envelope) ─────────────────────────────

data class FrontGlobalInfo(
    val year: Int,
    val month: Int,
    val turnterm: Int,
    val scenario: String,
    val scenarioText: String,
    val generalCount: Int,
    val nationCount: Int,
    val cityCount: Int,
    val npcCount: Int,
)

/** The caller's general gating surface (null when no character). */
data class FrontGeneralInfo(
    val hasGeneral: Boolean,
    val generalId: Int?,
    val name: String?,
    val nationId: Int,
    val officerLevel: Int,
    val permission: Int,
    val showSecret: Boolean,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val injury: Int,
    val gold: Int,
    val rice: Int,
    val crew: Int,
    val cityId: Int,
)

data class FrontNationInfo(
    val id: Int,
    val name: String,
    val color: String,
    val level: Int,
    val gold: Int,
    val rice: Int,
    val tech: Double,
    val capitalCityId: Int?,
)

data class FrontCityInfo(
    val id: Int,
    val name: String,
    val level: Int,
    val nationId: Int,
    val region: Int,
    val population: Int,
    val populationMax: Int,
    val agriculture: Int,
    val agricultureMax: Int,
    val commerce: Int,
    val commerceMax: Int,
    val security: Int,
    val securityMax: Int,
    val defense: Int,
    val defenseMax: Int,
    val wall: Int,
    val wallMax: Int,
    val trust: Double,
    val trade: Int?,
)

/** GET /api/front-info body. `general.hasGeneral=false` (others null) when the caller has no character. */
data class FrontInfoResponse(
    val result: Boolean,
    val global: FrontGlobalInfo,
    val general: FrontGeneralInfo,
    val nation: FrontNationInfo?,
    val city: FrontCityInfo?,
    val recentRecord: List<String>,
)

// ── my-* read endpoints ──────────────────────────────────────────────────────

/** GET /api/my-page — the caller's general detail (404 when no character). */
data class MyPageResponse(
    val generalId: Int,
    val name: String,
    val nationId: Int,
    val nationName: String?,
    val cityId: Int,
    val cityName: String?,
    val officerLevel: Int,
    val permission: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val injury: Int,
    val experience: Int,
    val dedication: Int,
    val gold: Int,
    val rice: Int,
    val crew: Int,
    val train: Int,
    val atmos: Int,
    val picture: String?,
    val imageServer: Int,
)

/** GET /api/my-generals — generals in the caller's nation (the caller is always included). */
data class MyGeneralSummary(
    val generalId: Int,
    val name: String,
    val cityId: Int,
    val officerLevel: Int,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val crew: Int,
    val npcState: Int,
    /** True iff this is the caller's own general. Named `mine` (not `isMe`) so Jackson keeps the field name. */
    val mine: Boolean,
)

data class MyGeneralsResponse(
    val result: Boolean,
    val nationId: Int,
    val generals: List<MyGeneralSummary>,
)

/** GET /api/my-cities — cities owned by the caller's nation. */
data class MyCitySummary(
    val cityId: Int,
    val name: String,
    val level: Int,
    val region: Int,
    val population: Int,
    val populationMax: Int,
    val defense: Int,
    val wall: Int,
)

data class MyCitiesResponse(
    val result: Boolean,
    val nationId: Int,
    val cities: List<MyCitySummary>,
)

/** GET /api/my-boss — the ruler (officer_level 12) of the caller's nation (인사부). */
data class MyBossResponse(
    val result: Boolean,
    val nationId: Int,
    val hasBoss: Boolean,
    val bossGeneralId: Int?,
    val bossName: String?,
    val bossOfficerLevel: Int?,
)

/** GET /api/my-nation-detail — the caller's nation, fuller surface (404 shape if no nation). */
data class MyNationDetailResponse(
    val result: Boolean,
    val hasNation: Boolean,
    val nation: FrontNationInfo?,
    val cityCount: Int,
    val generalCount: Int,
)

// ── global-menu (§4 server-driven typed union) ───────────────────────────────

/**
 * Server-driven menu union (GlobalMenu.php parity). `type` discriminates: "item" | "split" | "multi"
 * | "line". Optional fields are present per type; the client filters via condShowVar/condHighlightVar
 * against globalInfo (see spec §4 filterMenu).
 */
data class MenuNode(
    val type: String,
    val name: String? = null,
    val url: String? = null,
    val newTab: Boolean? = null,
    val funcCall: String? = null,
    val icon: String? = null,
    val condHighlightVar: String? = null,
    val condShowVar: String? = null,
    val main: MenuNode? = null,
    val subMenu: List<MenuNode>? = null,
)

data class GlobalMenuResponse(
    val result: Boolean,
    val version: Int,
    val menu: List<MenuNode>,
)

// ── const (cached singleton) ─────────────────────────────────────────────────

/** GET /api/const — server constants the client caches as a module singleton. */
data class GameConstResponse(
    val result: Boolean,
    val mapName: String,
    val mapWidth: Int,
    val mapHeight: Int,
    val maxTurn: Int,
    val officerLevelText: Map<Int, String>,
)
