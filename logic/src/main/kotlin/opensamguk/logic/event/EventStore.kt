package opensamguk.logic.event

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray

/**
 * FE3 — the in-memory `event`-table mirror.
 *
 * F2 ruling (plan consolidated OQ #9): the `event` table is the V1 baseline persisted table; the
 * store mirrors it in-memory at scenario load so [opensamguk.logic.event.EventAction] `DeleteEvent`
 * self-delete + self-insert flush semantics match the golden DB dump. Rows carry a stable serial-PK
 * [EventRow.id] assigned in INSERTION order (= the `id serial` PK, ties resolved by insertion order).
 *
 * F2 AUTHORS the [withDefaults] seed VERBATIM from `GameConstBase.php:447-531` ($defaultEvents) — the
 * row set AND the intra-row action order are F2-owned (NO per-family row-append API; families only
 * register their leaf into the [EventActionFactory] by name, plan §append protocol).
 *
 * A [DeleteEvent] tombstone + a self-insert MUTATE the store but NOT an in-flight dispatch's frozen
 * row set — [EventDispatcher] materializes a snapshot list before running, so this store is free to
 * mutate during a dispatch.
 */
class EventStore {

    /** One materialized event row: the serial-PK [id], target, priority, decoded condition + actions. */
    data class EventRow(
        val id: Int,
        val target: EventTarget,
        val priority: Int,
        val condition: EventCondition,
        val actions: List<RawAction>,
    )

    private val rows = LinkedHashMap<Int, EventRow>()
    private var nextId = 1

    /** Insert a row from ALREADY-DECODED condition + actions (the self-insert / test path). */
    fun insert(targetCode: String, priority: Int, condition: EventCondition, actions: List<RawAction>): Int {
        val id = nextId++
        rows[id] = EventRow(id, EventTarget.fromCode(targetCode), priority, condition, actions)
        return id
    }

    /** Insert a row from the JSON wire (the DB-load / [withDefaults] path); decodes via [EventCodec]. */
    fun insertRaw(targetCode: String, priority: Int, conditionJson: JsonElement, actionJson: JsonElement): Int {
        val cond = EventCodec.decodeCondition(conditionJson)
        val actions = EventCodec.decodeActionList(actionJson)
        return insert(targetCode, priority, cond, actions)
    }

    /** PHP `DeleteEvent` (`Action/DeleteEvent.php:18`): `DELETE FROM event WHERE id = %i`. Idempotent. */
    fun delete(id: Int) {
        rows.remove(id)
    }

    /** All rows for [target], ordered the EXACT dispatch order: `priority DESC, id ASC`. */
    fun rowsFor(target: EventTarget): List<EventRow> =
        rows.values.asSequence()
            .filter { it.target == target }
            .sortedWith(compareByDescending<EventRow> { it.priority }.thenBy { it.id })
            .toList()

    /** Every row (insertion order); for assertions/diagnostics. */
    fun allRows(): List<EventRow> = rows.values.toList()

    companion object {
        private val json = Json { }

        /**
         * The F2-authored `$defaultEvents` seed (GameConstBase.php:447-531), inserted in array order so
         * the serial-PK ids ascend with the source order. `ignoreDefaultEvents` (Scenario.php:214)
         * yields an empty store (a scenario then supplies its own rows). $defaultInitialEvents
         * (GameConstBase.php:441-446) are run-once at build via tryRunEvent — NOT inserted into the
         * dispatched event set, so they are intentionally NOT seeded here.
         */
        fun withDefaults(ignoreDefaultEvents: Boolean = false): EventStore {
            val store = EventStore()
            if (ignoreDefaultEvents) return store
            for (row in DEFAULT_EVENTS) {
                store.insertRaw(row.target, row.priority, row.condition, row.action)
            }
            return store
        }

        private data class SeedRow(
            val target: String,
            val priority: Int,
            val condition: JsonElement,
            val action: JsonElement,
        )

        private fun cond(s: String): JsonElement = Json.parseToJsonElement(s)

        /** `[Class, ...args]` action array. */
        private fun a(name: String, vararg args: Any?): JsonArray = buildJsonArray {
            add(JsonPrimitive(name))
            for (arg in args) {
                when (arg) {
                    null -> add(JsonNull)
                    is Int -> add(JsonPrimitive(arg))
                    is String -> add(JsonPrimitive(arg))
                    is Boolean -> add(JsonPrimitive(arg))
                    else -> error("unsupported seed arg $arg")
                }
            }
        }

        private fun actions(vararg acts: JsonArray): JsonArray = buildJsonArray { acts.forEach { add(it) } }

        /**
         * VERBATIM transcription of `GameConstBase.php:447-531` $defaultEvents. Each entry is
         * `[target, priority, condition, ...actions]`; here split into target/priority/condition + the
         * ordered action array. The NoticeToHistoryLog `ActionLogger::EVENT_YEAR_MONTH` constant is
         * the integer log-type the F7 HistoryTokens scaffold defines — seeded here as the raw token
         * "EVENT_YEAR_MONTH" so the wire stays self-describing until A/B leaves resolve it.
         */
        private val DEFAULT_EVENTS: List<SeedRow> = listOf(
            // pre_month / 9000 → [UpdateCitySupply, ProcessWarIncome]
            SeedRow(
                "pre_month", 9000, cond("true"),
                actions(a("UpdateCitySupply"), a("ProcessWarIncome")),
            ),
            // month / 9000 (Date == null,1) → the 8-action January row
            SeedRow(
                "month", 9000, cond("""["Date","==",null,1]"""),
                actions(
                    a("MergeInheritPointRank"),
                    a("ProcessSemiAnnual", "gold"),
                    a("ProcessIncome", "gold"),
                    a("ResetOfficerLock"),
                    a("RaiseDisaster"),
                    a("RandomizeCityTradeRate"),
                    a("NewYear"),
                    a("AssignGeneralSpeciality"),
                ),
            ),
            // month / 9000 (Date == null,4) → [ResetOfficerLock, RaiseDisaster]
            SeedRow(
                "month", 9000, cond("""["Date","==",null,4]"""),
                actions(a("ResetOfficerLock"), a("RaiseDisaster")),
            ),
            // month / 9000 (Date == null,7) → the 6-action July (rice) row
            SeedRow(
                "month", 9000, cond("""["Date","==",null,7]"""),
                actions(
                    a("MergeInheritPointRank"),
                    a("ProcessSemiAnnual", "rice"),
                    a("ProcessIncome", "rice"),
                    a("ResetOfficerLock"),
                    a("RaiseDisaster"),
                    a("RandomizeCityTradeRate"),
                ),
            ),
            // month / 9000 (Date == null,10) → [ResetOfficerLock, RaiseDisaster]
            SeedRow(
                "month", 9000, cond("""["Date","==",null,10]"""),
                actions(a("ResetOfficerLock"), a("RaiseDisaster")),
            ),
            // month / 2000 (DateRelative == 1,1) → 거병 출병제한 2년 notice + DeleteEvent
            SeedRow(
                "month", 2000, cond("""["DateRelative","==",1,1]"""),
                actions(
                    a("NoticeToHistoryLog", "<S>2년 뒤 출병 제한이 풀립니다.</>", "EVENT_YEAR_MONTH"),
                    a("DeleteEvent"),
                ),
            ),
            // month / 2000 (DateRelative == 2,1) → 1년 뒤 notice + DeleteEvent
            SeedRow(
                "month", 2000, cond("""["DateRelative","==",2,1]"""),
                actions(
                    a("NoticeToHistoryLog", "<S>1년 뒤 출병 제한이 풀립니다.</>", "EVENT_YEAR_MONTH"),
                    a("DeleteEvent"),
                ),
            ),
            // month / 2000 (DateRelative == 2,7) → 6개월 뒤 notice + DeleteEvent
            SeedRow(
                "month", 2000, cond("""["DateRelative","==",2,7]"""),
                actions(
                    a("NoticeToHistoryLog", "<S>6개월 뒤 출병 제한이 풀립니다. 병력을 준비해주세요.</>", "EVENT_YEAR_MONTH"),
                    a("DeleteEvent"),
                ),
            ),
            // month / 2000 (DateRelative == 3,1) → 출병 제한 해제 notice + DeleteEvent
            SeedRow(
                "month", 2000, cond("""["DateRelative","==",3,1]"""),
                actions(
                    a("NoticeToHistoryLog", "<S>출병 제한이 풀렸습니다.</>", "EVENT_YEAR_MONTH"),
                    a("DeleteEvent"),
                ),
            ),
            // month / 2000 (DateRelative == 4,1) → 하야/망명 패널티 notice + 2x AddGlobalBetray + DeleteEvent
            SeedRow(
                "month", 2000, cond("""["DateRelative","==",4,1]"""),
                actions(
                    a("NoticeToHistoryLog", "<S>이제부터 하야, 망명시 패널티가 적용됩니다.</>", "EVENT_YEAR_MONTH"),
                    a("AddGlobalBetray", 1, 0),
                    a("AddGlobalBetray", 1, 1),
                    a("DeleteEvent"),
                ),
            ),
            // month / 1000 (true) → [UpdateNationLevel, ProvideNPCTroopLeader]
            SeedRow(
                "month", 1000, cond("true"),
                actions(a("UpdateNationLevel"), a("ProvideNPCTroopLeader")),
            ),
            // united / 5000 (true) → [MergeInheritPointRank]
            SeedRow(
                "united", 5000, cond("true"),
                actions(a("MergeInheritPointRank")),
            ),
            // month / 3000 (DateRelative == 1,1) → [OpenNationBetting, DeleteEvent]
            SeedRow(
                "month", 3000, cond("""["DateRelative","==",1,1]"""),
                actions(a("OpenNationBetting"), a("DeleteEvent")),
            ),
        )
    }
}
