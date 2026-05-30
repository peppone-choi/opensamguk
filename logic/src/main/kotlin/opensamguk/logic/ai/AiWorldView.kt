package opensamguk.logic.ai

import opensamguk.logic.ai.bfs.AiDistance
import opensamguk.logic.domain.City

/**
 * F-FACADE — `AiWorldView`, a faithful port of the per-AI-instance read-only categorize/derive facade
 * of `legacy/devsam-core/hwe/sammo/GeneralAI.php` (GRAND TRUTH, read in full).
 *
 * Task FC1 ports `categorizeNationCities` (`:3469-3513`) and `calcWarRoute` (`:283-292`). Task FC2 adds
 * `categorizeNationGeneral` (the 9 buckets, by-ref into the city buckets). This class is PURE `:logic`
 * (no Spring/DB): the world is supplied as plain-data lookups (a PK-ascending `city` snapshot).
 *
 * ## NO draws
 * Both methods make ZERO RNG draws — they only materialize derived state + set candidate ORDER. The
 * bucket insertion order seeds the 발령/포상/promotion candidate lists in the leaf families.
 *
 * ## The per-field null-guards (decision #2)
 * `categorizeNationCities` (`nationCities !== null`, `:3472`) and `calcWarRoute` (`warRoute !== null`,
 * `:285`) are lazy-once: computed once, NEVER re-invalidated by a `reqUpdateInstance` flip. This is a
 * SEPARATE cache regime from [AiInstanceState]'s `reqUpdateInstance` flag — never coupled.
 *
 * ## PK-ascending HARD requirement (B5, R-FACADE §1)
 * The PHP query `SELECT * FROM city WHERE nation = %i` (`:3486`) has NO `ORDER BY`, so MariaDB returns
 * rows in clustered-PK (city id ascending) order. The Kotlin port MUST materialize the snapshot with
 * `.sortedBy { it.id }` (ascending) and build the four maps as `LinkedHashMap` in that exact order —
 * MariaDB-no-ORDER-BY ≠ Postgres/InMemory native order, so the explicit sort is NOT optional. A shuffled
 * InMemory insertion order still yields cityId-ascending buckets. The `LinkedHashMap`s are then walked by
 * `rng->choice(...)` (e.g. `do부대전방발령` `:337/:343/:349`) by INSERTION order; a different row order →
 * a different `choice` index → RNG desync.
 *
 * @param ownNationId the acting general's nation id (PHP `$this->nation['nation']`).
 * @param cityRowsSupplier the full `city` table snapshot (lazily fetched ONCE; materialized PK-ascending).
 * @param warTargetNation the `{nationID → 2|1}` war-target partition ([AiInstanceState.warTargetNation]) —
 *  `calcWarRoute` keys it (`array_keys`) then appends [ownNationId]. NULL → `?? []` → only [ownNationId].
 */
class AiWorldView(
    private val ownNationId: Int,
    private val cityRowsSupplier: () -> List<City>,
    private val warTargetNation: Map<Int, Int>?,
) {
    /** Convenience ctor for an already-materialized snapshot (tests + the engine adapter). */
    constructor(
        ownNationId: Int,
        cityRows: List<City>,
        warTargetNation: Map<Int, Int>?,
    ) : this(ownNationId, { cityRows }, warTargetNation)

    // The PK-ascending `city` snapshot, materialized ONCE on first need (categorizeNationCities or
    // calcWarRoute). PHP fetches fresh per method; here a single lazy snapshot serves both and stays
    // PK-ascending. NEVER re-sorted by anything else.
    private val cityRows: List<City> by lazy { cityRowsSupplier().sortedBy { it.id } }

    // --- categorizeNationCities outputs (the 4 buckets; lazy-once null-guard) ---

    private var _nationCities: LinkedHashMap<Int, NationCity>? = null

    /** PHP `$this->nationCities` (`:3506/3509`) — ALL nation cities, PK-ascending, keyed by cityId. */
    val nationCities: LinkedHashMap<Int, NationCity>
        get() {
            categorizeNationCities()
            return _nationCities!!
        }

    /** PHP `$this->supplyCities` (`:3498/3511`) — `supply` truthy, PK-ascending. */
    val supplyCities: LinkedHashMap<Int, NationCity>
        get() {
            categorizeNationCities()
            return _supplyCities!!
        }
    private var _supplyCities: LinkedHashMap<Int, NationCity>? = null

    /** PHP `$this->frontCities` (`:3501/3510`) — `front` truthy, PK-ascending. */
    val frontCities: LinkedHashMap<Int, NationCity>
        get() {
            categorizeNationCities()
            return _frontCities!!
        }
    private var _frontCities: LinkedHashMap<Int, NationCity>? = null

    /** PHP `$this->backupCities` (`:3503/3512`) — `supply` truthy AND NOT `front` (the `else if`), PK-ascending. */
    val backupCities: LinkedHashMap<Int, NationCity>
        get() {
            categorizeNationCities()
            return _backupCities!!
        }
    private var _backupCities: LinkedHashMap<Int, NationCity>? = null

    // --- calcWarRoute output (lazy-once null-guard) ---

    private var _warRoute: Map<Int, Map<Int, Int>>? = null

    /** PHP `$this->warRoute` (`:291`) — `searchAllDistanceByNationList([warTargets..., ownNation], false)`. */
    val warRoute: Map<Int, Map<Int, Int>>
        get() {
            calcWarRoute()
            return _warRoute!!
        }

    /**
     * Faithful port of `categorizeNationCities` (PHP `:3469-3513`). Lazy-once null-guard (`:3472`),
     * NO draws. Builds the four `LinkedHashMap` buckets in PK-ascending order. Cities are built BEFORE
     * generals (FC2's `categorizeNationGeneral` mutates `nationCities[*].generals`/`.important` by
     * reference, so the city maps MUST exist first).
     */
    fun categorizeNationCities() {
        if (_nationCities != null) {
            return // PHP `:3472-3474` — the lazy-once null-guard.
        }

        val nationCities = LinkedHashMap<Int, NationCity>()
        val supplyCities = LinkedHashMap<Int, NationCity>()
        val frontCities = LinkedHashMap<Int, NationCity>()
        val backupCities = LinkedHashMap<Int, NationCity>()

        // PHP `:3486` `SELECT * FROM city WHERE nation = %i` (NO ORDER BY → PK-ascending; materialized above).
        for (row in cityRows) {
            if (row.nationId != ownNationId) continue

            // PHP `:3489-3491` — dev = sum(agri,comm,secu,def,wall) / sum(*_max). Double float division.
            val statSum = (row.agriculture + row.commerce + row.security + row.defense + row.wall).toDouble()
            val statMaxSum =
                (row.agricultureMax + row.commerceMax + row.securityMax + row.defenseMax + row.wallMax).toDouble()
            val dev = statSum / statMaxSum

            val nationCity = NationCity(
                cityId = row.id,
                city = row,
                dev = dev,
                important = 1,                       // PHP `:3495` `$nationCity['important'] = 1;`
                generals = LinkedHashMap(),          // PHP `:3487` `new \ArrayObject()` — filled by-ref in FC2.
            )

            // PHP `:3497-3506` — the bucket partition (order: supply, then front/else-if-backup, then all).
            if (row.supplyState != 0) {
                supplyCities[row.id] = nationCity
            }
            if (row.frontState != 0) {
                frontCities[row.id] = nationCity
            } else if (row.supplyState != 0) {
                backupCities[row.id] = nationCity
            }
            nationCities[row.id] = nationCity
        }

        _nationCities = nationCities
        _supplyCities = supplyCities
        _frontCities = frontCities
        _backupCities = backupCities
    }

    /**
     * Faithful port of `calcWarRoute` (PHP `:283-292`). Lazy-once null-guard (`:285`), NO draws.
     * `target = array_keys(warTargetNation ?? []); target[] = ownNation` → the `[warTargets..., ownNation]`
     * APPEND order is a tie-ordering parity target (the BFS row order seeds downstream tie ordering).
     * Forwards to [AiDistance.searchAllDistanceByNationList] with `suppliedCityOnly=false` (PHP `:291`).
     */
    fun calcWarRoute() {
        if (_warRoute != null) {
            return // PHP `:285-287` — the lazy-once null-guard.
        }
        _warRoute = AiDistance.searchAllDistanceByNationList(
            linkNationList = warRouteNationList(),
            cityRows = cityRows,
            suppliedCityOnly = false,
        )
    }

    /**
     * The nation list `calcWarRoute` threads (PHP `:288-289`): `array_keys(warTargetNation ?? [])` THEN
     * append [ownNationId]. Exposed so the `[warTargets..., ownNation]` append order is itself assertable.
     */
    fun warRouteNationList(): List<Int> {
        val target = ArrayList<Int>(warTargetNation?.keys ?: emptyList())
        target.add(ownNationId) // PHP `:289` `$target[] = $this->nation['nation'];`
        return target
    }
}

/**
 * One nation city as the AI facade materializes it (PHP `$nationCity` map: the `city` row enriched with
 * `dev`/`important`/`generals`). The `generals` list is filled by-ref in FC2's `categorizeNationGeneral`
 * (`$nationCities[$cityID]['generals'][$generalID] = ...`); `important` accumulates there too (per officer
 * 2-4), so both are `var`/mutable. Insertion order is the candidate order downstream `rng->choice` walks.
 *
 * @param cityId the city PK (PHP `$nationCity['city']`).
 * @param city the underlying [City] row (the AI reads its stats/level/supply/front).
 * @param dev the development ratio (PHP `:3489-3491`).
 * @param important the importance counter, seeded 1 (PHP `:3495`), `+= 1` per officer 2-4 in FC2.
 * @param generals the `{generalID → general}` accumulator, filled by-ref in FC2 (insertion-ordered).
 */
data class NationCity(
    val cityId: Int,
    val city: City,
    val dev: Double,
    var important: Int,
    val generals: LinkedHashMap<Int, Any?> = LinkedHashMap(),
)
