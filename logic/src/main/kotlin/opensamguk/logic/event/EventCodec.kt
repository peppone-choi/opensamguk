package opensamguk.logic.event

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.intOrNull

/**
 * FE2 — the JSON-array wire (de)serializer for the `event` table's `condition` / `action` jsonb
 * columns (V1 baseline `condition`/`action`, plan FE3).
 *
 * Faithful port of:
 *  - PHP `Condition::build` (Condition.php:7-44) — **RECURSIVE**: bool→ConstBool; first elem a logic
 *    keyword (case-insensitive) → [EventCondition.Logic] over the recursively-built rest; else a known
 *    `Condition\{key}` leaf built from its recursively-built args (scalar args recurse to themselves).
 *  - PHP `Action::build` (Action.php:12-26) — **NON-recursive**: `[$className, ...$args]`; args are
 *    `array_slice(args, 1)` passed VERBATIM (a nested array is one whole arg, NOT decoded). Encoded as
 *    a [RawAction] the [EventActionFactory] instantiates.
 *
 * The `condition` column holds a SINGLE condition value (bool or `[Class,...]`); the `action` column
 * holds an ARRAY of action arrays (`[[Class,...],[Class,...]]`) — see [decodeActionList].
 */
object EventCodec {

    // ── Condition (recursive) ────────────────────────────────────────────────

    /** Port of `Condition::build` — decode ONE condition value into an [EventCondition] tree. */
    fun decodeCondition(json: JsonElement): EventCondition {
        // PHP: is_bool($chain) → ConstBool.
        if (json is JsonPrimitive) {
            val b = json.booleanOrNull
            if (b != null && !json.isString) return EventCondition.ConstBool(b)
            // A bare non-bool scalar is not a valid top-level condition (PHP would return it raw and
            // later blow up when eval'd) — fail loudly.
            throw IllegalArgumentException("조건은 bool 또는 [Class, ...] 배열이어야 합니다: $json")
        }
        if (json !is JsonArray) {
            throw IllegalArgumentException("조건은 bool 또는 [Class, ...] 배열이어야 합니다: $json")
        }
        require(json.isNotEmpty()) { "빈 조건 배열" }

        val key = primitiveString(json[0]) ?: throw IllegalArgumentException("조건 키가 문자열이 아님: ${json[0]}")
        val rest = json.drop(1)

        // PHP: logic 단축 명령 — first elem is a logic keyword.
        if (key.lowercase() in EventCondition.AVAILABLE_LOGIC) {
            return EventCondition.Logic(key, rest.map { decodeCondition(it) })
        }

        // PHP: Condition\{key} leaf — args recursively built (scalars recurse to themselves).
        return buildLeaf(key, rest)
    }

    private fun buildLeaf(key: String, rawArgs: List<JsonElement>): EventCondition = when (key) {
        "ConstBool" -> EventCondition.ConstBool(boolArg(rawArgs, 0))
        "Date" -> EventCondition.Date(stringArg(rawArgs, 0), intOrNullArg(rawArgs, 1), intOrNullArg(rawArgs, 2))
        "DateRelative" ->
            EventCondition.DateRelative(stringArg(rawArgs, 0), intOrNullArg(rawArgs, 1), intOrNullArg(rawArgs, 2))
        "RemainNation" -> EventCondition.RemainNation(stringArg(rawArgs, 0), intArg(rawArgs, 1))
        "Interval" -> throw NotImplementedError("Not Yet Implmented.") // Interval.php:7 throws on build
        "Engine" -> EventCondition.Engine
        else -> throw IllegalArgumentException("존재하지 않는 Condition입니다 :$key")
    }

    // ── Action (non-recursive) ────────────────────────────────────────────────

    /** Port of `Action::build` — split `[$className, ...$args]` into a [RawAction] (args verbatim). */
    fun decodeAction(json: JsonElement): RawAction {
        if (json !is JsonArray) throw IllegalArgumentException("action을 입력해야 합니다.")
        require(json.isNotEmpty()) { "빈 action 배열" }
        val name = primitiveString(json[0]) ?: throw IllegalArgumentException("action 이름이 문자열이 아님: ${json[0]}")
        return RawAction(name, json.drop(1)) // verbatim — NON-recursive
    }

    /** The `action` column is an ARRAY of action arrays; decode each in array order. */
    fun decodeActionList(json: JsonElement): List<RawAction> {
        if (json !is JsonArray) throw IllegalArgumentException("action 목록은 배열이어야 합니다.")
        return json.map { decodeAction(it) }
    }

    // ── Encode (round-trip) ────────────────────────────────────────────────

    /** Encode an [EventCondition] back to the JSON wire (inverse of [decodeCondition]). */
    fun encodeCondition(cond: EventCondition): JsonElement = when (cond) {
        is EventCondition.ConstBool -> JsonPrimitive(cond.value)
        is EventCondition.Date -> buildJsonArray {
            add(JsonPrimitive("Date")); add(JsonPrimitive(cond.cmp))
            add(cond.year?.let { JsonPrimitive(it) } ?: JsonNull)
            add(cond.month?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        is EventCondition.DateRelative -> buildJsonArray {
            add(JsonPrimitive("DateRelative")); add(JsonPrimitive(cond.cmp))
            add(cond.year?.let { JsonPrimitive(it) } ?: JsonNull)
            add(cond.month?.let { JsonPrimitive(it) } ?: JsonNull)
        }
        is EventCondition.RemainNation -> buildJsonArray {
            add(JsonPrimitive("RemainNation")); add(JsonPrimitive(cond.cmp)); add(JsonPrimitive(cond.cnt))
        }
        is EventCondition.Logic -> buildJsonArray {
            add(JsonPrimitive(cond.mode))
            cond.conditions.forEach { add(encodeCondition(it)) }
        }
        EventCondition.Interval -> throw NotImplementedError("Not Yet Implmented.")
        EventCondition.Engine -> buildJsonArray { add(JsonPrimitive("Engine")) }
    }

    /** Encode a [RawAction] back to `[name, ...args]` (args verbatim). */
    fun encodeAction(raw: RawAction): JsonElement = buildJsonArray {
        add(JsonPrimitive(raw.name))
        raw.args.forEach { add(it) }
    }

    /** Encode an action list to `[[name,...],[name,...]]`. */
    fun encodeActionList(list: List<RawAction>): JsonElement = buildJsonArray {
        list.forEach { add(encodeAction(it)) }
    }

    // ── arg helpers ────────────────────────────────────────────────────────

    private fun primitiveString(e: JsonElement): String? =
        (e as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun stringArg(args: List<JsonElement>, i: Int): String =
        primitiveString(args[i]) ?: throw IllegalArgumentException("인자 $i 가 문자열이 아님: ${args[i]}")

    private fun intArg(args: List<JsonElement>, i: Int): Int =
        (args[i] as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("인자 $i 가 정수가 아님: ${args[i]}")

    private fun intOrNullArg(args: List<JsonElement>, i: Int): Int? {
        if (i >= args.size) return null
        val e = args[i]
        if (e is JsonNull) return null
        return (e as? JsonPrimitive)?.intOrNull ?: throw IllegalArgumentException("인자 $i 가 정수/null이 아님: $e")
    }

    private fun boolArg(args: List<JsonElement>, i: Int): Boolean =
        (args[i] as? JsonPrimitive)?.booleanOrNull ?: throw IllegalArgumentException("인자 $i 가 bool이 아님: ${args[i]}")
}
