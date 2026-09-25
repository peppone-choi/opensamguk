package opensamguk.logic.record

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Compact, version-one JSONB wire: a role fixes the ref/fact type; names and prose have no slot. */
object EventPayloadCodec {
    fun encodeRefs(refs: Map<RefRole, EventRef>): String = JsonObject(refs.mapKeys { it.key.name }
        .mapValues { (_, ref) ->
            when (ref) {
                is EventRef.General -> JsonPrimitive(ref.id)
                is EventRef.City -> JsonPrimitive(ref.id)
                is EventRef.Nation -> JsonPrimitive(ref.id)
                is EventRef.Corps -> JsonPrimitive(ref.id)
                is EventRef.Request -> JsonPrimitive(ref.id)
                is EventRef.RoadFort -> JsonPrimitive(ref.id)
                is EventRef.Replay -> JsonPrimitive(ref.id)
            }
        }).toString()

    fun decodeRefs(json: String): Map<RefRole, EventRef> = objectOf(json).map { (key, value) ->
        val role = RefRole.valueOf(key)
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("$key must be scalar")
        val ref = when (role) {
            RefRole.ACTOR, RefRole.PERSON, RefRole.ISSUER, RefRole.TARGET -> EventRef.General(primitive.intId())
            RefRole.CITY -> EventRef.City(primitive.intId())
            RefRole.NATION, RefRole.FROM_NATION, RefRole.TO_NATION -> EventRef.Nation(primitive.intId())
            RefRole.CORPS -> EventRef.Corps(primitive.stringId())
            RefRole.REQUEST -> EventRef.Request(primitive.stringId())
            RefRole.ROAD_FORT -> EventRef.RoadFort(primitive.stringId())
            RefRole.REPLAY -> EventRef.Replay(primitive.stringId())
        }
        role to ref
    }.toMap()

    fun encodeFacts(facts: Map<FactRole, EventFact>): String = JsonObject(facts.mapKeys { it.key.name }
        .mapValues { (_, fact) ->
            when (fact) {
                is EventFact.Amount -> JsonPrimitive(fact.value)
                is EventFact.Change -> JsonPrimitive(fact.value)
                is EventFact.TroopsBand -> JsonPrimitive(fact.value)
                is EventFact.Outcome -> JsonPrimitive(fact.code)
            }
        }).toString()

    fun decodeFacts(json: String): Map<FactRole, EventFact> = objectOf(json).map { (key, value) ->
        val role = FactRole.valueOf(key)
        val primitive = value as? JsonPrimitive ?: throw IllegalArgumentException("$key must be scalar")
        val fact = when (role) {
            FactRole.COUNTIES, FactRole.MONEY, FactRole.GRAIN, FactRole.IRON, FactRole.TIMBER,
            FactRole.HORSES, FactRole.RENOWN_BEFORE, FactRole.RENOWN_AFTER -> EventFact.Amount(primitive.longNumber())
            FactRole.RENOWN_CHANGE -> EventFact.Change(primitive.longNumber())
            FactRole.TROOPS_BAND -> EventFact.TroopsBand(primitive.intId())
            FactRole.OUTCOME -> EventFact.Outcome(primitive.stringId())
        }
        role to fact
    }.toMap()

    private fun objectOf(json: String): JsonObject = Json.parseToJsonElement(json) as? JsonObject
        ?: throw IllegalArgumentException("Event payload must be an object")

    private fun JsonPrimitive.intId(): Int {
        require(!isString)
        return intOrNull ?: throw IllegalArgumentException("Expected integer")
    }

    private fun JsonPrimitive.longNumber(): Long {
        require(!isString)
        return longOrNull ?: throw IllegalArgumentException("Expected integer")
    }

    private fun JsonPrimitive.stringId(): String {
        require(isString)
        return content
    }
}
