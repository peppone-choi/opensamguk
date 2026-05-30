package opensamguk.logic.log

import opensamguk.common.josa.JosaUtil
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F7 FU4 — byte-parity for the battle/phase/conquest log catalog against PHP grand truth
 * `legacy/devsam-core/hwe/process_war.php` (진격 :252-253, 패퇴 :274/:277, 퇴각 :411, 패퇴/전멸 :450-456,
 * per-phase detail :386/:390, 분쟁 :495, 지배 :578, 긴급천도 :718/:720, 분쟁협상 :771, 양도 :773-774) +
 * `WarUnitGeneral.php:322` (부상). Each template reproduces the literal color/tag markup
 * (`<D>/<Y>/<G>/<C>/<R>/<M>/<S>/<O>/<Y1>/<b>/【…】`), the JosaUtil josa selection, and the exact
 * `(전투시드: …)` hidden span + the `先`/`{n} ` phase nickname.
 */
class BattleLogTokensTest {

    @Test
    fun `진격 global + general lines reproduce josa, name markup, and warSeed span`() {
        val nationName = "위"
        val generalName = "관우"
        val cityName = "낙양"
        val warSeed = "deadbeefcafe"
        val date = "180년 1월"

        val josaYi = JosaUtil.pick(generalName, "이")
        val josaRo = JosaUtil.pick(cityName, "로")
        val seedSpan = "<span class='hidden_but_copyable'>(전투시드: $warSeed)</span>"

        assertEquals(
            "<D><b>$nationName</b></>의 <Y>$generalName</>$josaYi <G><b>$cityName</b></>$josaRo 진격합니다.$seedSpan",
            BattleLogTokens.advanceGlobal(nationName, generalName, cityName, warSeed),
        )
        assertEquals(
            "<G><b>$cityName</b></>$josaRo <M>진격</>합니다.$seedSpan <1>$date</>",
            BattleLogTokens.advanceGeneral(cityName, warSeed, date),
        )
    }

    @Test
    fun `퇴각 global line`() {
        val attackerName = "장비"
        val crewType = "보병"
        val josaYi = JosaUtil.pick(crewType, "이")
        assertEquals(
            "<Y>$attackerName</>의 $crewType$josaYi 퇴각했습니다.",
            BattleLogTokens.retreatGlobal(attackerName, crewType),
        )
    }

    @Test
    fun `패퇴 (병량) + 전멸 defender lines`() {
        val defenderName = "조운"
        val crewType = "궁병"
        val josaYi = JosaUtil.pick(crewType, "이")
        assertEquals(
            "<Y>$defenderName</>의 $crewType$josaYi 패퇴했습니다.",
            BattleLogTokens.defeatedGlobal(defenderName, crewType),
        )
        assertEquals(
            "<Y>$defenderName</>의 $crewType$josaYi 전멸했습니다.",
            BattleLogTokens.annihilatedGlobal(defenderName, crewType),
        )
    }

    @Test
    fun `병량 패퇴 history line (city seized for lack of rice)`() {
        val nationName = "촉"
        val cityName = "성도"
        val josaUl = JosaUtil.pick(cityName, "을")
        val josaYi = JosaUtil.pick(nationName, "이")
        assertEquals(
            "<M><b>【패퇴】</b></><D><b>$nationName</b></>$josaYi 병량 부족으로 <G><b>$cityName</b></>$josaUl 뺏기고 말았습니다.",
            BattleLogTokens.riceShortfallSeizeHistory(nationName, cityName),
        )
    }

    @Test
    fun `per-phase battle-detail HP markup (先 + numbered nickname)`() {
        val attackerName = "관우"
        val defenderName = "여포"
        // attacker-perspective; 先 nickname (defender phase < 0)
        assertEquals(
            "先: <Y1>【$attackerName】</> <C>1000 (-200)</> VS <C>800 (-300)</> <Y1>【$defenderName】</>",
            BattleLogTokens.phaseDetailAttacker(
                phaseNickname = "先", attackerName = attackerName, attackerHp = 1000, deadAttacker = 200,
                defenderName = defenderName, defenderHp = 800, deadDefender = 300,
            ),
        )
        // numbered nickname carries the trailing space exactly ("{currPhase} ")
        assertEquals(
            "3 : <Y1>【$defenderName】</> <C>800 (-300)</> VS <C>1000 (-200)</> <Y1>【$attackerName】</>",
            BattleLogTokens.phaseDetailDefender(
                phaseNickname = "3 ", attackerName = attackerName, attackerHp = 1000, deadAttacker = 200,
                defenderName = defenderName, defenderHp = 800, deadDefender = 300,
            ),
        )
    }

    @Test
    fun `phase nickname helper (先 for init, numbered with trailing space otherwise)`() {
        assertEquals("先", BattleLogTokens.phaseNickname(defenderPhase = -1, attackerPhase = 0))
        assertEquals("1 ", BattleLogTokens.phaseNickname(defenderPhase = 0, attackerPhase = 0))
        assertEquals("3 ", BattleLogTokens.phaseNickname(defenderPhase = 2, attackerPhase = 2))
    }

    @Test
    fun `부상 general line`() {
        assertEquals("전투중 <R>부상</>당했다!", BattleLogTokens.woundGeneral())
    }

    @Test
    fun `분쟁 history line (newConflict)`() {
        val nationName = "오"
        val cityName = "건업"
        val josaYi = JosaUtil.pick(nationName, "이")
        assertEquals(
            "<M><b>【분쟁】</b></><D><b>$nationName</b></>$josaYi <G><b>$cityName</b></> 공략에 가담하여 분쟁이 발생하고 있습니다.",
            BattleLogTokens.conflictHistory(nationName, cityName),
        )
    }

    @Test
    fun `지배 history line (conquest)`() {
        val nationName = "위"
        val cityName = "허창"
        val josaYi = JosaUtil.pick(nationName, "이")
        val josaUl = JosaUtil.pick(cityName, "을")
        assertEquals(
            "<S><b>【지배】</b></><D><b>$nationName</b></>$josaYi <G><b>$cityName</b></>$josaUl 지배했습니다.",
            BattleLogTokens.conquerHistory(nationName, cityName),
        )
    }

    @Test
    fun `긴급천도 history + general move lines`() {
        val defenderNationName = "촉"
        val minCityName = "한중"
        val josaYi = JosaUtil.pick(defenderNationName, "이")
        val josaRo = JosaUtil.pick(minCityName, "로")
        assertEquals(
            "<M><b>【긴급천도】</b></><D><b>$defenderNationName</b></>$josaYi 수도가 함락되어 <G><b>$minCityName</b></>$josaRo 긴급천도하였습니다.",
            BattleLogTokens.emergencyCapitalMoveHistory(defenderNationName, minCityName),
        )
        assertEquals(
            "수도가 함락되어 <G><b>$minCityName</b></>$josaRo <M>긴급천도</>합니다.",
            BattleLogTokens.emergencyCapitalMoveGeneral(minCityName),
        )
    }

    @Test
    fun `분쟁협상 (양도받음) history line`() {
        val conquerNationName = "오"
        val cityName = "강하"
        val josaYi = JosaUtil.pick(conquerNationName, "이")
        val josaUl = JosaUtil.pick(cityName, "을")
        assertEquals(
            "<Y><b>【분쟁협상】</b></><D><b>$conquerNationName</b></>$josaYi 영토분쟁에서 우위를 점하여 <G><b>$cityName</b></>$josaUl 양도받았습니다.",
            BattleLogTokens.conflictNegotiationHistory(conquerNationName, cityName),
        )
    }
}
