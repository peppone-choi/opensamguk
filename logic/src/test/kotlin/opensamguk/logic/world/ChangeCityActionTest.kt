package opensamguk.logic.world

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.event.RawAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A3 — ChangeCity (PHP `Event/Action/ChangeCity.php`) 충실 포팅 게이트.
 *
 * **draw 0** (RNG 미사용) + 결정적 도시 필드 패치. PHP SQL 식(`least`/`greatest`/`ROUND`/`*`)을 도시
 * 현재값에 평가한 결과가 byte-동일해야 한다. 실제 시나리오 사용 패턴(`N%`/int/`+N`)을 1차 게이트로,
 * 나머지 분기(float 곱셈/`-`/`/`/`_max` math/target 분기/검증 throw)를 전수 커버.
 *
 * scenario_1010/900 등의 정본 인보케이션:
 *   ["ChangeCity","free",  {pop:"70%",agri:"70%",comm:"70%",secu:"70%",trust:80}]
 *   ["ChangeCity","occupied",{...,def:"70%",wall:"70%"}]
 */
class ChangeCityActionTest {

    /** 도시 목록 + 패치 sink를 기록하는 테스트용 richer context. 대상 필터는 target.type대로 적용. */
    private class RecordingCtx(
        private val cities: List<ChangeCityCity>,
    ) : ChangeCityContext {
        override val env: Map<String, Any?> = linkedMapOf("year" to 200, "month" to 1, "currentEventID" to null)
        var applied: Map<Int, Map<String, Number>>? = null

        override fun targetCities(target: ChangeCityTarget): List<ChangeCityCity> = when (target.type) {
            "all" -> cities
            "free" -> cities.filter { it.nationId == 0 }
            "occupied" -> cities.filter { it.nationId != 0 }
            "cities" -> {
                val byId = target.args.all { it.toIntOrNull() != null }
                if (byId) {
                    val ids = target.args.map { it.toInt() }.toSet()
                    cities.filter { it.id in ids }
                } else {
                    val names = target.args.toSet()
                    cities.filter { it.name in names }
                }
            }
            else -> throw IllegalArgumentException("올바르지 않은 cond 입니다.")
        }

        override fun applyCityPatches(patches: Map<Int, Map<String, Number>>) {
            applied = patches
        }
    }

    private fun city(
        id: Int, nationId: Int, name: String = "C$id",
        pop: Int = 0, popMax: Int = 0,
        agri: Int = 0, agriMax: Int = 0,
        comm: Int = 0, commMax: Int = 0,
        secu: Int = 0, secuMax: Int = 0,
        def: Int = 0, defMax: Int = 0,
        wall: Int = 0, wallMax: Int = 0,
        trust: Double = 0.0,
    ) = ChangeCityCity(
        id, nationId, name, pop, popMax, agri, agriMax, comm, commMax,
        secu, secuMax, def, defMax, wall, wallMax, trust,
    )

    private fun obj(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) {
            when (v) {
                is Int -> put(k, JsonPrimitive(v))
                is Double -> put(k, JsonPrimitive(v))
                is String -> put(k, JsonPrimitive(v))
                else -> error("unsupported $v")
            }
        }
    }

    // ── 1. 정본 시나리오 패턴: N% + int (free/occupied) ───────────────────────

    @Test
    fun `scenario 1010 free - percent on keyMax basis and trust int clamp`() {
        // free 도시 한 개: 현재값과 max가 다른 케이스로 percent가 keyMax 기준임을 검증.
        val cities = listOf(
            city(
                id = 1, nationId = 0, name = "낙양",
                pop = 100000, popMax = 200000,
                agri = 1000, agriMax = 4000,
                comm = 500, commMax = 3000,
                secu = 100, secuMax = 1000,
                trust = 50.0,
            ),
            city(id = 2, nationId = 5, name = "장안"), // occupied — free 타깃에서 제외돼야
        )
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("free")); add(obj("pop" to "70%", "agri" to "70%", "comm" to "70%", "secu" to "70%", "trust" to 80)) }
                .toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)

        val patches = ctx.applied!!
        assertEquals(setOf(1), patches.keys, "free 타깃은 nation=0 도시만")
        val p = patches.getValue(1)
        // percent는 keyMax * (round(70)/100) = max * 0.7, ROUND half-away.
        assertEquals(140000, p["pop"])  // ROUND(200000*0.7)
        assertEquals(2800, p["agri"])   // ROUND(4000*0.7)
        assertEquals(2100, p["comm"])   // ROUND(3000*0.7)
        assertEquals(700, p["secu"])    // ROUND(1000*0.7)
        // trust int 80 → valueFit(80,0,100)=80.0 (FLOAT 컬럼).
        assertEquals(80.0, p["trust"])
    }

    @Test
    fun `scenario 1010 occupied - adds def and wall percent`() {
        val cities = listOf(
            city(
                id = 3, nationId = 7, name = "성도",
                pop = 1, popMax = 100000, agri = 1, agriMax = 5000,
                comm = 1, commMax = 5000, secu = 1, secuMax = 2000,
                def = 1, defMax = 6000, wall = 1, wallMax = 4000, trust = 30.0,
            ),
            city(id = 4, nationId = 0, name = "free곳"), // free → occupied 타깃에서 제외
        )
        val action = ChangeCityAction.build(
            buildJsonArray {
                add(JsonPrimitive("occupied"))
                add(obj("pop" to "70%", "agri" to "70%", "comm" to "70%", "secu" to "70%", "trust" to 80, "def" to "70%", "wall" to "70%"))
            }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        val p = ctx.applied!!.getValue(3)
        assertEquals(setOf(3), ctx.applied!!.keys)
        assertEquals(70000, p["pop"])
        assertEquals(3500, p["agri"])
        assertEquals(3500, p["comm"])
        assertEquals(1400, p["secu"])
        assertEquals(4200, p["def"])   // ROUND(6000*0.7)
        assertEquals(2800, p["wall"])  // ROUND(4000*0.7)
        assertEquals(80.0, p["trust"])
    }

    // ── 2. int 분기 (least(keyMax, max(0,value))) + trade ──────────────────────

    @Test
    fun `int value clamps to keyMax and floors at zero`() {
        val cities = listOf(city(id = 1, nationId = 1, pop = 0, popMax = 500))
        // value 999 > keyMax 500 → least=500. value -5 (음수)면 max(0,-5)=0 (별도 케이스).
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("pop" to 999)) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        assertEquals(500, ctx.applied!!.getValue(1)["pop"])
    }

    @Test
    fun `negative int floors at zero before keyMax clamp`() {
        val cities = listOf(city(id = 1, nationId = 1, pop = 0, popMax = 500))
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("pop" to -5)) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        assertEquals(0, ctx.applied!!.getValue(1)["pop"]) // least(500, max(0,-5))
    }

    @Test
    fun `trade int passes through valueFit 95 to 105`() {
        val cities = listOf(city(id = 1, nationId = 1))
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("trade" to 100)) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        assertEquals(100, ctx.applied!!.getValue(1)["trade"])
    }

    @Test
    fun `trade clamps below 95 and above 105`() {
        val cities = listOf(city(id = 1, nationId = 1), city(id = 2, nationId = 1))
        val low = ChangeCityAction.build(buildJsonArray { add(JsonPrimitive("all")); add(obj("trade" to 10)) }.toList())
        val lctx = RecordingCtx(cities)
        low.run(lctx)
        assertEquals(95, lctx.applied!!.getValue(1)["trade"])

        val high = ChangeCityAction.build(buildJsonArray { add(JsonPrimitive("all")); add(obj("trade" to 999)) }.toList())
        val hctx = RecordingCtx(cities)
        high.run(hctx)
        assertEquals(105, hctx.applied!!.getValue(1)["trade"])
    }

    // ── 3. math 분기: +N / -N general + _max ──────────────────────────────────

    @Test
    fun `scenario 903 plus math general clamps to keyMax and floors at zero`() {
        // ["ChangeCity","occupied",{agri:"+1200",comm:"+1200",pop:"+60000"}] 류.
        val cities = listOf(
            city(id = 1, nationId = 2, agri = 100, agriMax = 800, comm = 100, commMax = 5000, pop = 50000, popMax = 100000),
        )
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("occupied")); add(obj("agri" to "+1200", "comm" to "+1200", "pop" to "+60000")) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        val p = ctx.applied!!.getValue(1)
        // agri: least(800, greatest(0, ROUND(100+1200))) = least(800,1300)=800 (keyMax clamp)
        assertEquals(800, p["agri"])
        // comm: least(5000, 100+1200)=1300
        assertEquals(1300, p["comm"])
        // pop: least(100000, 50000+60000)=least(100000,110000)=100000
        assertEquals(100000, p["pop"])
    }

    @Test
    fun `minus math floors at zero`() {
        val cities = listOf(city(id = 1, nationId = 1, agri = 100, agriMax = 5000))
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("agri" to "-300")) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        // least(5000, greatest(0, ROUND(100-300))) = least(5000, max(0,-200)) = 0
        assertEquals(0, ctx.applied!!.getValue(1)["agri"])
    }

    @Test
    fun `scenario 913 max-key plus math uses greatest-0 without keyMax clamp`() {
        // ["ChangeCity",[...],{agri_max:"+1000",pop_max:"+100000"}] 류 — key가 _max라 greatest(0,key+v)만.
        val cities = listOf(city(id = 1, nationId = 0, agriMax = 4000, popMax = 200000))
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("free")); add(obj("agri_max" to "+1000", "pop_max" to "+100000")) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        val p = ctx.applied!!.getValue(1)
        assertEquals(5000, p["agri_max"])   // greatest(0, ROUND(4000+1000))
        assertEquals(300000, p["pop_max"])  // greatest(0, ROUND(200000+100000)) — keyMax clamp 없음(증가 가능)
    }

    @Test
    fun `max-key minus math floors at zero only`() {
        val cities = listOf(city(id = 1, nationId = 1, defMax = 500))
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("def_max" to "-9999")) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        assertEquals(0, ctx.applied!!.getValue(1)["def_max"]) // greatest(0, 500-9999)=0
    }

    // ── 4. float (곱셈) 분기 + ROUND half-away ────────────────────────────────

    @Test
    fun `float multiply rounds half-away and clamps to keyMax`() {
        val cities = listOf(city(id = 1, nationId = 1, agri = 1000, agriMax = 1200))
        // 1.5 (소수) → least(1200, ROUND(1000*1.5))=least(1200,1500)=1200
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("agri" to 1.5)) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        assertEquals(1200, ctx.applied!!.getValue(1)["agri"])
    }

    @Test
    fun `float multiply ROUND is half-away-from-zero not half-even`() {
        // 0.5 경계: ROUND(2500*0.5)=ROUND(1250.0)=1250; ROUND(2501*0.5)=ROUND(1250.5)=1251 (half-away).
        val cities = listOf(city(id = 1, nationId = 1, comm = 2501, commMax = 99999))
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("comm" to 0.5)) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        assertEquals(1251, ctx.applied!!.getValue(1)["comm"])
    }

    @Test
    fun `trust float multiplies and clamps to 100`() {
        val cities = listOf(city(id = 1, nationId = 1, trust = 80.0))
        // 2.0 → least(100, 80*2)=least(100,160)=100
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("trust" to 2.0)) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        assertEquals(100.0, ctx.applied!!.getValue(1)["trust"])
    }

    @Test
    fun `trust percent rounds the percent then clamps 0 to 100`() {
        val cities = listOf(city(id = 1, nationId = 1, trust = 10.0))
        // "150.4%" → round(150.4)=150 → valueFit(150,0,100)=100 (상수, trust 현재값 무관).
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("trust" to "150.4%")) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        assertEquals(100.0, ctx.applied!!.getValue(1)["trust"])
    }

    @Test
    fun `trust math op adds and clamps`() {
        val cities = listOf(city(id = 1, nationId = 1, trust = 90.0))
        // "+30" → least(100, greatest(0, 90+30))=least(100,120)=100
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("trust" to "+30")) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        assertEquals(100.0, ctx.applied!!.getValue(1)["trust"])
    }

    // ── 5. target 분기: cities by id / by name + all ─────────────────────────

    @Test
    fun `target cities by name selects only matching names`() {
        val cities = listOf(
            city(id = 1, nationId = 1, name = "낙양", agri = 100, agriMax = 5000),
            city(id = 2, nationId = 1, name = "장안", agri = 100, agriMax = 5000),
            city(id = 3, nationId = 1, name = "업", agri = 100, agriMax = 5000),
        )
        val action = ChangeCityAction.build(
            buildJsonArray {
                add(buildJsonArray { add(JsonPrimitive("cities")); add(JsonPrimitive("낙양")); add(JsonPrimitive("업")) })
                add(obj("agri" to "+100"))
            }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        assertEquals(setOf(1, 3), ctx.applied!!.keys)
    }

    @Test
    fun `target all selects every city`() {
        val cities = listOf(city(id = 1, nationId = 0), city(id = 2, nationId = 9))
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("trade" to 100)) }.toList(),
        )
        val ctx = RecordingCtx(cities)
        action.run(ctx)
        assertEquals(setOf(1, 2), ctx.applied!!.keys)
    }

    // ── 6. 검증 throw (생성자 단계) ──────────────────────────────────────────

    @Test
    fun `unsupported key throws`() {
        assertFailsWith<IllegalArgumentException> {
            ChangeCityAction.build(buildJsonArray { add(JsonPrimitive("all")); add(obj("gold" to 100)) }.toList())
        }
    }

    @Test
    fun `unknown string pattern throws`() {
        assertFailsWith<IllegalArgumentException> {
            ChangeCityAction.build(buildJsonArray { add(JsonPrimitive("all")); add(obj("pop" to "abc")) }.toList())
        }
    }

    @Test
    fun `divide by zero throws at eval`() {
        val cities = listOf(city(id = 1, nationId = 1, agri = 100, agriMax = 5000))
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("agri" to "/0")) }.toList(),
        )
        assertFailsWith<IllegalArgumentException> { action.run(RecordingCtx(cities)) }
    }

    @Test
    fun `negative float multiplier throws at eval`() {
        val cities = listOf(city(id = 1, nationId = 1, agri = 100, agriMax = 5000))
        // -0.5 (소수,음수) → float 분기 → require(v>=0) throw.
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("agri" to -0.5)) }.toList(),
        )
        assertFailsWith<IllegalArgumentException> { action.run(RecordingCtx(cities)) }
    }

    // ── 7. draw 0 보장 + factory 등록 ─────────────────────────────────────────

    @Test
    fun `leaf registers into the F2 factory by name`() {
        val factory = EventActionFactory()
        ChangeCityAction.register(factory)
        assertTrue(factory.has("ChangeCity"))

        val raw = RawAction(
            "ChangeCity",
            buildJsonArray { add(JsonPrimitive("all")); add(obj("trade" to 100)) }.toList(),
        )
        val action: EventAction = factory.create(raw)
        assertTrue(action is ChangeCityAction)
    }

    @Test
    fun `running against a base context that is not a ChangeCityContext throws`() {
        val bareEnv = object : EventActionContext {
            override val env: Map<String, Any?> = linkedMapOf("year" to 200, "month" to 1)
        }
        val action = ChangeCityAction.build(
            buildJsonArray { add(JsonPrimitive("all")); add(obj("trade" to 100)) }.toList(),
        )
        assertFailsWith<IllegalStateException> { action.run(bareEnv) }
    }
}
