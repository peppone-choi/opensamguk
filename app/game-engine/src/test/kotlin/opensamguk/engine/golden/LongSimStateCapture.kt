package opensamguk.engine.golden

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.engine.turn.City
import opensamguk.engine.turn.InMemoryTurnWorld
import opensamguk.engine.turn.Nation
import opensamguk.engine.turn.TurnDiplomacy
import opensamguk.engine.turn.TurnGeneral
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField

/**
 * Phase 3 long-sim replay — capture the live [InMemoryTurnWorld] state into the same JSON shape as the
 * PHP golden (`game_env`, `nation`, `city`, `general`, `diplomacy`, `nation_env`).
 */
object LongSimStateCapture {

    private val PHP_DATETIME: DateTimeFormatter = java.time.format.DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd HH:mm:ss")
        .appendFraction(ChronoField.MICRO_OF_SECOND, 6, 6, true)
        .toFormatter()
        .withZone(java.time.ZoneOffset.UTC)

    fun captureState(world: InMemoryTurnWorld, baselineState: JsonObject): JsonObject {
        val gameEnv = captureGameEnv(world)
        return buildJsonObject {
            put("game_env", gameEnv)
            put("nation", captureTable(world.listNations().sortedBy { it.id }, baselineState["nation"]!!.jsonArray, ::nationValue))
            put("city", captureTable(world.listCities().sortedBy { it.id }, baselineState["city"]!!.jsonArray, ::cityValue))
            put("general", captureTable(world.listGenerals().sortedBy { it.id }, baselineState["general"]!!.jsonArray, ::generalValue))
            put("diplomacy", captureDiplomacy(world, baselineState["diplomacy"]!!.jsonArray))
            put("nation_env", captureNationEnv(world))
        }
    }

    private fun captureGameEnv(world: InMemoryTurnWorld): JsonObject {
        val state = world.getState()
        val meta = state.meta
        return buildJsonObject {
            put("year", encodeValue(state.currentYear))
            put("month", encodeValue(state.currentMonth))
            put("startyear", encodeValue(meta["startYear"] as? Number ?: state.currentYear))
            put("starttime", encodeValue(formatPhpInstant(meta["startTime"]) ?: formatPhpInstant(state.lastTurnTime)))
            put("turnterm", encodeValue(state.tickSeconds / 60))
            put("develcost", encodeValue(meta["develcost"] as? Number ?: 0))
            put("isunited", encodeValue(meta["isunited"] as? Number ?: 0))
            put("turntime", encodeValue(formatPhpInstant(state.lastTurnTime)))
            put("scenario", encodeValue(meta["scenario"] as? Number ?: 0))
            put("map", encodeValue(meta["map"] as? String))
        }
    }

    private fun captureTable(
        rows: List<Any>,
        baselineRows: JsonArray,
        valueOf: (entity: Any, column: String, meta: Map<String, Any?>) -> Any?,
    ): JsonArray {
        if (baselineRows.isEmpty()) return JsonArray(emptyList())
        val columns = baselineRows.first().jsonObject.keys.toList()
        return buildJsonArray {
            for (entity in rows) {
                val meta = entityMeta(entity)
                add(buildJsonObject {
                    for (col in columns) {
                        val v = valueOf(entity, col, meta)
                        put(col, encodeValue(v))
                    }
                })
            }
        }
    }

    private fun entityMeta(entity: Any): Map<String, Any?> = when (entity) {
        is TurnGeneral -> entity.meta
        is City -> entity.meta
        is Nation -> entity.meta
        is TurnDiplomacy -> entity.meta
        else -> emptyMap()
    }

    private fun generalValue(entity: Any, column: String, meta: Map<String, Any?>): Any? {
        val g = entity as TurnGeneral
        return when (column) {
            "no" -> g.id
            "name" -> g.name
            "nation" -> g.nationId
            "city" -> g.cityId
            "troop" -> g.troopId
            "leadership" -> g.stats.leadership
            "strength" -> g.stats.strength
            "intel" -> g.stats.intelligence
            "experience" -> g.experience
            "dedication" -> g.dedication
            "officer_level" -> g.officerLevel
            "injury" -> g.injury
            "gold" -> g.gold
            "rice" -> g.rice
            "crew" -> g.crew
            "crewtype" -> g.crewTypeId
            "train" -> g.train
            "atmos" -> g.atmos
            "age" -> g.age
            "npc" -> g.npcState
            "turntime" -> formatPhpInstant(g.turnTime)
            "recent_war" -> formatPhpInstant(g.recentWarTime)
            "personal" -> (g.role.personality ?: meta["personal"])
            "special" -> (g.role.specialDomestic ?: meta["special"])
            "special2" -> (g.role.specialWar ?: meta["special2"])
            "horse" -> (g.role.items.horse ?: "None")
            "weapon" -> (g.role.items.weapon ?: "None")
            "book" -> (g.role.items.book ?: "None")
            "item" -> (g.role.items.item ?: "None")
            "owner" -> (meta["owner"] ?: 0)
            else -> meta[column]
        }
    }

    private fun cityValue(entity: Any, column: String, meta: Map<String, Any?>): Any? {
        val c = entity as City
        return when (column) {
            "city" -> c.id
            "name" -> c.name
            "level" -> c.level
            "nation" -> c.nationId
            "supply" -> c.supplyState
            "front" -> c.frontState
            "pop" -> c.population
            "pop_max" -> c.populationMax
            "agri" -> c.agriculture
            "agri_max" -> c.agricultureMax
            "comm" -> c.commerce
            "comm_max" -> c.commerceMax
            "secu" -> c.security
            "secu_max" -> c.securityMax
            "def" -> c.defence
            "def_max" -> c.defenceMax
            "wall" -> c.wall
            "wall_max" -> c.wallMax
            "state" -> c.state
            "region" -> c.region
            "trade" -> c.trade
            else -> meta[column]
        }
    }

    private fun nationValue(entity: Any, column: String, meta: Map<String, Any?>): Any? {
        val n = entity as Nation
        return when (column) {
            "nation" -> n.id
            "name" -> n.name
            "color" -> n.color
            "capital" -> n.capitalCityId
            "gold" -> n.gold
            "rice" -> n.rice
            "power" -> n.power
            "tech" -> n.tech
            "level" -> n.level
            "type" -> n.typeCode
            else -> meta[column]
        }
    }

    private fun captureDiplomacy(world: InMemoryTurnWorld, baselineRows: JsonArray): JsonArray {
        val columns = if (baselineRows.isEmpty()) listOf("me", "you", "state", "term", "dead") else baselineRows.first().jsonObject.keys.toList()
        val rows = world.listDiplomacy().sortedWith(compareBy({ it.fromNationId }, { it.toNationId }))
        return buildJsonArray {
            for (d in rows) {
                add(buildJsonObject {
                    for (col in columns) {
                        val v = when (col) {
                            "me" -> d.fromNationId
                            "you" -> d.toNationId
                            "state" -> d.state
                            "term" -> d.term
                            "dead" -> d.dead
                            else -> d.meta[col]
                        }
                        put(col, encodeValue(v))
                    }
                })
            }
        }
    }

    private fun captureNationEnv(world: InMemoryTurnWorld): JsonArray {
        val rows = mutableListOf<JsonObject>()
        for (nation in world.listNations().sortedBy { it.id }) {
            val env = nation.meta["nation_env"] as? Map<*, *> ?: continue
            for ((key, value) in env) {
                rows.add(buildJsonObject {
                    put("namespace", encodeValue(nation.id))
                    put("key", encodeValue(key.toString()))
                    put("value", encodeValue(value))
                })
            }
        }
        return JsonArray(rows)
    }

    fun encodeValue(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> buildJsonArray { for (item in v) add(encodeValue(item)) }
        is Map<*, *> -> buildJsonObject {
            for ((k, item) in v) {
                put(k.toString(), encodeValue(item))
            }
        }
        else -> JsonPrimitive(v.toString())
    }

    private fun formatPhpInstant(value: Any?): String? {
        val instant = when (value) {
            null -> return null
            is Instant -> value
            is String -> parseInstantLike(value) ?: return value
            else -> return value.toString()
        }
        return PHP_DATETIME.format(instant)
    }

    private fun parseInstantLike(raw: String): Instant? =
        runCatching { Instant.parse(raw.trim()) }.getOrNull()
            ?: runCatching { LongSimWorldMaterializer.parseTurnTime(raw) }.getOrNull()

    fun compareStates(expected: JsonObject, actual: JsonObject, path: String = "state"): String? {
        for (key in expected.keys) {
            val e = expected[key]!!
            val a = actual[key]
            if (a == null) return "$path.$key: expected present, actual absent"
            val result = when (key) {
                "nation_env" -> compareNationEnv(e.jsonArray, (a as JsonArray))
                else -> compareJson(e, a, "$path.$key")
            }
            if (result != null) return result
        }
        if (actual.keys.any { it !in expected }) {
            val extra = actual.keys - expected.keys
            return "$path: extra keys $extra"
        }
        return null
    }

    private fun compareJson(expected: JsonElement, actual: JsonElement, path: String): String? {
        if (expected is JsonObject && actual is JsonObject) {
            for (k in expected.keys) {
                if (k !in actual.keys) return "$path.$k: expected present, actual absent"
                val r = compareJson(expected[k]!!, actual[k]!!, "$path.$k")
                if (r != null) return r
            }
            for (k in actual.keys) {
                if (k !in expected.keys) return "$path.$k: extra key"
            }
            return null
        }
        if (expected is JsonArray && actual is JsonArray) {
            if (expected.size != actual.size) return "$path: array size ${expected.size} != ${actual.size}"
            for (i in expected.indices) {
                val r = compareJson(expected[i], actual[i], "$path[$i]")
                if (r != null) return r
            }
            return null
        }
        if (expected is JsonPrimitive && actual is JsonPrimitive) {
            if (valuesEqual(expected, actual)) return null
            return "$path: expected=${expected.content} actual=${actual.content}"
        }
        if (expected is JsonNull && actual is JsonNull) return null
        return "$path: type mismatch expected=${expected::class.simpleName} actual=${actual::class.simpleName}"
    }

    private fun valuesEqual(a: JsonPrimitive, b: JsonPrimitive): Boolean {
        if (a.isString || b.isString) return a.content == b.content
        if (a.booleanOrNull != null || b.booleanOrNull != null) return a.booleanOrNull == b.booleanOrNull
        val ad = a.doubleOrNull
        val bd = b.doubleOrNull
        if (ad != null && bd != null) return ad == bd
        return a.content == b.content
    }

    private fun compareNationEnv(expected: JsonArray, actual: JsonArray): String? {
        fun toMap(arr: JsonArray): Map<Int, Map<String, JsonElement>> {
            val map = LinkedHashMap<Int, MutableMap<String, JsonElement>>()
            for (row in arr.map { it.jsonObject }) {
                val ns = row["namespace"]!!.jsonPrimitive.int
                val key = row["key"]!!.jsonPrimitive.content
                val value = row["value"] ?: JsonNull
                map.getOrPut(ns) { LinkedHashMap() }[key] = value
            }
            return map
        }
        val eMap = toMap(expected)
        val aMap = toMap(actual)
        if (eMap.keys != aMap.keys) return "nation_env: namespace keys differ expected=${eMap.keys} actual=${aMap.keys}"
        for (ns in eMap.keys) {
            val eK = eMap[ns]!!
            val aK = aMap[ns]!!
            if (eK.keys != aK.keys) return "nation_env[$ns]: keys differ expected=${eK.keys} actual=${aK.keys}"
            for (k in eK.keys) {
                val r = compareJson(eK[k]!!, aK[k]!!, "nation_env[$ns].$k")
                if (r != null) return r
            }
        }
        return null
    }

    /** Parse an ISO-8601-ish instant from either a PHP datetime string or an ISO string. */
    fun parseInstant(raw: String): Instant? = runCatching {
        LongSimWorldMaterializer.parseTurnTime(raw)
    }.getOrNull()
}
