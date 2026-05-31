package opensamguk.logic.ai

import opensamguk.logic.ai.bfs.AiDistance
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.metaInt
import opensamguk.logic.util.phpToInt

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
 * @param ownGeneralId the acting general's id (PHP `$this->general->getID()`); `categorizeNationGeneral`
 *  EXCLUDES self (`SELECT no ... AND no != %i`, `:3516`) — the [generalsSupplier] returns the already-self-
 *  excluded snapshot, and this id is the assertion target for the no-self-leak guard.
 * @param generalsSupplier the self-excluded nation general snapshot (lazily fetched ONCE; materialized
 *  PK-ascending `.sortedBy { it.general.id }`). PHP `SELECT no FROM general WHERE nation=%i AND no!=%i`
 *  (`:3516`, NO `ORDER BY` → PK-ascending) → [AiGeneralView] per general carrying the bucketing scalars.
 * @param dipState the AI instance's diplomacy state ([AiInstanceState.dipState]) — the userWar/userCivil
 *  split's `dipState !== d평화` branch (`:3589`). The default [AiInstanceState.D_PEACE] is the FC1 no-op path.
 * @param minWarCrew the merged nation policy's `minWarCrew` (`:3590`) — the userWar crew threshold.
 * @param minNpcWarLeadership the merged nation policy's `minNPCWarLeadership` (`:3597`) — the npcWar gate.
 * @param turnTerm the env `turnterm` (`:3543/3585`) — `calcRecentWarTurn`'s `60*turnTerm` divisor.
 */
class AiWorldView(
    private val ownNationId: Int,
    private val cityRowsSupplier: () -> List<City>,
    private val warTargetNation: Map<Int, Int>?,
    private val ownGeneralId: Int = 0,
    private val generalsSupplier: () -> List<AiGeneralView> = { emptyList() },
    private val dipState: Int = AiInstanceState.D_PEACE,
    private val minWarCrew: Int = 0,
    private val minNpcWarLeadership: Int = 0,
    private val turnTerm: Int = 1,
) {
    /** Convenience ctor for an already-materialized snapshot (tests + the engine adapter). */
    constructor(
        ownNationId: Int,
        cityRows: List<City>,
        warTargetNation: Map<Int, Int>?,
        ownGeneralId: Int = 0,
        generals: List<AiGeneralView> = emptyList(),
        dipState: Int = AiInstanceState.D_PEACE,
        minWarCrew: Int = 0,
        minNpcWarLeadership: Int = 0,
        turnTerm: Int = 1,
    ) : this(
        ownNationId = ownNationId,
        cityRowsSupplier = { cityRows },
        warTargetNation = warTargetNation,
        ownGeneralId = ownGeneralId,
        generalsSupplier = { generals },
        dipState = dipState,
        minWarCrew = minWarCrew,
        minNpcWarLeadership = minNpcWarLeadership,
        turnTerm = turnTerm,
    )

    /** Secondary ctor that takes a [generalsSupplier] lambda (the lazy-once test path + the engine adapter). */
    constructor(
        ownNationId: Int,
        cityRows: List<City>,
        warTargetNation: Map<Int, Int>?,
        ownGeneralId: Int,
        generalsSupplier: () -> List<AiGeneralView>,
        dipState: Int,
        minWarCrew: Int,
        minNpcWarLeadership: Int,
        turnTerm: Int,
    ) : this(
        ownNationId = ownNationId,
        cityRowsSupplier = { cityRows },
        warTargetNation = warTargetNation,
        ownGeneralId = ownGeneralId,
        generalsSupplier = generalsSupplier,
        dipState = dipState,
        minWarCrew = minWarCrew,
        minNpcWarLeadership = minNpcWarLeadership,
        turnTerm = turnTerm,
    )

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

    // ====================================================================================================
    // categorizeNationGeneral (PHP `:3516-3613`) — the 9 buckets, the first-match-wins role ladder, NO draws.
    // ====================================================================================================

    // The PK-ascending self-excluded general snapshot, materialized ONCE on first need. PHP `:3516`
    // `SELECT no FROM general WHERE nation=%i AND no!=%i` (NO ORDER BY → PK-ascending; sorted explicitly).
    private val generalRows: List<AiGeneralView> by lazy {
        generalsSupplier().sortedBy { it.general.id }
    }

    // --- categorizeNationGeneral outputs (the 9 buckets; lazy-once via the userGenerals null sentinel) ---

    private var _nationGenerals: List<AiGeneralView>? = null
    private var _userGenerals: LinkedHashMap<Int, AiGeneralView>? = null
    private var _userCivilGenerals: LinkedHashMap<Int, AiGeneralView>? = null
    private var _userWarGenerals: LinkedHashMap<Int, AiGeneralView>? = null
    private var _lostGenerals: LinkedHashMap<Int, AiGeneralView>? = null
    private var _npcCivilGenerals: LinkedHashMap<Int, AiGeneralView>? = null
    private var _npcWarGenerals: LinkedHashMap<Int, AiGeneralView>? = null
    private var _troopLeaders: LinkedHashMap<Int, AiGeneralView>? = null
    private var _chiefGenerals: LinkedHashMap<Int, AiGeneralView>? = null

    /** PHP `$this->nationGenerals` (`:3604`) — ALL nation generals (self-excluded), PK-ascending. */
    val nationGenerals: List<AiGeneralView>
        get() { categorizeNationGeneral(); return _nationGenerals!! }

    /** PHP `$this->userGenerals` (`:3605`) — `npcType < 2` real-user / light-npc generals. */
    val userGenerals: LinkedHashMap<Int, AiGeneralView>
        get() { categorizeNationGeneral(); return _userGenerals!! }

    /** PHP `$this->userCivilGenerals` (`:3606`) — user generals NOT meeting the userWar split (`:3595`). */
    val userCivilGenerals: LinkedHashMap<Int, AiGeneralView>
        get() { categorizeNationGeneral(); return _userCivilGenerals!! }

    /** PHP `$this->userWarGenerals` (`:3607`) — user generals past the `lastWar+12` OR not-peace+crew gate. */
    val userWarGenerals: LinkedHashMap<Int, AiGeneralView>
        get() { categorizeNationGeneral(); return _userWarGenerals!! }

    /** PHP `$this->lostGenerals` (`:3608`) — in an unsupplied own city OR in a non-nation city (`:3568/3571`). */
    val lostGenerals: LinkedHashMap<Int, AiGeneralView>
        get() { categorizeNationGeneral(); return _lostGenerals!! }

    /** PHP `$this->npcCivilGenerals` (`:3609`) — `killturn<=5` OR low-leadership npc OR the default rung. */
    val npcCivilGenerals: LinkedHashMap<Int, AiGeneralView>
        get() { categorizeNationGeneral(); return _npcCivilGenerals!! }

    /** PHP `$this->npcWarGenerals` (`:3610`) — npc (≥2) with `getLeadership(false) >= minNPCWarLeadership`. */
    val npcWarGenerals: LinkedHashMap<Int, AiGeneralView>
        get() { categorizeNationGeneral(); return _npcWarGenerals!! }

    /** PHP `$this->troopLeaders` (`:3611`) — `npcType==5` OR the non-npc `che_집합` troop leader (`:3575/3577`). */
    val troopLeaders: LinkedHashMap<Int, AiGeneralView>
        get() { categorizeNationGeneral(); return _troopLeaders!! }

    /** PHP `$this->chiefGenerals` (`:3612`) — keyed by `officer_level` (>4), later-no-ascending OVERWRITES a dup. */
    val chiefGenerals: LinkedHashMap<Int, AiGeneralView>
        get() { categorizeNationGeneral(); return _chiefGenerals!! }

    /**
     * Faithful port of `categorizeNationGeneral` (PHP `:3516-3613`). Lazy-once (the `userGenerals === null`
     * sentinel `:3518`), NO draws. Mutates the FC1 [nationCities] `generals`/`important` BY REFERENCE — so
     * [categorizeNationCities] MUST run first (it does: [nationCities] forces it).
     *
     * Two passes over the PK-ascending self-excluded [generalRows]:
     *  - **Pass 1 (`:3541-3550`)** — `lastWar = min(calcRecentWarTurn)` over generals NOT skipped by the
     *    pre-임관 gate `recentWar >= (belong-1)*12` (`:3544`). `lastWar` seeds [Long.MAX_VALUE]-equivalent
     *    `PHP_INT_MAX` (`:3541`).
     *  - **Pass 2 (`:3552-3602`)** — officer weighting (chiefGenerals by level / important += per officer 2-4),
     *    then city-attach + lost detection, then the **first-match-wins role ladder** (`:3574-3601`).
     */
    fun categorizeNationGeneral() {
        if (_userGenerals != null) {
            return // PHP `:3518-3520` — the lazy-once null-guard (userGenerals as the sentinel).
        }

        // PHP `:3533-3534` — ensure cities exist first; the `&$this->nationCities` by-reference handle.
        val cities = nationCities // forces categorizeNationCities()

        val userGenerals = LinkedHashMap<Int, AiGeneralView>()
        val userCivilGenerals = LinkedHashMap<Int, AiGeneralView>()
        val userWarGenerals = LinkedHashMap<Int, AiGeneralView>()
        val lostGenerals = LinkedHashMap<Int, AiGeneralView>()
        val npcCivilGenerals = LinkedHashMap<Int, AiGeneralView>()
        val npcWarGenerals = LinkedHashMap<Int, AiGeneralView>()
        val troopLeaders = LinkedHashMap<Int, AiGeneralView>()
        val chiefGenerals = LinkedHashMap<Int, AiGeneralView>()

        val generals = generalRows // PK-ascending, self-excluded.

        // --- PASS 1 — lastWar (PHP `:3541-3550`) ---
        var lastWar = Long.MAX_VALUE // PHP `:3541` PHP_INT_MAX.
        for (gv in generals) {
            val recentWar = gv.calcRecentWarTurn(turnTerm) // :3543
            // :3544 임관전 전투는 제외 — skip battles fought before joining (recentWar >= (belong-1)*12).
            if (recentWar >= (gv.belong - 1) * 12) {
                continue
            }
            lastWar = minOf(lastWar, recentWar.toLong()) // :3549
        }

        // --- PASS 2 — bucketing (PHP `:3552-3602`) ---
        for (gv in generals) {
            val g = gv.general
            val generalID = g.id
            val cityID = g.cityId
            val npcType = g.npcType
            val officerLevel = g.officerLevel
            val officerCity = g.officerCity

            // PHP `:3559-3563` — officer weighting (BEFORE the role ladder; independent of it).
            if (officerLevel > 4) {
                chiefGenerals[officerLevel] = gv // :3560 keyed by LEVEL; later no-ascending OVERWRITES a dup.
            } else if (officerLevel >= 2) {
                cities[officerCity]?.let { it.important += 1 } // :3562 important += 1 on the officer_city.
            }

            // PHP `:3565-3572` — city-attach (by-ref into the FC1 generals map) + lost detection.
            val ownCity = cities[cityID]
            if (ownCity != null) {
                ownCity.generals[generalID] = gv // :3567 by-reference accumulation, insertion-ordered.
                if (ownCity.city.supplyState == 0) {
                    lostGenerals[generalID] = gv // :3568-3570 in own city but unsupplied → lost.
                }
            } else {
                lostGenerals[generalID] = gv // :3571 city not ours → lost.
            }

            // PHP `:3574-3601` — the FIRST-MATCH-WINS role ladder (REORDERING changes bucketing → draws).
            when {
                npcType == 5 -> {
                    troopLeaders[generalID] = gv // :3575 NPC 부대장.
                }
                g.troop == generalID && gv.reservedCommandName == "che_집합" -> {
                    troopLeaders[generalID] = gv // :3577 비 NPC 부대장 (troop===self AND reserved che_집합).
                }
                gv.killturn <= 5 -> {
                    npcCivilGenerals[generalID] = gv // :3579 near-deletion → 내정 NPC.
                }
                npcType < 2 -> {
                    userGenerals[generalID] = gv // :3582 real user / light-npc.
                    // :3585 recentWar <= lastWar+12. PHP promotes `PHP_INT_MAX + 12` to a FLOAT (no wraparound),
                    // so an unseeded lastWar makes the cutoff effectively unbounded → compare in Double (both
                    // operands ≤ ~12000 fit exactly; only the MAX_VALUE sentinel needs the float promotion).
                    when {
                        gv.calcRecentWarTurn(turnTerm).toDouble() <= lastWar.toDouble() + 12.0 -> {
                            userWarGenerals[generalID] = gv
                        }
                        dipState != AiInstanceState.D_PEACE && g.crew >= minWarCrew -> {
                            userWarGenerals[generalID] = gv // :3589-3590 not-peace AND crew >= minWarCrew.
                            // TODO(php :3592-3593): 훈련/사기 NOT yet factored — port as-is, do not add.
                        }
                        else -> {
                            userCivilGenerals[generalID] = gv // :3595.
                        }
                    }
                }
                gv.fullLeadership >= minNpcWarLeadership -> {
                    npcWarGenerals[generalID] = gv // :3597 getLeadership(false) >= minNPCWarLeadership.
                }
                else -> {
                    npcCivilGenerals[generalID] = gv // :3600 default rung.
                }
            }
        }

        // PHP `:3604-3612` — publish all buckets.
        _nationGenerals = generals
        _userGenerals = userGenerals
        _userCivilGenerals = userCivilGenerals
        _userWarGenerals = userWarGenerals
        _lostGenerals = lostGenerals
        _npcCivilGenerals = npcCivilGenerals
        _npcWarGenerals = npcWarGenerals
        _troopLeaders = troopLeaders
        _chiefGenerals = chiefGenerals
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
    val generals: LinkedHashMap<Int, AiGeneralView> = LinkedHashMap(),
)

/**
 * One nation general as the AI facade reads it in `categorizeNationGeneral` (PHP `:3552-3601`). Wraps the
 * domain [General] plus the three derived scalars the bucketing reads that are NOT plain columns:
 *  - [recentWarSeconds] — the engine-supplied `DateInterval` seconds between `recent_war` and `turntime`
 *    (PHP `General.php:286`); `null` means a falsy `recent_war` (the "never fought" marker). [calcRecentWarTurn]
 *    reproduces the 12000 sentinel + the `secDiff<=0 → 0` clamp + the `intdiv(toInt(secDiff), 60*turnTerm)`
 *    trunc-div (H-HELPERS §2 / `General.php:273-296`). The string→seconds mangle is the engine's job (the
 *    `:logic` layer stays PURE — same split as `war/WarUnitGeneral.kt`'s recent_war handling).
 *  - [reservedCommandName] — `getReservedTurn(0)->getName()` (PHP `:3577`), the turn_idx 0 reserved command
 *    name; `null` (= 휴식/none) when no reservation. Compared literally to `'che_집합'` for the non-NPC
 *    troop-leader rung.
 *  - [fullLeadership] — `getLeadership(false)` (PHP `:3597`), the no-injury full leadership (G8 flavor),
 *    compared to `minNPCWarLeadership` for the npcWar gate (the SAME value site `calcGenType` uses).
 *
 * `belong`/`killturn` ride [General.meta]; `crew`/`troop`/`npcType`/`officerLevel`/`officerCity`/`cityId`/`id`
 * are plain [General] fields. NO RNG draws.
 */
data class AiGeneralView(
    val general: General,
    val recentWarSeconds: Long?,
    val reservedCommandName: String?,
    val fullLeadership: Double,
) {
    /**
     * Faithful port of `General::calcRecentWarTurn(turnTerm)` (PHP `General.php:273-296`, H-HELPERS §2).
     * Falsy `recent_war` ([recentWarSeconds] null) → the **12000** sentinel (`12 * 1000`, `:280`); `secDiff<=0`
     * → 0 (`:288`); else `intdiv(toInt(secDiff), 60 * turnTerm)` (`:293`, BOTH trunc-toward-zero). The PHP
     * `calcCache` memo (per general per turnTerm) is unobservable from the bucketing — both call sites pass the
     * same `turnTerm`, so a pure recompute is byte-identical.
     */
    fun calcRecentWarTurn(turnTerm: Int): Int {
        val secDiff = recentWarSeconds ?: return 12 * 1000 // :279-282 falsy recent_war → 12000 sentinel.
        if (secDiff <= 0L) return 0 // :288-291 clamp to 0.
        // :293 intdiv(Util::toInt(secDiff), 60*turnTerm) — Util::toInt = trunc-toward-zero, intdiv = trunc.
        return phpToInt(secDiff.toDouble()) / (60 * turnTerm)
    }

    /** PHP `$nationGeneral->getVar('belong')` (`:3544`) — years belonging, rides `meta['belong']`. */
    val belong: Int get() = metaInt(general.meta, "belong")

    /** PHP `$nationGeneral->getVar('killturn')` (`:3579`) — 삭턴 counter, rides `meta['killturn']`. */
    val killturn: Int get() = metaInt(general.meta, "killturn")
}
