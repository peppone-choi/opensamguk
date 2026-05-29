package opensamguk.logic.event

/**
 * FE1 — the sealed dynamic-event Condition DSL.
 *
 * Faithful port of PHP `sammo/Event/Condition.php` + the `sammo/Event/Condition/` leaf classes. Each leaf's
 * `eval(env)` returns the boolean verdict only. PHP returns `['value'=>..., 'chain'=>...]`, but the
 * `chain` is observational debug data the dispatcher discards (`EventHandler.php:19` gates the action
 * run on `$result['value']` ALONE) — the F2 ruling is to NOT model `chain` (G1 does not byte-match
 * the discarded result, plan FE3).
 *
 * The comparator semantics REUSE the existing constraint comparator core where the operand is scalar
 * ([RemainNation]); [Date]/[DateRelative] are PHP **2-tuple** `[year?, month?]` comparisons (PHP array
 * comparison: `==`/`!=` are full equality, `<`/`>`/`<=`/`>=` are element-wise lexicographic). When a
 * field is null in the Condition it is null on BOTH sides of the tuple, so a null slot never mixes
 * with a number — it only ever compares null-vs-null (equal) and the decision falls through to the
 * next slot, exactly as PHP does.
 *
 * `env` keys are the PHP lowercase keys the legacy conditions read: `year`, `month`, `startyear`,
 * `currentEventID` (F4 widens [opensamguk.logic.statview.WorldEnvBuilder] to carry them). [RemainNation]
 * reads the live nation count the dispatcher injects under [REMAIN_NATION_COUNT_KEY] (PHP
 * `getAllNationStaticInfo()` — a GLOBAL count, not an env date field).
 */
sealed interface EventCondition {

    /** PHP `Condition::eval($env)` → only the boolean `value`. `env == null` is the PHP null-env path. */
    fun eval(env: Map<String, Any?>?): Boolean

    companion object {
        /** The valid comparison operators shared by Date/DateRelative/RemainNation (PHP `AVAILABLE_CMP`). */
        val AVAILABLE_CMP: Set<String> = setOf("==", "!=", "<", ">", "<=", ">=")

        /** The 4 logic modes; value = "accepts >1 child" (PHP `Logic::AVAILABLE_LOGIC_NAME`). */
        val AVAILABLE_LOGIC: Map<String, Boolean> =
            mapOf("not" to false, "and" to true, "or" to true, "xor" to true)

        /**
         * env key under which the dispatcher injects the live GLOBAL nation count
         * (`count(getAllNationStaticInfo())`, `RemainNation.php:31`). Injected per-dispatch, NOT a
         * date field — RemainNation reads world state, not the calendar.
         */
        const val REMAIN_NATION_COUNT_KEY: String = "__remainNationCount"

        private fun requireCmp(cmp: String) {
            require(cmp in AVAILABLE_CMP) { "올바르지 않은 비교연산자입니다" }
        }

        /** PHP 2-tuple lexicographic comparison: `[a0,a1] <cmp> [b0,b1]`; null slots compare null-vs-null. */
        internal fun compareTuple(lhs: Pair<Int?, Int?>, rhs: Pair<Int?, Int?>, cmp: String): Boolean {
            val (a0, a1) = lhs
            val (b0, b1) = rhs
            // Element-wise: first compare slot 0, then slot 1 (PHP array comparison order).
            val c0 = cmpSlot(a0, b0)
            val c = if (c0 != 0) c0 else cmpSlot(a1, b1)
            return when (cmp) {
                "==" -> a0 == b0 && a1 == b1
                "!=" -> !(a0 == b0 && a1 == b1)
                "<" -> c < 0
                ">" -> c > 0
                "<=" -> c <= 0
                ">=" -> c >= 0
                else -> error("invalid comparator $cmp")
            }
        }

        /** null == null → 0; otherwise both are non-null Ints (a null slot is null on both sides). */
        private fun cmpSlot(a: Int?, b: Int?): Int = when {
            a == null && b == null -> 0
            a == null -> -1
            b == null -> 1
            else -> a.compareTo(b)
        }

        private fun compareScalar(lhs: Int, rhs: Int, cmp: String): Boolean = when (cmp) {
            "==" -> lhs == rhs
            "!=" -> lhs != rhs
            "<" -> lhs < rhs
            ">" -> lhs > rhs
            "<=" -> lhs <= rhs
            ">=" -> lhs >= rhs
            else -> error("invalid comparator $cmp")
        }

        private fun intEnv(env: Map<String, Any?>, key: String): Int {
            val v = env[key] ?: throw IllegalArgumentException("env에 ${key}가 없습니다.")
            return (v as Number).toInt()
        }
    }

    /** PHP `Condition\ConstBool` — a fixed boolean (ConstBool.php:11-16). */
    data class ConstBool(val value: Boolean) : EventCondition {
        override fun eval(env: Map<String, Any?>?): Boolean = value
    }

    /**
     * PHP `Condition\Date` (Date.php:21-77). Absolute calendar gate; 2-tuple `[year?, month?]`.
     * `env == null` short-circuits to false (Date.php:37-42) BEFORE the missing-key throw.
     */
    class Date(val cmp: String, val year: Int?, val month: Int?) : EventCondition {
        init {
            requireCmp(cmp)
            require(!(year == null && month == null)) { "year과 month가 둘다 null일 수 없습니다." }
        }

        override fun eval(env: Map<String, Any?>?): Boolean {
            if (env == null) return false
            if (year != null && !env.containsKey("year")) {
                throw IllegalArgumentException("env에 year가 없습니다.")
            }
            if (month != null && !env.containsKey("month")) {
                throw IllegalArgumentException("env에 month가 없습니다.")
            }
            val lhs = (if (year != null) intEnv(env, "year") else null) to
                (if (month != null) intEnv(env, "month") else null)
            return compareTuple(lhs, year to month, cmp)
        }
    }

    /**
     * PHP `Condition\DateRelative` (DateRelative.php:20-82). Like [Date] but the year slot is
     * `env['year'] - env['startyear']` (founding-relative). `env == null` → false.
     */
    class DateRelative(val cmp: String, val year: Int?, val month: Int?) : EventCondition {
        init {
            requireCmp(cmp)
            require(!(year == null && month == null)) { "year과 month가 둘다 null일 수 없습니다." }
        }

        override fun eval(env: Map<String, Any?>?): Boolean {
            if (env == null) return false
            if (year != null) {
                if (!env.containsKey("year")) throw IllegalArgumentException("env에 year가 없습니다.")
                if (!env.containsKey("startyear")) throw IllegalArgumentException("env에 startyear가 없습니다.")
            }
            if (month != null && !env.containsKey("month")) {
                throw IllegalArgumentException("env에 month가 없습니다.")
            }
            val relYear = if (year != null) intEnv(env, "year") - intEnv(env, "startyear") else null
            val lhs = relYear to (if (month != null) intEnv(env, "month") else null)
            return compareTuple(lhs, year to month, cmp)
        }
    }

    /**
     * PHP `Condition\RemainNation` (RemainNation.php:20-49). Compares a comparator against the GLOBAL
     * remaining-nation count (`count(getAllNationStaticInfo())`), NOT an env date field. The dispatcher
     * injects the live `world.nations` count under [REMAIN_NATION_COUNT_KEY].
     */
    class RemainNation(val cmp: String, val cnt: Int) : EventCondition {
        init {
            requireCmp(cmp)
        }

        override fun eval(env: Map<String, Any?>?): Boolean {
            val count = (env?.get(REMAIN_NATION_COUNT_KEY) as? Number)?.toInt()
                ?: throw IllegalArgumentException("env에 $REMAIN_NATION_COUNT_KEY 가 없습니다.")
            return compareScalar(count, cnt, cmp)
        }
    }

    /**
     * PHP `Condition\Logic` (Logic.php:18-108). `and` (false short-circuit) / `or` (true
     * short-circuit) / `xor` (left-to-right fold) / `not` (negates a SINGLE child, throws on >1).
     */
    class Logic(mode: String, val conditions: List<EventCondition>) : EventCondition {
        val mode: String = mode.lowercase()

        init {
            require(this.mode in AVAILABLE_LOGIC) { "첫번째 인자는 not, and, or, xor 중 하나여야 합니다." }
            if (AVAILABLE_LOGIC[this.mode] == false && conditions.size > 1) {
                throw IllegalArgumentException("조건을 하나만 받을 수 있습니다.")
            }
        }

        override fun eval(env: Map<String, Any?>?): Boolean = when (mode) {
            "not" -> !conditions[0].eval(env)
            "and" -> {
                var value = true
                for (c in conditions) {
                    if (!c.eval(env)) {
                        value = false
                        break
                    }
                }
                value
            }
            "or" -> {
                var value = false
                for (c in conditions) {
                    if (c.eval(env)) {
                        value = true
                        break
                    }
                }
                value
            }
            "xor" -> {
                var value = false
                for (c in conditions) value = (value != c.eval(env))
                value
            }
            else -> error("올바르지 않은 mode.")
        }
    }

    /**
     * PHP `Condition\Interval` (Interval.php:6,14) — explicitly Not Yet Implemented; both the
     * constructor AND eval throw. Ported as a loud error node so any scenario wiring it fails fast.
     */
    object Interval : EventCondition {
        override fun eval(env: Map<String, Any?>?): Boolean =
            throw NotImplementedError("Not Yet Implmented.")
    }

    /**
     * PHP `Event/Engine` (Engine.php:9-11) — an empty placeholder ("미래의 누군가" =P). Modeled as an
     * inert stub that evaluates false (it never gates a real game event).
     */
    object Engine : EventCondition {
        override fun eval(env: Map<String, Any?>?): Boolean = false
    }
}
