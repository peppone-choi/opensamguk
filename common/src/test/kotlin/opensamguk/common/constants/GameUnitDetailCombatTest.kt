package opensamguk.common.constants

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F7 FU1 — combat-method parity for [GameUnitDetail] against PHP grand truth
 * `legacy/devsam-core/hwe/sammo/GameUnitDetail.php:130-213` + `func_converter.php:699-780`.
 *
 * All formulas kept as Double (no premature round). General-stat resolution (the iAction-flagged
 * getStrength/getIntel/getLeadership) is the CALLER's (F2 WarUnit) job; these methods take the
 * already-resolved mainstat values + tech + crew as primitives so :common stays free of :logic.
 */
class GameUnitDetailCombatTest {

    private val footman = GameUnitConst.byId(1100)!! // 보병, armType T_FOOTMAN, attack 100, defence 150
    private val wizard = GameUnitConst.byId(1400)!!  // 귀병, armType T_WIZARD, attack 80, defence 80
    private val castle = GameUnitConst.byId(1000)!!  // 성벽, armType T_CASTLE

    // ── getTechAbil / getTechCost (func_converter.php:699-705) ────────────────────────────────
    @Test
    fun techAbilIsLevelTimes25() {
        assertEquals(0, getTechAbil(0))
        assertEquals(25, getTechAbil(1000))
        assertEquals(75, getTechAbil(3000))
    }

    @Test
    fun techCostIsOnePlusLevelTimes015() {
        assertEquals(1.0, getTechCost(0))
        assertEquals(1.0 + 3 * 0.15, getTechCost(3000))
    }

    // ── getComputedAttack (GameUnitDetail.php:150-173) ────────────────────────────────────────
    @Test
    fun computedAttackUsesStrengthForFootman() {
        // ratio = str*2-40 = 60; att = 100 + 0; result = 100*60/100 = 60
        assertEquals(60.0, footman.getComputedAttack(strength = 50.0, intel = 0.0, leadership = 0.0, tech = 0))
    }

    @Test
    fun computedAttackFloorsRatioBelow10() {
        // str=20 → ratio=0 → floored to 10 FIRST; result = 100*10/100 = 10
        assertEquals(10.0, footman.getComputedAttack(strength = 20.0, intel = 0.0, leadership = 0.0, tech = 0))
    }

    @Test
    fun computedAttackSoftCapsRatioAbove100() {
        // str=80 → ratio=120 > 100 → 50 + 120/2 = 110; result = 100*110/100 = 110
        assertEquals(110.0, footman.getComputedAttack(strength = 80.0, intel = 0.0, leadership = 0.0, tech = 0))
    }

    @Test
    fun computedAttackWizardUsesIntel() {
        // wizard: ratio = intel*2-40 = 100; att = 80 + 0; result = 80*100/100 = 80
        assertEquals(80.0, wizard.getComputedAttack(strength = 0.0, intel = 70.0, leadership = 0.0, tech = 0))
    }

    @Test
    fun computedAttackAddsTechAbil() {
        // tech=3000 → getTechAbil=75; str=50 → ratio=60; att = 100+75 = 175; result = 175*60/100 = 105
        assertEquals(105.0, footman.getComputedAttack(strength = 50.0, intel = 0.0, leadership = 0.0, tech = 3000))
    }

    // ── getComputedDefence (GameUnitDetail.php:175-180) ───────────────────────────────────────
    @Test
    fun computedDefenceFloatCrewFactor() {
        // def = 150 + 0; crew factor = 3000/(7000/30)+70; result = def*crew/100
        val crewFactor = 3000.0 / (7000.0 / 30.0) + 70.0
        assertEquals(150.0 * crewFactor / 100.0, footman.getComputedDefence(crew = 3000, tech = 0))
    }

    @Test
    fun computedDefenceAddsTechAbil() {
        val crewFactor = 1000.0 / (7000.0 / 30.0) + 70.0
        assertEquals((150.0 + 75.0) * crewFactor / 100.0, footman.getComputedDefence(crew = 1000, tech = 3000))
    }

    // ── getCriticalRatio (GameUnitDetail.php:182-213) ─────────────────────────────────────────
    @Test
    fun criticalRatioCastleIsZero() {
        assertEquals(0.0, castle.getCriticalRatio(strength = 200.0, intel = 200.0, leadership = 200.0))
    }

    @Test
    fun criticalRatioFootmanCoef05() {
        // valueFit(85-65,0)=20 * 0.5 = 10; min(50,10)/100 = 0.1
        assertEquals(0.1, footman.getCriticalRatio(strength = 85.0, intel = 0.0, leadership = 0.0))
    }

    @Test
    fun criticalRatioCapsAt50Over100() {
        // valueFit(200-65,0)=135 * 0.5 = 67.5; min(50,67.5)/100 = 0.5
        assertEquals(0.5, footman.getCriticalRatio(strength = 200.0, intel = 0.0, leadership = 0.0))
    }

    @Test
    fun criticalRatioWizardCoef04UsesIntel() {
        // valueFit(85-65,0)=20 * 0.4 = 8; min(50,8)/100 = 0.08
        assertEquals(0.08, wizard.getCriticalRatio(strength = 0.0, intel = 85.0, leadership = 0.0))
    }

    @Test
    fun criticalRatioFloorsNegativeMainstatToZero() {
        // valueFit(40-65,0)=0 → ratio 0
        assertEquals(0.0, footman.getCriticalRatio(strength = 40.0, intel = 0.0, leadership = 0.0))
    }

    // ── getAttackCoef / getDefenceCoef (GameUnitDetail.php:130-148) ───────────────────────────
    @Test
    fun attackCoefResolvesArmTypeThenSpecificId() {
        // footman vs archer(1200, armType ARCHER=2): footman.attackCoef[T_ARCHER]=1.2
        val archer = GameUnitConst.byId(1200)!!
        assertEquals(1.2, footman.getAttackCoef(archer))
        // siege 1500 vs specific unit 1106: 1.112 wins over armType
        val jeongran = GameUnitConst.byId(1500)!!
        val baekyi = GameUnitConst.byId(1106)!!
        assertEquals(1.112, jeongran.getAttackCoef(baekyi))
    }

    @Test
    fun defenceCoefDefaultsToOne() {
        // footman has no defenceCoef entry for itself (T_FOOTMAN) → 1.0
        assertEquals(1.0, footman.getDefenceCoef(footman))
    }

    // ── getDexLevel / getDexLog (func_converter.php:712-780) ──────────────────────────────────
    @Test
    fun dexLevelReturnsLadderIndex() {
        assertEquals(0, getDexLevel(0))
        assertEquals(0, getDexLevel(349))
        assertEquals(1, getDexLevel(350))
        assertEquals(25, getDexLevel(1275974))
        assertEquals(26, getDexLevel(1275975))
        assertEquals(0, getDexLevel(-5))
    }

    @Test
    fun dexLogDivisor55PlusOneOffset() {
        // (getDexLevel(350) - getDexLevel(0))/55 + 1 = (1-0)/55 + 1
        assertEquals(1.0 / 55.0 + 1.0, getDexLog(350, 0))
        assertEquals(1.0, getDexLog(0, 0))
        // (26 - 1)/55 + 1
        assertEquals(25.0 / 55.0 + 1.0, getDexLog(1275975, 350))
    }

    @Test
    fun crewtypeCastleConstant() {
        assertEquals(1000, GameUnitConst.CREWTYPE_CASTLE)
    }
}
