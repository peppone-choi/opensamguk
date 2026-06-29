package opensamguk.infra.seed

import opensamguk.infra.persistence.MetaJson

/**
 * Decoded, position-resolved model of the two committed scenario resources used by the A-minimal
 * scenario-seed importer (F1a):
 *
 *  - `scenario/scenario_1010.json` — VERBATIM copy of `legacy/devsam-core/hwe/scenario/scenario_1010.json`
 *    (grand truth). Positional tuples decoded by the index maps below.
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

    // ── general[] / general_ex[] 14-slot positional index (PHP Scenario.php generateGeneral list()) ──
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

        val generals = ArrayList<ScenarioGeneral>()
        // general[] (npcType 2) THEN general_ex[] (npcType 2) — PHP build order. Both into `general`.
        for (raw in arr(root["general"])) generals.add(decodeGeneral(asList(raw)))
        for (raw in arr(root["general_ex"])) generals.add(decodeGeneral(asList(raw)))

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
            nations = nations,
            generals = generals,
            diplomacy = diplomacy,
        )
    }

    private fun decodeGeneral(t: List<Any?>): ScenarioGeneral = ScenarioGeneral(
        affinity = intOrNull(t.getOrNull(G_AFFINITY)),
        name = strOf(t[G_NAME]),
        picture = strOrNull(t.getOrNull(G_PICTURE)),
        nationId = intOf(t.getOrNull(G_NATION), 0),
        locatedCity = strOrNull(t.getOrNull(G_CITY)),
        leadership = intOf(t.getOrNull(G_LEADERSHIP), 0),
        strength = intOf(t.getOrNull(G_STRENGTH), 0),
        intel = intOf(t.getOrNull(G_INTEL), 0),
        officerLevel = intOf(t.getOrNull(G_OFFICER_LEVEL), 0),
        bornYear = intOrNull(t.getOrNull(G_BORN)),
        deadYear = intOrNull(t.getOrNull(G_DEAD)),
        ego = strOrNull(t.getOrNull(G_EGO)),
        special = strOrNull(t.getOrNull(G_CHAR)),
    )

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
}

/** Position-resolved scenario header + rosters. */
data class Scenario(
    val title: String,
    val startYear: Int,
    val map: Map<String, Any?> = emptyMap(),
    val const: Map<String, Any?> = emptyMap(),
    val nations: List<ScenarioNation>,
    val generals: List<ScenarioGeneral>,
    val diplomacy: List<ScenarioDiplomacy>,
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
