package opensamguk.logic.log

import opensamguk.common.josa.JosaUtil
import opensamguk.logic.util.numberFormat

/**
 * P3 / AREA F7 / Task FG2 — JosaUtil-backed history-string catalog scaffold.
 *
 * Byte-exact templates transcribed from PHP frozen historical baseline (ADR-LITE-042; not current product authority). These are the strings the gate (G1)
 * byte-matches across the monthly tick (consolidated OQ #12); A1/A3/A5/B5 consume them. Each
 * template reproduces the literal color/tag markup (`<W>/<R>/<G>/<C>/<Y>/<D>/<S>/<b>/【…】`),
 * the JosaUtil josa selection, and `number_format` comma grouping (`numberFormat`) exactly.
 *
 * Source citations are noted per template. JosaUtil lives in `:common` (which `:logic` depends
 * on); `numberFormat` is the GREEN `logic/util/PhpRound.kt` helper (`"%,d"`).
 */
object HistoryTokens {

    // ── 봄/가을 income (global, ProcessIncome.php:111 / :195) ────────────────────────────────
    fun springIncomeGlobal(): String = "<W><b>【지급】</b></>봄이 되어 봉록에 따라 자금이 지급됩니다."
    fun autumnIncomeGlobal(): String = "<W><b>【지급】</b></>가을이 되어 봉록에 따라 군량이 지급됩니다."

    // ── 수입 / 봉급 per-general action lines (ProcessIncome.php:85/105 gold, :170/189 rice) ───
    fun goldIncomeLine(income: Int): String = "이번 수입은 금 <C>${numberFormat(income)}</>입니다."
    fun riceIncomeLine(income: Int): String = "이번 수입은 쌀 <C>${numberFormat(income)}</>입니다."
    fun goldSalaryLine(gold: Int): String = "봉급으로 금 <C>${numberFormat(gold)}</>을 받았습니다."
    fun riceSalaryLine(rice: Int): String = "봉급으로 쌀 <C>${numberFormat(rice)}</>을 받았습니다."

    // ── 고립 supply-loss (UpdateCitySupply.php:118; josaYi via pick(name,'이')) ───────────────
    fun isolatedCity(cityName: String): String {
        val josaYi = JosaUtil.pick(cityName, "이")
        return "<R><b>【고립】</b></><G><b>$cityName</b></>$josaYi 보급이 끊겨 <R>미지배</> 도시가 되었습니다."
    }

    // ── 작위 level-up (UpdateNationLevel.php:86-114) + NEW 8/9 (extend the case-7 옹립 pattern) ─
    /**
     * Global 【작위】 history line for a nation level-up to [level].
     *
     * The PHP `switch ($nationlevel)`:
     *   - 7/8/9 → 옹립 (enthronement). 8/9 reuse the case-7 옹립 pattern with the NEW level names
     *     (대황제/천자) — DEFINED by this plan (F7). Note the SPACE-separated `{nationName} {old}`.
     *   - 6     → 책봉 (investiture).
     *   - 5/4/3 → 임명 (appointment).
     *   - 2     → 독립 (independence) — uses josaRa on the nation name (`{nation}{라}는`).
     * Levels 0/1 produce no history line (the PHP switch has no case).
     */
    fun nationLevelUpGlobal(
        level: Int,
        nationName: String,
        lordName: String,
        oldLevelText: String,
        newLevelText: String,
    ): String {
        val josaYi = JosaUtil.pick(lordName, "이")
        val josaRo = JosaUtil.pick(newLevelText, "로")
        return when (level) {
            7, 8, 9 ->
                "<Y><b>【작위】</b></><D><b>$nationName</b></> $oldLevelText <Y>$lordName</>$josaYi <C>$newLevelText</>$josaRo 옹립되었습니다."
            6 ->
                "<Y><b>【작위】</b></><D><b>$nationName</b></>의 <Y>$lordName</>$josaYi <C>$newLevelText</>$josaRo 책봉되었습니다."
            5, 4, 3 ->
                "<Y><b>【작위】</b></><D><b>$nationName</b></>의 <Y>$lordName</>$josaYi <C>$newLevelText</>$josaRo 임명되었습니다."
            2 -> {
                val josaRa = JosaUtil.pick(nationName, "라")
                "<Y><b>【작위】</b></><Y>$lordName</>$josaYi 독립하여 <D><b>$nationName</b></>${josaRa}는 <C>$newLevelText</>$josaRo 나섰습니다."
            }
            else -> ""
        }
    }

    /** National 【작위】 history line (the un-prefixed, terse variant of [nationLevelUpGlobal]). */
    fun nationLevelUpNational(
        level: Int,
        nationName: String,
        lordName: String,
        oldLevelText: String,
        newLevelText: String,
    ): String {
        val josaYi = JosaUtil.pick(lordName, "이")
        val josaRo = JosaUtil.pick(newLevelText, "로")
        return when (level) {
            7, 8, 9 ->
                "<D><b>$nationName</b></> $oldLevelText <Y>$lordName</>$josaYi <C>$newLevelText</>$josaRo 옹립"
            6 ->
                "<D><b>$nationName</b></>의 <Y>$lordName</>$josaYi <C>$newLevelText</>$josaRo 책봉"
            5, 4, 3 ->
                "<D><b>$nationName</b></>의 <Y>$lordName</>$josaYi <C>$newLevelText</>$josaRo 임명됨"
            2 -> {
                val josaRa = JosaUtil.pick(nationName, "라")
                "<Y>$lordName</>$josaYi 독립하여 <D><b>$nationName</b></>${josaRa}는 <C>$newLevelText</>$josaRo 나서다"
            }
            else -> ""
        }
    }

    // ── 개전 / 종전 diplomacy (func_gamerule.php:360 / :384; josaWa pick(n1,'와'), josaYi pick(n2,'이')) ─
    fun warStartGlobal(name1: String, name2: String): String {
        val josaWa = JosaUtil.pick(name1, "와")
        val josaYi = JosaUtil.pick(name2, "이")
        return "<R><b>【개전】</b></><D><b>$name1</b></>$josaWa <D><b>$name2</b></>$josaYi <R>전쟁</>을 시작합니다."
    }

    fun warStopGlobal(name1: String, name2: String): String {
        val josaWa = JosaUtil.pick(name1, "와")
        val josaYi = JosaUtil.pick(name2, "이")
        return "<R><b>【종전】</b></><D><b>$name1</b></>$josaWa <D><b>$name2</b></>$josaYi <S>종전</>합니다."
    }
}
