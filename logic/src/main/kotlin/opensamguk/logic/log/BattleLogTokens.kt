package opensamguk.logic.log

import opensamguk.common.josa.JosaUtil

/**
 * F7 / Task FU4 — JosaUtil-backed battle/phase/conquest log catalog (consolidated OQ #14).
 *
 * Byte-exact templates transcribed from PHP grand truth `legacy/devsam-core/hwe/process_war.php`
 * (진격 :252-253, 병량 패퇴 :274/:277, 퇴각 :411, 패퇴/전멸 :450-456, per-phase detail :386/:390,
 * 분쟁 :495, 지배 :578, 긴급천도 :718/:720, 분쟁협상 :771) and `WarUnitGeneral.php:322` (부상).
 * F2 (power/finishBattle logs) + A4 (conflict log) + B1/B2 (battle/conquest logs) consume these; G1
 * byte-matches the strings. Each template reproduces the literal color/tag markup
 * (`<W>/<R>/<G>/<C>/<Y>/<Y1>/<D>/<S>/<M>/<O>/<b>/【…】`), the JosaUtil josa selection, and the
 * `(전투시드: …)` hidden span + the `<1>{date}</>`/`先`/`{n} ` phase nickname EXACTLY.
 *
 * JosaUtil lives in `:common`; these helpers take the already-resolved names so the catalog stays
 * presentation-only (no entity coupling).
 */
object BattleLogTokens {

    private fun seedSpan(warSeed: String): String =
        "<span class='hidden_but_copyable'>(전투시드: $warSeed)</span>"

    // ── 진격 (process_war.php:252-253) ────────────────────────────────────────────────────────
    /** Global 進擊 line: `{nation}의 {general}{이/가} {city}{로/으로} 진격합니다.(전투시드…)`. */
    fun advanceGlobal(nationName: String, generalName: String, cityName: String, warSeed: String): String {
        val josaYi = JosaUtil.pick(generalName, "이")
        val josaRo = JosaUtil.pick(cityName, "로")
        return "<D><b>$nationName</b></>의 <Y>$generalName</>$josaYi <G><b>$cityName</b></>$josaRo 진격합니다.${seedSpan(warSeed)}"
    }

    /** Per-general 進擊 line (carries the trailing `<1>{date}</>`). */
    fun advanceGeneral(cityName: String, warSeed: String, date: String): String {
        val josaRo = JosaUtil.pick(cityName, "로")
        return "<G><b>$cityName</b></>$josaRo <M>진격</>합니다.${seedSpan(warSeed)} <1>$date</>"
    }

    // ── 퇴각 / 패퇴 / 전멸 global crew lines (process_war.php:411/:450/:454) ─────────────────────
    /** `{attacker}의 {crewType}{이/가} 퇴각했습니다.` (:411). */
    fun retreatGlobal(attackerName: String, crewTypeName: String): String {
        val josaYi = JosaUtil.pick(crewTypeName, "이")
        return "<Y>$attackerName</>의 $crewTypeName$josaYi 퇴각했습니다."
    }

    /** `{defender}의 {crewType}{이/가} 패퇴했습니다.` (:450, 병량 부족 defender rout). */
    fun defeatedGlobal(defenderName: String, crewTypeName: String): String {
        val josaYi = JosaUtil.pick(crewTypeName, "이")
        return "<Y>$defenderName</>의 $crewTypeName$josaYi 패퇴했습니다."
    }

    /** `{defender}의 {crewType}{이/가} 전멸했습니다.` (:454). */
    fun annihilatedGlobal(defenderName: String, crewTypeName: String): String {
        val josaYi = JosaUtil.pick(crewTypeName, "이")
        return "<Y>$defenderName</>의 $crewTypeName$josaYi 전멸했습니다."
    }

    // ── 병량 패퇴 history (process_war.php:277) ────────────────────────────────────────────────
    /** `【패퇴】{nation}{이/가} 병량 부족으로 {city}{을/를} 뺏기고 말았습니다.`. */
    fun riceShortfallSeizeHistory(nationName: String, cityName: String): String {
        val josaUl = JosaUtil.pick(cityName, "을")
        val josaYi = JosaUtil.pick(nationName, "이")
        return "<M><b>【패퇴】</b></><D><b>$nationName</b></>$josaYi 병량 부족으로 <G><b>$cityName</b></>$josaUl 뺏기고 말았습니다."
    }

    // ── per-phase battle-detail HP markup (process_war.php:375-391) ───────────────────────────
    /**
     * PHP `$phaseNickname` (:375-381): `先` when the defender's phase < 0 (the init/선제 contact),
     * otherwise `"{attackerPhase+1} "` — note the TRAILING SPACE, which the `"$phaseNickname: …"`
     * template carries into the rendered line.
     */
    fun phaseNickname(defenderPhase: Int, attackerPhase: Int): String =
        if (defenderPhase < 0) "先" else "${attackerPhase + 1} "

    /** Attacker-perspective per-phase detail (:386): `{nick}: 【att】 HP (-dead) VS HP (-dead) 【def】`. */
    fun phaseDetailAttacker(
        phaseNickname: String,
        attackerName: String, attackerHp: Int, deadAttacker: Int,
        defenderName: String, defenderHp: Int, deadDefender: Int,
    ): String =
        "$phaseNickname: <Y1>【$attackerName】</> <C>$attackerHp (-$deadAttacker)</> VS <C>$defenderHp (-$deadDefender)</> <Y1>【$defenderName】</>"

    /** Defender-perspective per-phase detail (:390): the unit order is FLIPPED (def first). */
    fun phaseDetailDefender(
        phaseNickname: String,
        attackerName: String, attackerHp: Int, deadAttacker: Int,
        defenderName: String, defenderHp: Int, deadDefender: Int,
    ): String =
        "$phaseNickname: <Y1>【$defenderName】</> <C>$defenderHp (-$deadDefender)</> VS <C>$attackerHp (-$deadAttacker)</> <Y1>【$attackerName】</>"

    // ── 부상 (WarUnitGeneral.php:322) ─────────────────────────────────────────────────────────
    fun woundGeneral(): String = "전투중 <R>부상</>당했다!"

    // ── 분쟁 history (process_war.php:495) ────────────────────────────────────────────────────
    /** `【분쟁】{nation}{이/가} {city} 공략에 가담하여 분쟁이 발생하고 있습니다.`. */
    fun conflictHistory(nationName: String, cityName: String): String {
        val josaYi = JosaUtil.pick(nationName, "이")
        return "<M><b>【분쟁】</b></><D><b>$nationName</b></>$josaYi <G><b>$cityName</b></> 공략에 가담하여 분쟁이 발생하고 있습니다."
    }

    // ── 지배 history (process_war.php:578) ────────────────────────────────────────────────────
    /** `【지배】{nation}{이/가} {city}{을/를} 지배했습니다.`. */
    fun conquerHistory(attackerNationName: String, cityName: String): String {
        val josaYiNation = JosaUtil.pick(attackerNationName, "이")
        val josaUl = JosaUtil.pick(cityName, "을")
        return "<S><b>【지배】</b></><D><b>$attackerNationName</b></>$josaYiNation <G><b>$cityName</b></>$josaUl 지배했습니다."
    }

    // ── 긴급천도 (process_war.php:718/:720) ───────────────────────────────────────────────────
    /** `【긴급천도】{nation}{이/가} 수도가 함락되어 {minCity}{로/으로} 긴급천도하였습니다.`. */
    fun emergencyCapitalMoveHistory(defenderNationName: String, minCityName: String): String {
        val josaYi = JosaUtil.pick(defenderNationName, "이")
        val josaRo = JosaUtil.pick(minCityName, "로")
        return "<M><b>【긴급천도】</b></><D><b>$defenderNationName</b></>$josaYi 수도가 함락되어 <G><b>$minCityName</b></>$josaRo 긴급천도하였습니다."
    }

    /** Per-general 긴급천도 move line (:720). */
    fun emergencyCapitalMoveGeneral(minCityName: String): String {
        val josaRo = JosaUtil.pick(minCityName, "로")
        return "수도가 함락되어 <G><b>$minCityName</b></>$josaRo <M>긴급천도</>합니다."
    }

    // ── 분쟁협상 (양도받음) history (process_war.php:771) ──────────────────────────────────────
    /** `【분쟁협상】{nation}{이/가} 영토분쟁에서 우위를 점하여 {city}{을/를} 양도받았습니다.`. */
    fun conflictNegotiationHistory(conquerNationName: String, cityName: String): String {
        val josaYi = JosaUtil.pick(conquerNationName, "이")
        val josaUl = JosaUtil.pick(cityName, "을")
        return "<Y><b>【분쟁협상】</b></><D><b>$conquerNationName</b></>$josaYi 영토분쟁에서 우위를 점하여 <G><b>$cityName</b></>$josaUl 양도받았습니다."
    }
}
