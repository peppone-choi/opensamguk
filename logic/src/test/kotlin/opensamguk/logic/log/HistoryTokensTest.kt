package opensamguk.logic.log

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P3 / AREA F7 / Task FG2 — byte-exact history-string catalog scaffold.
 *
 * Each template is transcribed from PHP grand truth and rendered byte-for-byte (JosaUtil-backed,
 * color/tag markup literal). The numbers below are the per-source citations:
 *   봄/가을 income     — ProcessIncome.php:111 / :195
 *   수입/봉급 salary    — ProcessIncome.php:85/105 (gold) / :170/189 (rice)
 *   고립 supply-loss    — UpdateCitySupply.php:118
 *   작위 level-up 2..7  — UpdateNationLevel.php:86-114 (+ 8/9 extend the case-7 옹립 pattern)
 *   개전/종전 diplomacy  — func_gamerule.php:360 / :384
 */
class HistoryTokensTest {

    // ---- 봄/가을 income (global) ----
    @Test fun springIncomeGlobal() {
        assertEquals(
            "<W><b>【지급】</b></>봄이 되어 봉록에 따라 자금이 지급됩니다.",
            HistoryTokens.springIncomeGlobal(),
        )
    }

    @Test fun autumnIncomeGlobal() {
        assertEquals(
            "<W><b>【지급】</b></>가을이 되어 봉록에 따라 군량이 지급됩니다.",
            HistoryTokens.autumnIncomeGlobal(),
        )
    }

    // ---- 수입 / 봉급 (per-general action lines, number_format comma grouping) ----
    @Test fun goldIncomeLine() {
        assertEquals("이번 수입은 금 <C>1,234</>입니다.", HistoryTokens.goldIncomeLine(1234))
    }

    @Test fun riceIncomeLine() {
        assertEquals("이번 수입은 쌀 <C>5,000</>입니다.", HistoryTokens.riceIncomeLine(5000))
    }

    @Test fun goldSalaryLine() {
        assertEquals("봉급으로 금 <C>1,000</>을 받았습니다.", HistoryTokens.goldSalaryLine(1000))
    }

    @Test fun riceSalaryLine() {
        assertEquals("봉급으로 쌀 <C>800</>을 받았습니다.", HistoryTokens.riceSalaryLine(800))
    }

    // ---- 고립 supply-loss (josaYi via JosaUtil.pick(name,'이')) ----
    @Test fun isolatedCity_jongsung() {
        // 업현 has a 받침 → 이
        assertEquals(
            "<R><b>【고립】</b></><G><b>업현</b></>이 보급이 끊겨 <R>미지배</> 도시가 되었습니다.",
            HistoryTokens.isolatedCity("업현"),
        )
    }

    @Test fun isolatedCity_noJongsung() {
        // 초 has no 받침 → 가
        assertEquals(
            "<R><b>【고립】</b></><G><b>초</b></>가 보급이 끊겨 <R>미지배</> 도시가 되었습니다.",
            HistoryTokens.isolatedCity("초"),
        )
    }

    // ---- 작위 level-up (global + national), cases 2..7 + 8/9 ----
    @Test fun nationLevelUpCase2_independence_global() {
        // case 2 (군벌): josaRa via pick(nationName,'라'), josaRo via pick(levelText,'로'), josaYi via pick(lord,'이')
        // 위 has no 받침 → josaRa="라" (위라는); 조조 no 받침 → josaYi="가"; 군벌(ㄹ) → josaRo="로"
        assertEquals(
            "<Y><b>【작위】</b></><Y>조조</>가 독립하여 <D><b>위</b></>라는 <C>군벌</>로 나섰습니다.",
            HistoryTokens.nationLevelUpGlobal(level = 2, nationName = "위", lordName = "조조", oldLevelText = "호족", newLevelText = "군벌"),
        )
    }

    @Test fun nationLevelUpCase2_independence_national() {
        assertEquals(
            "<Y>조조</>가 독립하여 <D><b>위</b></>라는 <C>군벌</>로 나서다",
            HistoryTokens.nationLevelUpNational(level = 2, nationName = "위", lordName = "조조", oldLevelText = "호족", newLevelText = "군벌"),
        )
    }

    @Test fun nationLevelUpCase5_appointment_global() {
        // cases 3,4,5 share the 임명 pattern
        assertEquals(
            "<Y><b>【작위】</b></><D><b>촉</b></>의 <Y>유비</>가 <C>공</>으로 임명되었습니다.",
            HistoryTokens.nationLevelUpGlobal(level = 5, nationName = "촉", lordName = "유비", oldLevelText = "주목", newLevelText = "공"),
        )
    }

    @Test fun nationLevelUpCase6_investiture_global() {
        assertEquals(
            "<Y><b>【작위】</b></><D><b>오</b></>의 <Y>손권</>이 <C>왕</>으로 책봉되었습니다.",
            HistoryTokens.nationLevelUpGlobal(level = 6, nationName = "오", lordName = "손권", oldLevelText = "공", newLevelText = "왕"),
        )
    }

    @Test fun nationLevelUpCase7_enthronement_global() {
        // case 7 (황제) — note the SPACE between nationName block and oldLevelText (옹립 pattern)
        assertEquals(
            "<Y><b>【작위】</b></><D><b>위</b></> 왕 <Y>조비</>가 <C>황제</>로 옹립되었습니다.",
            HistoryTokens.nationLevelUpGlobal(level = 7, nationName = "위", lordName = "조비", oldLevelText = "왕", newLevelText = "황제"),
        )
    }

    @Test fun nationLevelUpCase7_enthronement_national() {
        assertEquals(
            "<D><b>위</b></> 왕 <Y>조비</>가 <C>황제</>로 옹립",
            HistoryTokens.nationLevelUpNational(level = 7, nationName = "위", lordName = "조비", oldLevelText = "왕", newLevelText = "황제"),
        )
    }

    // ---- NEW 8/9 작위 templates (extend the case-7 옹립 pattern; 대황제 / 천자) ----
    @Test fun nationLevelUpCase8_daehwangje_global() {
        // 대황제 ends with no 받침 (제) → josaRo = 로
        assertEquals(
            "<Y><b>【작위】</b></><D><b>위</b></> 황제 <Y>조비</>가 <C>대황제</>로 옹립되었습니다.",
            HistoryTokens.nationLevelUpGlobal(level = 8, nationName = "위", lordName = "조비", oldLevelText = "황제", newLevelText = "대황제"),
        )
    }

    @Test fun nationLevelUpCase9_cheonja_global() {
        // 천자 ends with no 받침 (자) → josaRo = 로
        assertEquals(
            "<Y><b>【작위】</b></><D><b>위</b></> 대황제 <Y>조비</>가 <C>천자</>로 옹립되었습니다.",
            HistoryTokens.nationLevelUpGlobal(level = 9, nationName = "위", lordName = "조비", oldLevelText = "대황제", newLevelText = "천자"),
        )
    }

    @Test fun nationLevelUpCase9_cheonja_national() {
        assertEquals(
            "<D><b>위</b></> 대황제 <Y>조비</>가 <C>천자</>로 옹립",
            HistoryTokens.nationLevelUpNational(level = 9, nationName = "위", lordName = "조비", oldLevelText = "대황제", newLevelText = "천자"),
        )
    }

    // ---- 개전 / 종전 diplomacy (josaWa via pick(name1,'와'), josaYi via pick(name2,'이')) ----
    @Test fun warStartGlobal() {
        assertEquals(
            "<R><b>【개전】</b></><D><b>위</b></>와 <D><b>촉</b></>이 <R>전쟁</>을 시작합니다.",
            HistoryTokens.warStartGlobal(name1 = "위", name2 = "촉"),
        )
    }

    @Test fun warStopGlobal() {
        assertEquals(
            "<R><b>【종전】</b></><D><b>위</b></>와 <D><b>촉</b></>이 <S>종전</>합니다.",
            HistoryTokens.warStopGlobal(name1 = "위", name2 = "촉"),
        )
    }
}
