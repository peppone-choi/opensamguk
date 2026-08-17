package opensamguk.engine.v2

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import opensamguk.logic.event.EventStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OPENSAM-151 (v2 R2) — `scenario_9200.json`(v2 도시 경제 시험장) 시드 검증.
 *
 * v2 월드는 `ignoreDefaultEvents: true` + 자체 `events` 행으로 돈다. 그래서 검증해야 하는 것은
 * 세 가지다:
 *  (a) 12행이 `EventStore.DEFAULT_EVENTS`의 **1:1 전사**이고 치환 외에는 한 글자도 다르지 않다
 *      (행 개수·순서·priority·조건·액션 순서 전부). 기본 이벤트가 나중에 바뀌면 여기서 깨진다.
 *  (b) `ProcessIncome`(v1 국가 단위 수입) 행이 **0개**. 하나라도 남으면 v2 월드가 수입을 두 번 걷는다.
 *  (c) `V2ProcessCityIncome`이 정확히 2개 — 1월 gold, 7월 rice.
 */
class V2ScenarioSeedTest {

    private val scenario: JsonObject = Json.parseToJsonElement(
        requireNotNull(javaClass.getResourceAsStream("/scenario/scenario_9200.json")) {
            "scenario_9200.json 이 클래스패스에 없다"
        }.readBytes().decodeToString(),
    ) as JsonObject

    private val eventRows: List<JsonArray> = scenario["events"]!!.jsonArray.map { it.jsonArray }

    /** 액션 이름만 뽑는다 — 부분 문자열이 아니라 이름 그대로 비교하기 위해서. */
    private fun actionNames(row: JsonArray): List<String> =
        row.drop(3).map { it.jsonArray[0].jsonPrimitive.content }

    @Test
    fun `ignoreDefaultEvents is on - the scenario owns its whole event set`() {
        assertEquals(JsonPrimitive(true), scenario["ignoreDefaultEvents"])
    }

    /** (a) 치환 외에는 기본 이벤트와 완전히 같다. */
    @Test
    fun `event rows are a verbatim transcription of the defaults, modulo the income substitution`() {
        val defaults = EventStore.defaultWireRows()
        assertEquals(defaults.size, eventRows.size, "행 개수가 기본 이벤트와 다르다")

        for ((i, expected) in defaults.withIndex()) {
            val actual = eventRows[i]
            assertEquals(expected.target, actual[0].jsonPrimitive.content, "행 $i target")
            assertEquals(expected.priority, actual[1].jsonPrimitive.content.toInt(), "행 $i priority")
            assertEquals(expected.condition, actual[2].toString(), "행 $i condition")

            // 기대 액션 = 기본 액션에서 ProcessIncome 만 V2ProcessCityIncome 으로 이름 치환한 것.
            val substituted = buildJsonArray {
                for (action in Json.parseToJsonElement(expected.action).jsonArray) {
                    val a = action.jsonArray
                    if (a[0].jsonPrimitive.content == "ProcessIncome") {
                        add(buildJsonArray {
                            add(JsonPrimitive(V2ProcessCityIncomeAction.NAME))
                            for (rest in a.drop(1)) add(rest)
                        })
                    } else {
                        add(a)
                    }
                }
            }
            assertEquals(substituted.toString(), JsonArray(actual.drop(3)).toString(), "행 $i actions")
        }
    }

    /** (b) v1 수입 leaf 가 하나도 없다. */
    @Test
    fun `no v1 ProcessIncome row survives`() {
        val names = eventRows.flatMap { actionNames(it) }
        assertEquals(0, names.count { it == "ProcessIncome" }, "v2 월드에 v1 ProcessIncome 이 남아 있다")
        // ProcessWarIncome 은 v2 도시 원장과 무관한 별개 leaf 다 — 남아 있어야 정상.
        assertEquals(1, names.count { it == "ProcessWarIncome" })
    }

    /** (c) v2 수입 leaf 가 1월 gold / 7월 rice 정확히 2개. */
    @Test
    fun `exactly two V2ProcessCityIncome rows - january gold and july rice`() {
        val calls = eventRows.flatMap { row ->
            row.drop(3).map { it.jsonArray }.filter { it[0].jsonPrimitive.content == V2ProcessCityIncomeAction.NAME }
                .map { row[2].toString() to it[1].jsonPrimitive.content }
        }
        assertEquals(
            listOf(
                """["Date","==",null,1]""" to "gold",
                """["Date","==",null,7]""" to "rice",
            ),
            calls,
        )
    }

    /** 등록기가 실제로 그 이름을 안다 — 시나리오가 부르는 이름과 팩토리 이름이 어긋나면 디스패치에서 죽는다. */
    @Test
    fun `the registrar knows the name the scenario calls`() {
        val factory = V2WorldActions.register(opensamguk.logic.event.EventActionFactory())
        val action = factory.create(
            opensamguk.logic.event.RawAction(V2ProcessCityIncomeAction.NAME, listOf(JsonPrimitive("gold"))),
        )
        assertTrue(action is V2ProcessCityIncomeAction)
        assertEquals("gold", (action as V2ProcessCityIncomeAction).resource)
    }
}
