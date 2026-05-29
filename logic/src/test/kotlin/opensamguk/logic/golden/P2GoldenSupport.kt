package opensamguk.logic.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.common.rng.serializeSeed
import opensamguk.logic.actions.GeneralActionResolveContext
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.LastTurn
import opensamguk.logic.domain.Nation
import opensamguk.logic.domain.WorldEnv
import kotlin.test.assertEquals

/**
 * AREA GATE-RUNTIME — Task GR2 shared support for the per-command PHP-golden byte gate.
 *
 * The golden capture (GR1) is the BYTE ORACLE. Each fixture file (golden/p2/<command>-fixtures.json)
 * carries, per case: the exact 6-component per-action `seedString`, the shared `hiddenSeed`, the env
 * (year/startYear/month/develCost), the captured `before`/`after` general+city subset (gold/rice/crew/
 * train/atmos/experience/dedication/intel_exp/explevel/nation/officer_level/city/max_domestic_critical +
 * city comm/agri/pop/comm_max/agri_max/trust/nation), the ordered acting `logLines`, the `broadcastLines`
 * (pushGlobalActionLog), and an optional `destGenerals` array (a second general's after-state + logLines).
 *
 * The capture snapshot does NOT dump leadership/strength/intel (they are unchanged by every P2 non-combat
 * command, so the GR1 snapshot omits them). The three captured actors and their two dest generals resolve
 * to EXACT scenario_1010 template stats (legacy/devsam-core/hwe/scenario/scenario_1010.json — `setStat`
 * takes the template L/S/I verbatim, no install RNG for explicit-stat scenario generals). These are pinned
 * here as documented fixture inputs (the P1 golden test pinned general no.76's stats the same way from the
 * DB dump). NONE of the visible captured state depends on them being anything other than the install truth:
 *
 *   gid 8   = 고승   (nation 2 황건적, city 19 허창)  L/S/I = 42 / 73 / 24
 *   gid 11  = 공손범 (neutral, city 32 평양)          L/S/I = 61 / 67 / 61
 *   gid 152 = 하진   (nation 1 후한,  city 74 낙양)   L/S/I = 49 / 69 / 37
 *   gid 14  = 공융   (nation 1 후한, 발령 dest)         L/S/I = 63 / 48 / 85  (specialDomestic 경작 — but
 *             발령 is a pure relocate; no stat-stack fold touches the captured after-state)
 *   gid 9   = 공도   (nation 2 황건적, 증여 dest)        L/S/I = 26 / 73 / 19
 */
object P2GoldenSupport {

    /** scenario_1010 template stats for every captured actor/dest general (raw L/S/I, unchanged by P2). */
    data class RawStat(val leadership: Int, val strength: Int, val intel: Int)

    val RAW_STATS: Map<Int, RawStat> = mapOf(
        8 to RawStat(42, 73, 24),
        11 to RawStat(61, 67, 61),
        152 to RawStat(49, 69, 37),
        14 to RawStat(63, 48, 85),
        9 to RawStat(26, 73, 19),
    )

    /** Nation 1 후한 capital = 낙양 (cityId 1); nation 2 황건적 capital = 업 (cityId 50). */
    fun capitalOf(nationId: Int): Int = when (nationId) {
        1 -> 1
        2 -> 50
        else -> 0
    }

    /** scenario_1010 nation tech (recruit cost reads it): 후한 1500, 황건적 500. */
    fun techOf(nationId: Int): Double = when (nationId) {
        1 -> 1500.0
        2 -> 500.0
        else -> 0.0
    }

    /** scenario_1010 nation display name (general.name / nation.name carry no install transform). */
    fun nationNameOf(nationId: Int): String = when (nationId) {
        1 -> "후한"
        2 -> "황건적"
        else -> ""
    }

    /**
     * Install display names (general.name = NPC-type prefix `ⓝ` + scenario name) for every captured
     * actor/dest general, extracted from the GR1 golden `<Y>…</>` log tokens (the byte oracle).
     */
    val NAMES: Map<Int, String> = mapOf(
        8 to "ⓝ고승",
        9 to "ⓝ공도",
        11 to "ⓝ공손범",
        14 to "ⓝ공융",
        152 to "ⓝ하진",
    )

    fun nameOf(gid: Int): String = NAMES[gid] ?: ""

    // ---- fixture DTOs (mirror the GR1 capture shape) ----

    data class Fixture(val command: String, val hiddenSeed: String, val cases: List<Case>)

    data class Case(
        val name: String,
        val command: String,
        val ctor: String,            // "general" | "nation"
        val scope: String,           // "generalCommand" | "nationCommand"
        val generalId: Int,
        val cityId: Int,
        val env: Env,
        val arg: Map<String, Any?>?,
        val seedString: String,
        val reqGold: Int?,
        val before: StatePair,
        val after: StatePair,
        val logLines: List<String>,
        val broadcastLines: List<String>,
        val destGenerals: List<DestGeneral>,
    )

    data class Env(val year: Int, val startYear: Int, val month: Int, val develCost: Int)

    /** The captured general+city subset (raw column names from the PHP dump). */
    data class GState(val raw: Map<String, JsonPrimitive>) {
        fun int(k: String): Int = raw.getValue(k).int
        fun intOrNull(k: String): Int? = raw[k]?.intOrNull
        fun double(k: String): Double = raw.getValue(k).double
        fun has(k: String): Boolean = raw.containsKey(k)
    }

    data class StatePair(val general: GState, val city: GState)

    data class DestGeneral(val generalId: Int, val after: StatePair, val logLines: List<String>)

    fun load(command: String): Fixture {
        val text = P2GoldenSupport::class.java.classLoader
            .getResourceAsStream("golden/p2/$command-fixtures.json")!!
            .readBytes().toString(Charsets.UTF_8)
        val root = Json.parseToJsonElement(text).jsonObject
        val hiddenSeed = root["hiddenSeed"]!!.jsonPrimitive.content
        val cmd = root["command"]!!.jsonPrimitive.content
        val cases = root["cases"]!!.jsonArray.map { el -> parseCase(el.jsonObject) }
        return Fixture(cmd, hiddenSeed, cases)
    }

    private fun parseCase(o: JsonObject): Case {
        val e = o["env"]!!.jsonObject
        return Case(
            name = o["case"]!!.jsonPrimitive.content,
            command = o["command"]!!.jsonPrimitive.content,
            ctor = o["ctor"]!!.jsonPrimitive.content,
            scope = o["scope"]!!.jsonPrimitive.content,
            generalId = o["generalId"]!!.jsonPrimitive.int,
            cityId = o["cityId"]!!.jsonPrimitive.int,
            env = Env(
                year = e["year"]!!.jsonPrimitive.int,
                startYear = e["startYear"]!!.jsonPrimitive.int,
                month = e["month"]!!.jsonPrimitive.int,
                develCost = e["develCost"]!!.jsonPrimitive.int,
            ),
            arg = o["arg"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonObject?.let { parseArg(it) },
            seedString = o["seedString"]!!.jsonPrimitive.content,
            reqGold = o["reqGold"]?.jsonPrimitive?.intOrNull,
            before = parseStatePair(o["before"]!!.jsonObject),
            after = parseStatePair(o["after"]!!.jsonObject),
            logLines = o["logLines"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            broadcastLines = o["broadcastLines"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
            destGenerals = o["destGenerals"]?.jsonArray?.map { d ->
                val dg = d.jsonObject
                DestGeneral(
                    generalId = dg["generalId"]!!.jsonPrimitive.int,
                    after = parseStatePair(dg["after"]!!.jsonObject),
                    logLines = dg["logLines"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                )
            } ?: emptyList(),
        )
    }

    private fun parseArg(o: JsonObject): Map<String, Any?> = o.mapValues { (_, v) ->
        val p = v.jsonPrimitive
        when {
            p.booleanOrNull != null -> p.boolean
            p.intOrNull != null -> p.int
            else -> p.content
        }
    }

    private fun parseStatePair(o: JsonObject): StatePair = StatePair(
        general = GState(o["general"]!!.jsonObject.mapValues { it.value.jsonPrimitive }),
        city = GState(o["city"]!!.jsonObject.mapValues { it.value.jsonPrimitive }),
    )

    // ---- entity construction from a captured state + the pinned raw stats ----

    /** Build a General from a captured GState + the scenario raw L/S/I for [gid]. */
    fun generalFrom(gid: Int, gs: GState, extraMeta: Map<String, Any?> = emptyMap()): General {
        val rs = RAW_STATS.getValue(gid)
        val meta = linkedMapOf<String, Any?>(
            "explevel" to gs.int("explevel"),
            "intel_exp" to gs.int("intel_exp"),
            "max_domestic_critical" to gs.double("max_domestic_critical"),
            "name" to nameOf(gid),
        )
        meta.putAll(extraMeta)
        return General(
            id = gid,
            nationId = gs.int("nation"),
            cityId = gs.int("city"),
            leadership = rs.leadership,
            strength = rs.strength,
            intel = rs.intel,
            injury = 0,
            experience = gs.double("experience"),
            dedication = gs.double("dedication"),
            officerLevel = gs.int("officer_level"),
            gold = gs.int("gold"),
            rice = gs.intOrNull("rice") ?: 0,
            crew = gs.intOrNull("crew") ?: 0,
            train = gs.raw["train"]?.double ?: 0.0,
            atmos = gs.raw["atmos"]?.double ?: 0.0,
            meta = meta,
        )
    }

    /** Build a City from a captured GState. */
    fun cityFrom(cityId: Int, gs: GState): City = City(
        id = cityId,
        nationId = gs.int("nation"),
        // city level is not in the dump; the captured commands never read it into a visible result, and
        // the constraint suite is not exercised here (resolve is driven directly). Default 8 (max).
        level = 8,
        commerce = gs.int("comm"), commerceMax = gs.int("comm_max"),
        agriculture = gs.int("agri"), agricultureMax = gs.int("agri_max"),
        supplyState = 1,
        frontState = 0,
        trust = gs.double("trust"),
        population = gs.intOrNull("pop") ?: 0,
        populationMax = gs.intOrNull("pop") ?: 0,
    )

    fun nationFrom(c: Case, gold: Int = 0, rice: Int = 0, tech: Double = 0.0): Nation {
        val nationId = c.before.general.int("nation")
        return Nation(
            id = nationId,
            level = if (nationId == 0) 0 else 2,
            capitalCityId = capitalOf(nationId),
            name = nationNameOf(nationId),
            gold = gold, rice = rice, tech = tech,
        )
    }

    fun envOf(c: Case): WorldEnv = WorldEnv(c.env.year, c.env.startYear, c.env.develCost)

    /**
     * Reconstruct the 6-component seed and GUARD-assert it byte-equals the captured PHP seedString,
     * then return a fresh RNG over it. [rawClassName] is the definition's `rawClassName` (the 6th token).
     */
    fun rngFor(f: Fixture, c: Case, rawClassName: String): RandUtil {
        val seed = serializeSeed(f.hiddenSeed, c.scope, c.env.year, c.env.month, c.generalId, rawClassName)
        assertEquals(c.seedString, seed, "[${c.name}] seedString must byte-equal the golden PHP oracle")
        return RandUtil(LiteHashDrbg(seed))
    }

    /** A resolve context with the golden's date suffix taken from the FIRST captured log line's <1>HH:MM</>. */
    fun ctxFor(
        f: Fixture, c: Case, draft: opensamguk.logic.actions.GeneralActionDraft, rawClassName: String,
        args: Map<String, Any?> = c.arg ?: emptyMap(),
        candidateGenerals: List<General> = emptyList(),
        generalName: String = "", destGeneralName: String = "",
        cityDistance: Int? = null,
    ): GeneralActionResolveContext {
        return GeneralActionResolveContext(
            draft = draft,
            rng = rngFor(f, c, rawClassName),
            env = envOf(c),
            month = c.env.month,
            date = dateOf(c),
            args = args,
            candidateGenerals = candidateGenerals,
            generalName = generalName,
            destGeneralName = destGeneralName,
            cityDistance = cityDistance,
        )
    }

    /** Extract the turn-time <1>HH:MM</> suffix from the first captured acting/broadcast/dest log. */
    fun dateOf(c: Case): String {
        val src = (c.logLines + c.broadcastLines + c.destGenerals.flatMap { it.logLines }).firstOrNull { it.contains("<1>") }
            ?: return ""
        val m = Regex("<1>(\\d{2}:\\d{2})</>").find(src) ?: return ""
        return m.groupValues[1]
    }
}
