package opensamguk.infra.seed

import opensamguk.infra.persistence.MetaJson

/**
 * Decoded, position-resolved model of the two committed scenario resources used by the A-minimal
 * scenario-seed importer (F1a):
 *
 *  - `scenario/scenario_1010.json` — VERBATIM copy of `legacy/devsam-core/hwe/scenario/scenario_1010.json`
 *    (frozen historical baseline (ADR-LITE-042; not current product authority)). Positional tuples decoded by the index maps below.
 *  - `scenario/cities_1010.json` — the 24-city A-minimal subset transcribed from `CityConstBase::$initCity`
 *    (== `common` `CityConst.cheInitCity`), already x100-scaled (see [ScenarioCity]).
 *
 * **Parity discipline.** This is the A-minimal (non-strict) seed: it makes ZERO RNG draws. Every value is
 * taken VERBATIM from the JSON or derived deterministically. Fields PHP would RNG-draw (affinity/personality
 * when null, city placement, killturn, turn-time jitter, nation type when null) are NOT drawn here — they are
 * the B-parity boundary documented in `docs/superpowers/research/2026-06-02-F1-scenario-seed-spec.md` §5.
 *
 * Parsing reuses [MetaJson] (the project's hand-rolled, insertion-order-preserving JSON codec — no Jackson /
 * kotlinx dependency added). `MetaJson.decode` yields a [LinkedHashMap] tree with [ArrayList] arrays and
 * Int/Long/Double/String/Boolean leaves; the loaders below walk that tree by position.
 */
object ScenarioJson {

    // ── general[] / general_ex[] positional index (PHP 14 slots + local/RTK14 extensions) ──
    // [0]affinity [1]name [2]picturePath [3]nation_id(0/1/2) [4]locatedCity(null in 1010)
    // [5]leadership(통솔) [6]strength(무력) [7]intel(지력) [8]officerLevel [9]born [10]dead
    // [11]ego(성격) [12]char(특기) [13]text(NPC 대사)
    private const val G_AFFINITY = 0
    private const val G_NAME = 1
    private const val G_PICTURE = 2
    private const val G_NATION = 3
    private const val G_CITY = 4
    private const val G_LEADERSHIP = 5
    private const val G_STRENGTH = 6
    private const val G_INTEL = 7
    private const val G_OFFICER_LEVEL = 8
    private const val G_BORN = 9
    private const val G_DEAD = 10
    private const val G_EGO = 11
    private const val G_CHAR = 12
    private const val G_TEXT = 13
    private const val G_POLITICS = 14
    private const val G_CHARM = 15
    private const val G_APPEARANCE_YEAR = 16
    private const val G_OFFICER_NUMBER = 17
    private const val G_GENDER = 18
    private const val G_LIFESPAN = 19
    private const val G_ACTIVITY_YEARS = 20
    private const val G_TOTAL = 21
    private const val G_IDEOLOGY = 22
    private const val G_RTK14_ADDED = 23
    private const val G_LEGACY_ACTIVE_AT_START = 24

    // ── nation[] 9-tuple positional index (PHP) ──
    // [0]name [1]color [2]gold [3]rice [4]desc [5]tech [6]ideology [7]scale(level) [8]cities[]
    private const val N_NAME = 0
    private const val N_COLOR = 1
    private const val N_GOLD = 2
    private const val N_RICE = 3
    private const val N_DESC = 4
    private const val N_TECH = 5
    private const val N_IDEOLOGY = 6
    private const val N_SCALE = 7
    private const val N_CITIES = 8

    /** Decode `scenario_1010.json` into the position-resolved [Scenario] model. */
    fun loadScenario(json: String): Scenario {
        val root = MetaJson.decode(json)
        val title = strOrNull(root["title"]) ?: ""
        val startYear = intOf(root["startYear"], 0)
        val map = stringMap(root["map"])
        val const = stringMap(root["const"])
        val iconPath = strOrNull(root["iconPath"]) ?: "."
        val storedIcons = stringMap(root["stored_icons"] ?: root["storedIcons"])
        val events = arr(root["events"]).map { decodeEvent(asList(it)) }
        val initialEvents = arr(root["initialEvents"]).map { decodeInitialEvent(asList(it)) }
        val ignoreDefaultEvents = boolOf(root["ignoreDefaultEvents"], false)
        val seedContract = root["seedContract"]?.let(::decodeSeedContract)

        val nations = arr(root["nation"]).mapIndexed { idx, raw ->
            val t = asList(raw)
            // PHP assigns nation ids 1-based by array order (index 0 of the array == nation id 1).
            ScenarioNation(
                id = idx + 1,
                name = strOf(t[N_NAME]),
                color = strOf(t[N_COLOR]),
                gold = intOf(t[N_GOLD], 0),
                rice = intOf(t[N_RICE], 0),
                desc = strOrNull(t[N_DESC]) ?: "",
                tech = intOf(t[N_TECH], 0),
                ideology = strOrNull(t[N_IDEOLOGY]),
                scale = intOf(t[N_SCALE], 0),
                cities = arr(t.getOrNull(N_CITIES)).map { strOf(it) },
            )
        }
        val nationIdsByToken = LinkedHashMap<Any?, Int>()
        nationIdsByToken[0] = 0
        nationIdsByToken["0"] = 0
        nationIdsByToken["재야"] = 0
        for (nation in nations) {
            nationIdsByToken[nation.id] = nation.id
            nationIdsByToken[nation.id.toString()] = nation.id
            nationIdsByToken[nation.name] = nation.id
        }

        val baseGenerals = arr(root["general"]).map { decodeGeneral(asList(it), nationIdsByToken, npcType = 2) }
        val generalEx = arr(root["general_ex"]).map { decodeGeneral(asList(it), nationIdsByToken, npcType = 2) }
        val generalNeutral = arr(root["general_neutral"]).map { decodeGeneral(asList(it), nationIdsByToken, npcType = 6) }

        // diplomacy[]: [me, you, state, remainMonths]. Empty in 1010, but decoded for completeness.
        val diplomacy = arr(root["diplomacy"]).map {
            val t = asList(it)
            ScenarioDiplomacy(
                me = intOf(t.getOrNull(0), 0),
                you = intOf(t.getOrNull(1), 0),
                state = intOf(t.getOrNull(2), 2),
                remainMonths = intOf(t.getOrNull(3), 0),
            )
        }

        return Scenario(
            title = title,
            startYear = startYear,
            map = map,
            const = const,
            iconPath = iconPath,
            storedIcons = storedIcons,
            nations = nations,
            generals = baseGenerals + generalEx + generalNeutral,
            baseGenerals = baseGenerals,
            generalEx = generalEx,
            generalNeutral = generalNeutral,
            diplomacy = diplomacy,
            events = events,
            initialEvents = initialEvents,
            ignoreDefaultEvents = ignoreDefaultEvents,
            seedContract = seedContract,
        )
    }

    private fun decodeSeedContract(value: Any?): ScenarioSeedContract {
        val contract = asMap(value)
        val active = asMap(contract["activeGenerals"])
        val base = intRequired(active["base"], "seedContract.activeGenerals.base")
        val extended = intRequired(active["extended"], "seedContract.activeGenerals.extended")
        require(base >= 0 && extended >= base) {
            "seedContract active general counts must satisfy 0 <= base <= extended"
        }
        return ScenarioSeedContract(
            activeGenerals = ActiveGeneralContract(
                base = base,
                extended = extended,
            ),
        )
    }

    private fun decodeEvent(t: List<Any?>): ScenarioEvent {
        require(t.size >= 3) { "scenario event must contain target, priority, and condition: $t" }
        val target = t[0] as? String ?: error("scenario event target must be a string: ${t[0]}")
        val priority = intRequired(t[1], "scenario event priority")
        return ScenarioEvent(target, priority, t[2], t.drop(3))
    }

    private fun decodeInitialEvent(t: List<Any?>): ScenarioInitialEvent {
        require(t.isNotEmpty()) { "scenario initial event must contain a condition" }
        return ScenarioInitialEvent(t[0], t.drop(1))
    }

    private fun decodeGeneral(
        t: List<Any?>,
        nationIdsByToken: Map<Any?, Int>,
        npcType: Int,
    ): ScenarioGeneral {
        val rawTuple = if (t.size >= 14) t.toList() else t + List(14 - t.size) { null }
        return ScenarioGeneral(
            affinity = intOrNull(t.getOrNull(G_AFFINITY)),
            name = strOf(t[G_NAME]),
            picture = strOrNull(t.getOrNull(G_PICTURE)),
            nationId = nationIdsByToken[t.getOrNull(G_NATION)]
                ?: intOf(t.getOrNull(G_NATION), 0),
            locatedCity = strOrNull(t.getOrNull(G_CITY)),
            leadership = intOf(t.getOrNull(G_LEADERSHIP), 0),
            strength = intOf(t.getOrNull(G_STRENGTH), 0),
            intel = intOf(t.getOrNull(G_INTEL), 0),
            officerLevel = intOf(t.getOrNull(G_OFFICER_LEVEL), 0),
            bornYear = intOrNull(t.getOrNull(G_BORN)),
            deadYear = intOrNull(t.getOrNull(G_DEAD)),
            ego = strOrNull(t.getOrNull(G_EGO)),
            special = strOrNull(t.getOrNull(G_CHAR)),
            text = strOrNull(t.getOrNull(G_TEXT)),
            politics = intOf(t.getOrNull(G_POLITICS), 50),
            charm = intOf(t.getOrNull(G_CHARM), 50),
            appearanceYear = intOrNull(t.getOrNull(G_APPEARANCE_YEAR)),
            officerNumber = intOrNull(t.getOrNull(G_OFFICER_NUMBER)),
            gender = strOrNull(t.getOrNull(G_GENDER)),
            lifespan = intOrNull(t.getOrNull(G_LIFESPAN)),
            activityYears = intOrNull(t.getOrNull(G_ACTIVITY_YEARS)),
            total = intOrNull(t.getOrNull(G_TOTAL)),
            ideology = strOrNull(t.getOrNull(G_IDEOLOGY)),
            rtk14Added = boolOf(t.getOrNull(G_RTK14_ADDED), false),
            legacyActiveAtStart = boolOrNull(t.getOrNull(G_LEGACY_ACTIVE_AT_START), "legacyActiveAtStart"),
            npcType = npcType,
            rawTuple = rawTuple,
        )
    }

    /** Decode `cities_1010.json` (che 풀맵 94도시). Stats are already x100-scaled. */
    fun loadCities(json: String): List<ScenarioCity> {
        val root = MetaJson.decode(json)
        return arr(root["cities"]).map {
            val c = asMap(it)
            ScenarioCity(
                id = intOf(c["id"], 0),
                name = strOf(c["name"]),
                level = intOf(c["level"], 0),
                region = intOf(c["region"], 0),
                nationId = intOf(c["nation_id"], 0),
                popMax = intOf(c["pop_max"], 0),
                agriMax = intOf(c["agri_max"], 0),
                commMax = intOf(c["comm_max"], 0),
                secuMax = intOf(c["secu_max"], 0),
                defMax = intOf(c["def_max"], 0),
                wallMax = intOf(c["wall_max"], 0),
                x = intOrNull(c["x"]),
                y = intOrNull(c["y"]),
                // 공백지 베이스 초기스탯(CityConstBase). 점령지는 JSON에 없어 null → importer가 ratio70(*_max).
                popInit = intOrNull(c["pop_init"]),
                agriInit = intOrNull(c["agri_init"]),
                commInit = intOrNull(c["comm_init"]),
                secuInit = intOrNull(c["secu_init"]),
                defInit = intOrNull(c["def_init"]),
                wallInit = intOrNull(c["wall_init"]),
            )
        }
    }

    fun loadMapCities(json: String): List<ScenarioCity> =
        MapJson.loadCityDetails(json).map { c ->
            ScenarioCity(
                id = c.id,
                name = c.name,
                level = c.level,
                region = c.region,
                nationId = 0,
                popMax = c.populationMax,
                agriMax = c.agricultureMax,
                commMax = c.commerceMax,
                secuMax = c.securityMax,
                defMax = c.defenceMax,
                wallMax = c.wallMax,
                x = c.x?.toInt(),
                y = c.y?.toInt(),
                popInit = c.populationInit,
                agriInit = c.agricultureInit,
                commInit = c.commerceInit,
                secuInit = c.securityInit,
                defInit = c.defenceInit,
                wallInit = c.wallInit,
            )
        }

    // ── small typed accessors over the MetaJson tree ──
    private fun arr(v: Any?): List<Any?> = when (v) {
        null -> emptyList()
        is List<*> -> v
        else -> error("expected JSON array, got ${v::class}")
    }

    @Suppress("UNCHECKED_CAST")
    private fun asMap(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: error("expected JSON object, got $v")

    private fun stringMap(v: Any?): Map<String, Any?> {
        val raw = v as? Map<*, *> ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in raw) {
            if (key is String) out[key] = value
        }
        return out
    }

    private fun asList(v: Any?): List<Any?> = v as? List<Any?> ?: error("expected JSON tuple, got $v")

    private fun strOf(v: Any?): String = v?.toString() ?: error("expected non-null string")

    private fun strOrNull(v: Any?): String? = v?.toString()

    private fun intOf(v: Any?, default: Int): Int = when (v) {
        null -> default
        is Int -> v
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: default
        else -> default
    }

    private fun intOrNull(v: Any?): Int? = when (v) {
        null -> null
        is Int -> v
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    private fun intRequired(v: Any?, label: String): Int = when (v) {
        is Int -> v
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: error("$label must be an integer: $v")
        else -> error("$label must be an integer: $v")
    }

    private fun boolOf(v: Any?, default: Boolean): Boolean = when (v) {
        null -> default
        is Boolean -> v
        is String -> v.toBooleanStrictOrNull() ?: default
        else -> default
    }

    private fun boolOrNull(v: Any?, label: String): Boolean? = when (v) {
        null -> null
        is Boolean -> v
        else -> error("$label must be a boolean: $v")
    }
}

/** Position-resolved scenario header + rosters. */
data class Scenario(
    val title: String,
    val startYear: Int,
    val map: Map<String, Any?> = emptyMap(),
    val const: Map<String, Any?> = emptyMap(),
    val iconPath: String = ".",
    val storedIcons: Map<String, Any?> = emptyMap(),
    val nations: List<ScenarioNation>,
    val generals: List<ScenarioGeneral>,
    val baseGenerals: List<ScenarioGeneral> = generals,
    val generalEx: List<ScenarioGeneral> = emptyList(),
    val generalNeutral: List<ScenarioGeneral> = emptyList(),
    val diplomacy: List<ScenarioDiplomacy>,
    val events: List<ScenarioEvent> = emptyList(),
    val initialEvents: List<ScenarioInitialEvent> = emptyList(),
    val ignoreDefaultEvents: Boolean = false,
    val seedContract: ScenarioSeedContract? = null,
) {
    fun seedGenerals(extendedGeneral: Boolean): List<ScenarioGeneral> {
        validateRtk14AddedPlacement()
        val legacyBase = baseGenerals.filterNot { it.rtk14Added }
        val selectedExtended = generalEx.filter { extendedGeneral || it.officerNumber != null }
        val legacyNeutral = generalNeutral.filterNot { it.rtk14Added }
        return legacyBase + selectedExtended + legacyNeutral + baseGenerals.filter { it.rtk14Added }
    }

    fun initGenerals(): List<ScenarioGeneral> = seedGenerals(extendedGeneral = true)

    fun isSourceProvenancedGeneralEx(general: ScenarioGeneral): Boolean =
        general.officerNumber != null && generalEx.any { it === general }

    private fun validateRtk14AddedPlacement() {
        require(generalEx.none { it.rtk14Added }) {
            "RTK14-added rows in general_ex are unsupported because they would alter InitScenario ordering"
        }
        require(generalNeutral.none { it.rtk14Added }) {
            "RTK14-added rows in general_neutral are unsupported because they would alter InitScenario ordering"
        }
    }
}

data class ScenarioSeedContract(val activeGenerals: ActiveGeneralContract)

data class ActiveGeneralContract(val base: Int, val extended: Int)

data class ScenarioEvent(
    val target: String,
    val priority: Int,
    val condition: Any?,
    val actions: List<Any?>,
)

data class ScenarioInitialEvent(
    val condition: Any?,
    val actions: List<Any?>,
)

data class ScenarioNation(
    val id: Int,
    val name: String,
    val color: String,
    val gold: Int,
    val rice: Int,
    val desc: String,
    val tech: Int,
    /** PHP ideology (e.g. 유가/태평도); `che_` is prefixed at import unless it already contains `_`. */
    val ideology: String?,
    /** PHP `scale` == opensamguk nation `level`. */
    val scale: Int,
    /** City NAMES owned by this nation (resolved to ids at import). */
    val cities: List<String>,
)

data class ScenarioGeneral(
    val affinity: Int?,
    val name: String,
    val picture: String?,
    /** 0 = neutral (재야); 1/2 = faction. Only {0,1,2} appear in 1010. */
    val nationId: Int,
    /** null for ALL generals in 1010 — city placement is an A-deterministic derivation. */
    val locatedCity: String?,
    val leadership: Int,
    val strength: Int,
    val intel: Int,
    val officerLevel: Int,
    val bornYear: Int?,
    val deadYear: Int?,
    /** 성격 (e.g. 유지/안전/은둔); maps to `che_<ego>` personal_code when known. */
    val ego: String?,
    /** 특기 (null for almost all 1010 rows). */
    val special: String?,
    val text: String? = null,
    val politics: Int = 50,
    val charm: Int = 50,
    val appearanceYear: Int? = null,
    val officerNumber: Int? = null,
    val gender: String? = null,
    val lifespan: Int? = null,
    val activityYears: Int? = null,
    val total: Int? = null,
    val ideology: String? = null,
    val rtk14Added: Boolean = false,
    val legacyActiveAtStart: Boolean? = null,
    val npcType: Int = 2,
    val rawTuple: List<Any?> = emptyList(),
)

data class ScenarioDiplomacy(
    val me: Int,
    val you: Int,
    val state: Int,
    val remainMonths: Int,
)

/**
 * One A-minimal city. `*Max` stats are the CityConstBase base values with the `_generate()` x100 scaling
 * ALREADY applied (so the JSON value equals the `city.*_max` column). `x`/`y` are client-display coords and
 * are NOT persisted in the `city` table.
 */
data class ScenarioCity(
    val id: Int,
    val name: String,
    val level: Int,
    val region: Int,
    val nationId: Int,
    val popMax: Int,
    val agriMax: Int,
    val commMax: Int,
    val secuMax: Int,
    val defMax: Int,
    val wallMax: Int,
    val x: Int?,
    val y: Int?,
    // 공백지(nation_id=0) 베이스 초기 스탯(CityConstBase). 점령지는 null → importer가 ratio70(*_max).
    val popInit: Int? = null,
    val agriInit: Int? = null,
    val commInit: Int? = null,
    val secuInit: Int? = null,
    val defInit: Int? = null,
    val wallInit: Int? = null,
)
