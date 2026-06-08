package opensamguk.logic.world

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import opensamguk.logic.event.EventAction
import opensamguk.logic.event.EventActionContext
import opensamguk.logic.event.EventActionFactory
import opensamguk.logic.util.phpRound
import opensamguk.logic.util.phpToInt
import opensamguk.logic.util.valueFit
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * A3 — ChangeCity (PHP `Event/Action/ChangeCity.php:1-190`).
 *
 * 시나리오 개시(보통 1월) 시 도시의 내정 수치(인구/농업/상업/치안/민심/방어/성벽/교역 및 각 max)를
 * 일괄 패치하는 월드 이벤트. PHP는 각 키마다 SQL 식(`least`/`greatest`/`ROUND`/`* %d`)을 만들어
 * `DB::db()->update('city', $queries, 'city IN %li', $cities)`로 한 번에 갱신한다.
 *
 * **draw 0** — RNG 일절 사용 안 함. 순수 결정적 도시 row 패치다.
 *
 * 포팅 전략: SQL 식을 Kotlin에서 도시의 *현재 필드값*에 대해 그대로 평가한다. MySQL `ROUND(x,0)`은
 * half-away-from-zero이므로 [phpRound](=`Util::round`)와 동일하다. `least`/`greatest`/`max(0,..)`는
 * `min`/`max`로, `%d`(곱셈 float)는 그대로 곱한 뒤 `ROUND`한다.
 *
 * 값 타입 분기(PHP `is_int`/`is_float`/`is_string`)는 JSON 디코딩 결과로 판별한다:
 *  - 문자열 → `N%`(REGEXP_PERCENT) 또는 `±/N`(REGEXP_MATH) 패턴
 *  - 정수형 숫자(소수점 없음) → int 분기
 *  - 소수점 있는 숫자 → float(곱셈) 분기
 *
 * 월드 seam은 [ChangeCityContext](daemon이 `env`로 주입)로, 대상 도시 목록 + 패치 sink를 공급한다.
 * F2가 base [EventActionContext]/[EventActionFactory]를 소유하며, 이 leaf는 이름으로 자기 자신만 등록한다.
 */
class ChangeCityAction(
    private val target: ChangeCityTarget,
    /** 키→값(JSON) 맵. PHP `$actions`. 생성자에서 검증 후 [resolvePatch]에서 도시별로 평가된다. */
    private val actions: Map<String, ChangeCityValue>,
) : EventAction {

    override fun run(ctx: EventActionContext) {
        val cityCtx = ctx as? ChangeCityContext
            ?: error(
                "ChangeCity needs the richer ChangeCityContext (target cities + patch sink) — " +
                    "the daemon must supply it at dispatch",
            )

        // PHP getTargetCities($env): targetType에 따라 도시 목록을 고른다.
        val targets = cityCtx.targetCities(target)

        // PHP: 각 도시에 동일 $queries(키별 SQL 식)를 적용. 도시별 현재값으로 식을 평가한다.
        val patches = LinkedHashMap<Int, Map<String, Number>>()
        for (city in targets) {
            patches[city.id] = resolvePatch(city)
        }

        // PHP: DB::db()->update('city', $queries, 'city IN %li', $cities)
        cityCtx.applyCityPatches(patches)
    }

    /** 한 도시의 현재값에 대해 모든 [actions] 키의 SQL 식을 평가해 새 필드값 맵을 만든다. */
    private fun resolvePatch(city: ChangeCityCity): Map<String, Number> {
        val patch = LinkedHashMap<String, Number>()
        // PHP $queries는 actions 순회 순서를 보존(LinkedHashMap) → 동일 순서로 평가.
        for ((key, value) in actions) {
            patch[key] = when (key) {
                "trust" -> evalTrust(value, city.trust)
                "trade" -> evalTrade(value)
                else -> evalGeneric(key, value, city)
            }
        }
        return patch
    }

    /**
     * PHP `genSQLTrust` (ChangeCity.php:80-109). 민심은 max가 100으로 고정이라 처리가 다르다.
     *  - float → `least(100, trust * value)` (value<0이면 throw)
     *  - int → `valueFit(value, 0, 100)` (상수)
     *  - `N%` → `valueFit(Util::round(N), 0, 100)` (상수)
     *  - `±/N` → `least(100, greatest(0, trust OP value))` (PHP의 리터럴 SQL은 괄호/`,0` 오타로 실제로는
     *    실행 불가 — 어느 시나리오에서도 도달하지 않음. 의도된 수식으로 충실 포팅, 보고서에 명시.)
     *
     * 반환은 Double(컬럼 trust FLOAT). PHP의 `%i` int 캐스트(`valueFit` 결과)도 FLOAT 컬럼에 그대로 저장된다.
     */
    private fun evalTrust(value: ChangeCityValue, curTrust: Double): Double = when (value) {
        is ChangeCityValue.FloatVal -> {
            require(value.v >= 0.0) { "음수를 곱할 수 없습니다." }
            // least(100, trust * value)
            minOf(100.0, curTrust * value.v)
        }
        is ChangeCityValue.IntVal ->
            // %i, valueFit(value, 0, 100)
            valueFit(value.v.toDouble(), 0.0, 100.0)
        is ChangeCityValue.PercentStr -> {
            // %i, valueFit(Util::round(matches[1]), 0, 100)
            val rounded = phpRound(value.v)
            valueFit(rounded.toDouble(), 0.0, 100.0)
        }
        is ChangeCityValue.MathStr -> {
            if (value.op == '/' && value.v == 0.0) throw IllegalArgumentException("0으로 나눌 수 없습니다.")
            // 의도된 수식: least(100, greatest(0, trust OP value)).
            minOf(100.0, maxOf(0.0, applyOp(curTrust, value.op, value.v)))
        }
    }

    /**
     * PHP `trade` 분기 (ChangeCity.php:64-72). null → null(여기선 미지원 입력은 생성자에서 거름),
     * 그 외 → `Util::valueFit((float)value, 95, 105)`. 입력은 보통 int 100(시나리오 다수).
     * 반환은 Int(컬럼 trade는 95..105 정수).
     */
    private fun evalTrade(value: ChangeCityValue): Int {
        val raw = when (value) {
            is ChangeCityValue.IntVal -> value.v.toDouble()
            is ChangeCityValue.FloatVal -> value.v
            // PHP는 trade에 (float) 캐스트만 하므로 숫자 문자열이면 그 값으로 평가된다.
            is ChangeCityValue.PercentStr -> value.v   // "N%" → (float)"N%" = N (PHP 캐스트 규칙)
            is ChangeCityValue.MathStr -> value.numericPrefix() // "+N" → (float)"+N" = N
        }
        // Util::valueFit((float)value, 95, 105) → 정수 컬럼이므로 toInt (95..105 범위라 손실 없음).
        return valueFit(raw, 95.0, 105.0).toInt()
    }

    /**
     * PHP `genSQLGeneric` (ChangeCity.php:111-151). keyMax = key + "_max".
     *  - float → `least(keyMax, ROUND(key * value, 0))` (value<0 throw)
     *  - int → `least(keyMax, max(0, value))`
     *  - `N%` → `ROUND(keyMax * (Util::round(N)/100), 0)` (퍼센트는 key가 아니라 keyMax 기준!)
     *  - key가 `_max`로 끝나는 `±/N` → `greatest(0, ROUND(key OP value, 0))`
     *  - 일반 `±/N` → `least(keyMax, greatest(0, ROUND(key OP value, 0)))`
     */
    private fun evalGeneric(key: String, value: ChangeCityValue, city: ChangeCityCity): Int {
        val cur = city.field(key)
        // PHP genSQLGeneric: `$keyMax = $key.'_max'`는 문자열로만 만들어두고, 실제로 SQL에 끼워 넣는
        // 분기에서만 참조한다. key 자체가 `_max`일 때의 math 분기는 `greatest(0, ROUND(`key` OP v))`로
        // `$keyMax`(=`xxx_max_max`, 존재하지 않는 컬럼)를 절대 참조하지 않으므로, curMax는 그 값을 쓰는
        // 분기에서만 lazy하게 해석한다(불필요한 city.field(keyMax) 조회가 throw하지 않도록).
        val keyMax = "${key}_max"
        val curMax: Int by lazy { city.field(keyMax) }

        return when (value) {
            is ChangeCityValue.FloatVal -> {
                require(value.v >= 0.0) { "음수를 곱할 수 없습니다." }
                // least(keyMax, ROUND(key * value, 0))
                minOf(curMax, mysqlRound(cur * value.v))
            }
            is ChangeCityValue.IntVal ->
                // least(keyMax, max(0, value))
                minOf(curMax, maxOf(0, value.v))
            is ChangeCityValue.PercentStr -> {
                // ROUND(keyMax * (Util::round(N)/100), 0) — clamp 없음(원문 그대로).
                val n = phpRound(value.v)
                mysqlRound(curMax * (n / 100.0))
            }
            is ChangeCityValue.MathStr -> {
                if (value.op == '/' && value.v == 0.0) throw IllegalArgumentException("0으로 나눌 수 없습니다.")
                // genSQLGeneric의 math는 `%i`(정수 placeholder) — operand를 toInt(truncate-toward-zero)
                // 한다(trust math는 `%d` float이라 다름 — evalTrust 참고).
                val operand = phpToInt(value.v).toDouble()
                if (key.endsWith("_max")) {
                    // key 자체가 최대치면: greatest(0, ROUND(key OP value, 0)) — keyMax 참조 안 함(PHP 동일).
                    maxOf(0, mysqlRound(applyOp(cur.toDouble(), value.op, operand)))
                } else {
                    // 일반: least(keyMax, greatest(0, ROUND(key OP value, 0)))
                    minOf(curMax, maxOf(0, mysqlRound(applyOp(cur.toDouble(), value.op, operand))))
                }
            }
        }
    }

    /** PHP REGEXP_MATH의 사칙 연산자 `+ - * /`를 적용. SQL `key OP value`와 동일. */
    private fun applyOp(base: Double, op: Char, v: Double): Double = when (op) {
        '+' -> base + v
        '-' -> base - v
        '*' -> base * v
        '/' -> base / v
        else -> throw IllegalArgumentException("알 수 없는 연산자: $op")
    }

    companion object {
        const val NAME: String = "ChangeCity"

        /** PHP 지원 키 화이트리스트 (ChangeCity.php:8-24, AVAILABLE_KEY). 순서/구성 동일. */
        val AVAILABLE_KEY: Set<String> = linkedSetOf(
            "pop", "agri", "comm", "secu", "trust", "def", "wall", "trade",
            "pop_max", "agri_max", "comm_max", "secu_max", "def_max", "wall_max",
        )

        // 123.5% → [1]=float (REGEXP_PERCENT)
        private val REGEXP_PERCENT = Regex("""^(\d+(\.\d+)?)%$""")

        // +30 → [1]=기호, [2]=float (REGEXP_MATH)
        private val REGEXP_MATH = Regex("""^([+\-/*])(\d+(\.\d+)?)$""")

        /**
         * MySQL `ROUND(x, 0)` = half-away-from-zero. [phpRound]는 Int를 돌려주므로 Double로 받아 동일하게
         * BigDecimal HALF_UP로 평가(음수 안전, half-away). PHP `Util::round`와 byte-동일.
         */
        private fun mysqlRound(x: Double): Int =
            BigDecimal.valueOf(x).setScale(0, RoundingMode.HALF_UP).toInt()

        /**
         * PHP `Action::build`이 넘긴 args(verbatim)로 leaf를 만든다. PHP 생성자 시그니처는
         * `__construct($target, array $actions)` → args[0]=target, args[1]=actions(연관배열).
         *
         * 생성자 검증(ChangeCity.php:35-77)을 그대로 수행: target 정규화 + AVAILABLE_KEY 화이트리스트 +
         * 값 타입(int/float/string) 검증 + 문자열 패턴 파싱(미리 분류해 [ChangeCityValue]로 저장).
         */
        fun build(args: List<JsonElement>): ChangeCityAction {
            require(args.size >= 2) { "ChangeCity는 target과 actions가 필요합니다." }
            val target = parseTarget(args[0])
            val rawActions = args[1] as? JsonObject
                ?: throw IllegalArgumentException("actions는 객체여야 합니다.")

            val parsed = LinkedHashMap<String, ChangeCityValue>()
            for ((key, valueJson) in rawActions) {
                if (key !in AVAILABLE_KEY) {
                    throw IllegalArgumentException("지원하지 않는 city 인자입니다 :$key")
                }
                parsed[key] = parseValue(valueJson)
            }
            return ChangeCityAction(target, parsed)
        }

        /**
         * PHP 생성자의 target 정규화 (ChangeCity.php:35-47):
         *  - falsy(null/빈문자) → 'all'
         *  - 문자열 → 그 type
         *  - 배열 → [type, ...args]
         */
        private fun parseTarget(json: JsonElement): ChangeCityTarget = when (json) {
            is JsonNull -> ChangeCityTarget("all", emptyList())
            is JsonPrimitive -> {
                if (json.isString) {
                    val s = json.content
                    if (s.isEmpty()) ChangeCityTarget("all", emptyList())
                    else ChangeCityTarget(s, emptyList())
                } else {
                    throw IllegalArgumentException("올바르지 않은 targetType 입니다.")
                }
            }
            is JsonArray -> {
                require(json.isNotEmpty()) { "올바르지 않은 targetType 입니다." }
                val type = (json[0] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("올바르지 않은 targetType 입니다.")
                val rest = json.drop(1).map { (it as JsonPrimitive).content }
                ChangeCityTarget(type, rest)
            }
            else -> throw IllegalArgumentException("올바르지 않은 targetType 입니다.")
        }

        /**
         * PHP의 값 타입 검증 + 문자열 패턴 파싱 (ChangeCity.php:55-74). int/float/string만 허용.
         * 문자열은 `N%` 또는 `±/N` 패턴이어야 한다(아니면 평가 시 throw — 여기선 분류만, 미상은 MathStr로
         * 떨어지지 않도록 미리 거른다).
         */
        private fun parseValue(json: JsonElement): ChangeCityValue {
            val prim = json as? JsonPrimitive
                ?: throw IllegalArgumentException("int, float, string이어야 합니다.")
            if (prim.isString) {
                val s = prim.content
                val pct = REGEXP_PERCENT.matchEntire(s)
                if (pct != null) return ChangeCityValue.PercentStr(pct.groupValues[1].toDouble(), s)
                val math = REGEXP_MATH.matchEntire(s)
                if (math != null) {
                    return ChangeCityValue.MathStr(math.groupValues[1][0], math.groupValues[2].toDouble(), s)
                }
                throw IllegalArgumentException("알 수 없는 패턴입니다.")
            }
            // 숫자: 소수점/지수 없는 정수면 int, 아니면 float (PHP is_int/is_float).
            val content = prim.content
            return if (content.contains('.') || content.contains('e') || content.contains('E')) {
                ChangeCityValue.FloatVal(content.toDouble())
            } else {
                ChangeCityValue.IntVal(content.toLong().toInt())
            }
        }

        /** F2 [EventActionFactory]에 이름으로 등록 (plan §append protocol, leaf 1회 터치). */
        fun register(factory: EventActionFactory): EventActionFactory =
            factory.register(NAME) { args -> build(args) }
    }
}

/**
 * PHP `$target` 정규화 결과. [type]은 'all'|'free'|'occupied'|'cities', [args]는 'cities'일 때의
 * 도시 id 또는 이름 목록(ChangeCity.php:153-179 getTargetCities 분기).
 */
data class ChangeCityTarget(val type: String, val args: List<String>)

/** ChangeCity가 패치할 도시의 현재값 투영 (PHP `city` row). trust는 FLOAT. */
data class ChangeCityCity(
    val id: Int,
    val nationId: Int,
    val name: String,
    val pop: Int, val popMax: Int,
    val agri: Int, val agriMax: Int,
    val comm: Int, val commMax: Int,
    val secu: Int, val secuMax: Int,
    val def: Int, val defMax: Int,
    val wall: Int, val wallMax: Int,
    val trust: Double,
) {
    /** key/keyMax 토큰으로 현재 정수 필드값을 읽는다 (genSQLGeneric의 %b 컬럼 참조). */
    fun field(key: String): Int = when (key) {
        "pop" -> pop; "pop_max" -> popMax
        "agri" -> agri; "agri_max" -> agriMax
        "comm" -> comm; "comm_max" -> commMax
        "secu" -> secu; "secu_max" -> secuMax
        "def" -> def; "def_max" -> defMax
        "wall" -> wall; "wall_max" -> wallMax
        else -> throw IllegalArgumentException("알 수 없는 city 필드: $key")
    }
}

/** 미리 분류·파싱된 ChangeCity 액션 값 (PHP의 is_int/is_float/is_string + 정규식 분기). */
sealed interface ChangeCityValue {
    /** PHP is_int. */
    data class IntVal(val v: Int) : ChangeCityValue

    /** PHP is_float (곱셈 분기). */
    data class FloatVal(val v: Double) : ChangeCityValue

    /** REGEXP_PERCENT: "N%" → v=N(float), raw=원문. */
    data class PercentStr(val v: Double, val raw: String) : ChangeCityValue

    /** REGEXP_MATH: "±/N" → op, v=N(float), raw=원문. */
    data class MathStr(val op: Char, val v: Double, val raw: String) : ChangeCityValue {
        /** trade 분기의 `(float)"+N"` 캐스트 모사: 부호 포함 숫자값. */
        fun numericPrefix(): Double = when (op) {
            '-' -> -v
            else -> v // '+','*','/' 접두는 (float) 캐스트 시 부호 없는 v (PHP는 선행 '+'를 무시)
        }
    }
}

/**
 * ChangeCity leaf가 필요로 하는 richer dispatch context (daemon이 공급). base [EventActionContext]의
 * env는 year/month/currentEventID를 운반하고(plan §append protocol), 이 컨텍스트가 추가로:
 *  - [targetCities] — PHP getTargetCities: targetType별 도시 목록(all/free/occupied/cities). DB PK
 *    오름차순(=일관 순회 순서)으로 공급. leaf는 재정렬하지 않는다.
 *  - [applyCityPatches] — 도시별 필드 패치 sink. PHP `DB::db()->update('city', $queries, 'city IN %li')`.
 */
interface ChangeCityContext : EventActionContext {
    fun targetCities(target: ChangeCityTarget): List<ChangeCityCity>
    fun applyCityPatches(patches: Map<Int, Map<String, Number>>)
}
