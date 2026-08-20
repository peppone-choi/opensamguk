package opensamguk.engine.golden

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import opensamguk.common.constants.EffectiveGameConst
import opensamguk.engine.turn.City
import opensamguk.engine.turn.GeneralRole
import opensamguk.engine.turn.GeneralStats
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.LegacyDiplomacyIdentityOracle
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import opensamguk.engine.turn.TurnWorldState
import opensamguk.engine.turn.WorldSnapshot
import opensamguk.logic.tick.ServerClock
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Phase 3 long-sim replay — materialize an [InMemoryTurnWorld] from a PHP golden baseline JSON.
 *
 * The baseline shape is what `tools/php-golden/capture_longsim.php` emits:
 *   { hiddenSeed, startYear, turnterm, maxTurns, state: { game_env, nation, city, general, diplomacy, nation_env } }
 */
object LongSimWorldMaterializer {

    private val ISO_MICROS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSSSSS]")

    data class Baseline(
        val hiddenSeed: String,
        val startYear: Int,
        val turnterm: Int,
        val maxTurns: Int,
        val state: JsonObject,
    )

    fun loadBaseline(resourceName: String = "golden/longsim/capture-00-baseline.json"): Baseline {
        val json = Json.parseToJsonElement(loadResource(resourceName)).jsonObject
        return parseBaseline(json)
    }

    fun parseBaseline(json: JsonObject): Baseline {
        return Baseline(
            hiddenSeed = json["hiddenSeed"]!!.jsonPrimitive.content,
            startYear = json["startYear"]!!.jsonPrimitive.int,
            turnterm = json["turnterm"]!!.jsonPrimitive.int,
            maxTurns = json["maxTurns"]!!.jsonPrimitive.int,
            state = json["state"]!!.jsonObject,
        )
    }

    fun materializeWorld(
        baseline: Baseline,
        diplomacyIdentityOracle: LegacyDiplomacyIdentityOracle? = null,
    ): InMemoryTurnWorld {
        val stateJson = baseline.state
        val gameEnv = stateJson["game_env"]!!.jsonObject

        val year = gameEnv["year"]!!.jsonPrimitive.int
        val month = gameEnv["month"]!!.jsonPrimitive.int
        val startYear = gameEnv["startyear"]!!.jsonPrimitive.int
        val turnterm = gameEnv["turnterm"]!!.jsonPrimitive.int
        val startTime = parseTurnTime(gameEnv["starttime"]?.jsonPrimitive?.contentOrNull)
        val turntime = parseTurnTime(gameEnv["turntime"]?.jsonPrimitive?.contentOrNull)
        val scenario = gameEnv["scenario"]?.jsonPrimitive?.intOrNull ?: 1010
        val mapName = gameEnv["map"]?.jsonPrimitive?.contentOrNull
        val isunited = gameEnv["isunited"]?.jsonPrimitive?.intOrNull ?: 0
        val develcost = gameEnv["develcost"]?.jsonPrimitive?.intOrNull
            ?: EffectiveGameConst.develcost(year, startYear)
        val killturn = EffectiveGameConst.killturn(turnterm, npcmode = 0)

        val nationEnvByNation = loadNationEnv(stateJson["nation_env"]!!.jsonArray)

        val generals = stateJson["general"]!!.jsonArray
            .map { toGeneral(it.jsonObject) }
            .sortedBy { it.id }
        val cities = stateJson["city"]!!.jsonArray
            .map { toCity(it.jsonObject) }
            .sortedBy { it.id }
        val nations = stateJson["nation"]!!.jsonArray
            .map { toNation(it.jsonObject, nationEnvByNation) }
            .sortedBy { it.id }
        val diplomacy = stateJson["diplomacy"]!!.jsonArray
            .map { toDiplomacy(it.jsonObject) }

        val maxNationId = nations.maxOfOrNull { it.id } ?: 0
        val maxGeneralId = generals.maxOfOrNull { it.id } ?: 0

        val stateMeta = decodeObject(gameEnv).toMutableMap()
        stateMeta.putAll(
            linkedMapOf(
                "hiddenSeed" to baseline.hiddenSeed,
                "startYear" to startYear,
                "startTime" to startTime.toString(),
                "turnterm" to turnterm,
                "scenario" to scenario,
                "map" to mapName,
                "isunited" to isunited,
                "develcost" to develcost,
                "killturn" to killturn,
                "maxNationId" to maxNationId,
                "maxGeneralId" to maxGeneralId,
            ),
        )
        val state = TurnWorldState(
            id = 1,
            currentYear = year,
            currentMonth = month,
            tickSeconds = turnterm * 60,
            lastTurnTime = turntime,
            meta = stateMeta,
            config = linkedMapOf("mapName" to (mapName ?: "che")),
        )
        return InMemoryTurnWorld(
            WorldSnapshot(
                state,
                generals,
                cities,
                nations,
                emptyList(),
                diplomacy,
                worldId = opensamguk.common.world.WorldId((state).id),
            ),
            legacyDiplomacyIdentityOracle = diplomacyIdentityOracle,
        )
    }

    private fun loadNationEnv(rows: JsonArray): Map<Int, Map<String, Any?>> {
        val map = LinkedHashMap<Int, MutableMap<String, Any?>>()
        for (row in rows.map { it.jsonObject }) {
            val namespace = row["namespace"]!!.jsonPrimitive.int
            val key = row["key"]!!.jsonPrimitive.content
            val rawValue = row["value"] ?: continue
            if (rawValue is JsonNull) continue
            // The stored value is itself a JSON-encoded string (KVStorage serializes values).
            val value = decodeElement(Json.parseToJsonElement(rawValue.jsonPrimitive.content))
            map.getOrPut(namespace) { LinkedHashMap() }[key] = value
        }
        return map
    }

    private fun toGeneral(o: JsonObject): TurnGeneral {
        val meta = decodeObject(o).toMutableMap()
        val npc = o["npc"]?.jsonPrimitive?.intOrNull ?: 0
        if (npc >= 2) {
            val rawKillturn = (meta["killturn"] as? Number)?.toInt()
            if (rawKillturn != null) {
                meta["killturn"] = rawKillturn * 3
                meta["killturn_unit"] = "phase"
                meta[LongSimKillturnOracle.OFFSET_META_KEY] = rawKillturn * 2
            }
        }
        return TurnGeneral(
            id = o["no"]!!.jsonPrimitive.int,
            userId = o["owner"]?.jsonPrimitive?.contentOrNull?.takeIf { it != "0" },
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
            nationId = o["nation"]?.jsonPrimitive?.intOrNull ?: 0,
            cityId = o["city"]?.jsonPrimitive?.intOrNull ?: 0,
            troopId = o["troop"]?.jsonPrimitive?.intOrNull ?: 0,
            stats = GeneralStats(
                leadership = o["leadership"]?.jsonPrimitive?.intOrNull ?: 0,
                strength = o["strength"]?.jsonPrimitive?.intOrNull ?: 0,
                intelligence = o["intel"]?.jsonPrimitive?.intOrNull ?: 0,
            ),
            experience = o["experience"]?.jsonPrimitive?.intOrNull ?: 0,
            dedication = o["dedication"]?.jsonPrimitive?.intOrNull ?: 0,
            officerLevel = o["officer_level"]?.jsonPrimitive?.intOrNull ?: 0,
            role = GeneralRole(
                personality = o["personal"]?.jsonPrimitive?.contentOrNull,
                specialDomestic = o["special"]?.jsonPrimitive?.contentOrNull,
                specialWar = o["special2"]?.jsonPrimitive?.contentOrNull,
                items = opensamguk.engine.turn.GeneralItems(
                    horse = o["horse"]?.jsonPrimitive?.contentOrNull,
                    weapon = o["weapon"]?.jsonPrimitive?.contentOrNull,
                    book = o["book"]?.jsonPrimitive?.contentOrNull,
                    item = o["item"]?.jsonPrimitive?.contentOrNull,
                ),
            ),
            injury = o["injury"]?.jsonPrimitive?.intOrNull ?: 0,
            gold = o["gold"]?.jsonPrimitive?.intOrNull ?: 0,
            rice = o["rice"]?.jsonPrimitive?.intOrNull ?: 0,
            crew = o["crew"]?.jsonPrimitive?.intOrNull ?: 0,
            crewTypeId = o["crewtype"]?.jsonPrimitive?.intOrNull ?: 0,
            train = o["train"]?.jsonPrimitive?.intOrNull ?: 0,
            atmos = o["atmos"]?.jsonPrimitive?.intOrNull ?: 0,
            age = o["age"]?.jsonPrimitive?.intOrNull ?: 0,
            npcState = npc,
            turnTime = parseTurnTime(o["turntime"]?.jsonPrimitive?.contentOrNull),
            recentWarTime = o["recent_war"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
                ?.let { runCatching { parseTurnTime(it) }.getOrNull() },
            meta = meta,
        )
    }

    private fun toCity(o: JsonObject): City {
        val meta = decodeObject(o).toMutableMap()
        return City(
            id = o["city"]!!.jsonPrimitive.int,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
            nationId = o["nation"]?.jsonPrimitive?.intOrNull ?: 0,
            level = o["level"]?.jsonPrimitive?.intOrNull ?: 0,
            state = o["state"]?.jsonPrimitive?.intOrNull ?: 0,
            population = o["pop"]?.jsonPrimitive?.intOrNull ?: 0,
            populationMax = o["pop_max"]?.jsonPrimitive?.intOrNull ?: 0,
            agriculture = o["agri"]?.jsonPrimitive?.intOrNull ?: 0,
            agricultureMax = o["agri_max"]?.jsonPrimitive?.intOrNull ?: 0,
            commerce = o["comm"]?.jsonPrimitive?.intOrNull ?: 0,
            commerceMax = o["comm_max"]?.jsonPrimitive?.intOrNull ?: 0,
            security = o["secu"]?.jsonPrimitive?.intOrNull ?: 0,
            securityMax = o["secu_max"]?.jsonPrimitive?.intOrNull ?: 0,
            supplyState = o["supply"]?.jsonPrimitive?.intOrNull ?: 0,
            frontState = o["front"]?.jsonPrimitive?.intOrNull ?: 0,
            defence = o["def"]?.jsonPrimitive?.intOrNull ?: 0,
            defenceMax = o["def_max"]?.jsonPrimitive?.intOrNull ?: 0,
            wall = o["wall"]?.jsonPrimitive?.intOrNull ?: 0,
            wallMax = o["wall_max"]?.jsonPrimitive?.intOrNull ?: 0,
            trade = o["trade"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull,
            region = o["region"]?.jsonPrimitive?.intOrNull ?: 0,
            term = o["term"]?.jsonPrimitive?.intOrNull ?: 0,
            officerSet = o["officer_set"]?.jsonPrimitive?.intOrNull ?: 0,
            conflict = jsonStorageString(o["conflict"]),
            meta = meta,
        )
    }

    private fun jsonStorageString(value: JsonElement?): String =
        when (value) {
            null, JsonNull -> "{}"
            is JsonPrimitive -> value.contentOrNull ?: value.toString()
            else -> value.toString()
        }

    private fun toNation(o: JsonObject, nationEnvByNation: Map<Int, Map<String, Any?>>): Nation {
        val nid = o["nation"]!!.jsonPrimitive.int
        val meta = decodeObject(o).toMutableMap()
        meta["nation_env"] = nationEnvByNation[nid] ?: emptyMap<String, Any?>()
        return Nation(
            id = nid,
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "",
            color = o["color"]?.jsonPrimitive?.contentOrNull ?: "#000000",
            capitalCityId = o["capital"]?.jsonPrimitive?.intOrNull,
            chiefGeneralId = o["chief_set"]?.jsonPrimitive?.intOrNull?.takeIf { it != 0 },
            gold = o["gold"]?.jsonPrimitive?.intOrNull ?: 0,
            rice = o["rice"]?.jsonPrimitive?.intOrNull ?: 0,
            power = o["power"]?.jsonPrimitive?.intOrNull ?: 0,
            tech = o["tech"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
            level = o["level"]?.jsonPrimitive?.intOrNull ?: 0,
            typeCode = o["type"]?.jsonPrimitive?.contentOrNull ?: "None",
            meta = meta,
        )
    }

    private fun toDiplomacy(o: JsonObject): TurnDiplomacy {
        val meta = decodeObject(o).toMutableMap()
        return TurnDiplomacy(
            fromNationId = o["me"]!!.jsonPrimitive.int,
            toNationId = o["you"]!!.jsonPrimitive.int,
            state = o["state"]?.jsonPrimitive?.intOrNull ?: 0,
            term = o["term"]?.jsonPrimitive?.intOrNull ?: 0,
            dead = o["dead"]?.jsonPrimitive?.intOrNull ?: 0,
            meta = meta,
        )
    }

    fun parseTurnTime(raw: String?): Instant {
        if (raw.isNullOrBlank()) return Instant.parse("0181-01-01T00:00:00Z")
        return runCatching {
            val ldt = java.time.LocalDateTime.parse(raw.trim(), ISO_MICROS)
            ldt.atZone(ServerClock.SERVER_ZONE).toInstant()
        }.getOrElse { Instant.parse(raw.trim().replace(' ', 'T') + "Z") }
    }

    /** Decode a JSON object into a Kotlin map (insertion order preserved; nested objects/arrays recursed). */
    fun decodeObject(o: JsonObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(o.size)
        for ((k, v) in o) out[k] = decodeElement(v)
        return out
    }

    fun decodeElement(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> {
                val l = e.longOrNull!!
                if (l in Int.MIN_VALUE..Int.MAX_VALUE) l.toInt() else l
            }
            e.doubleOrNull != null -> e.doubleOrNull
            else -> e.content
        }
        is JsonArray -> e.map { decodeElement(it) }
        is JsonObject -> decodeObject(e)
    }

    private fun loadResource(name: String): String =
        javaClass.classLoader.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)
}
