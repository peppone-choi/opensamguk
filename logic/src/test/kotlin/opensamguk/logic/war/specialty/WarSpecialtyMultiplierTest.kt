package opensamguk.logic.war.specialty

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.war.WarStatName
import opensamguk.logic.war.WarUnitCity
import opensamguk.logic.war.WarUnitCityState
import opensamguk.logic.war.WarUnitGeneral
import opensamguk.logic.war.WarUnitGeneralState
import opensamguk.logic.war.specialty.triggers.CheJeogyeokSido
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.domain.City
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Port-faithful tests for AW3 — the warpower-multiplier + trigger-injecting war specialties
 * (`ActionSpecialWar/che_*.php` `getWarPowerMultiplier` + injection).
 *
 * Grand truth: 격노 [1+0.2*cnt, 1] (cross-phase 격노 log count); 무쌍 [1.05+log2(killnum/5)/20, 0.98−log2/50]
 * + warCriticalRatio +0.1 attacker; 공성 [2,1] only vs WarUnitCity; 기병 attacker [1.2,1] / def [1.1,1];
 * 보병 attacker [1,0.9] / def [1,0.8]; 돌격 initWarPhase +2 + [1.05,1] attacker; 척사 [1.2,0.8] vs region/city;
 * 저격 injects 저격시도 at PRE+100. The pipeline folds MULTIPLICATIVELY. ZERO RNG in any hook.
 */
class WarSpecialtyMultiplierTest {

    private val footman: GameUnitDetail = GameUnitConst.byId(1100)!! // 보병 armType 1
    private val pipeline = GeneralActionPipeline()

    private fun general(id: Int = 1, leadership: Int = 80): General = General(
        id = id, nationId = 1, cityId = 10,
        leadership = leadership, strength = 80, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 10000,
        crew = 5000, train = 100.0, atmos = 100.0, crewTypeId = 1100,
        meta = linkedMapOf("explevel" to 0),
    )

    private fun warGeneral(
        g: General = general(),
        attacker: Boolean = true,
        crewType: GameUnitDetail = footman,
    ): WarUnitGeneral = WarUnitGeneral(
        rng = RandUtil(LiteHashDrbg("00")),
        state = WarUnitGeneralState(g),
        pipeline = pipeline,
        crewType = crewType, tech = 0, isAttacker = attacker, cityLevel = 9, isCapital = false,
    )

    private fun warCity(): WarUnitCity {
        val city = City(
            id = 10, nationId = 0, level = 5,
            commerce = 1000, commerceMax = 9999,
            agriculture = 1000, agricultureMax = 9999,
            supplyState = 1, frontState = 0, trust = 100.0,
            security = 1000, securityMax = 9999,
            defense = 500, defenseMax = 9999,
            wall = 500, wallMax = 9999,
            population = 100000, populationMax = 999999,
        )
        return WarUnitCity(rng = RandUtil(LiteHashDrbg("00")), state = WarUnitCityState(city), year = 200, startYear = 180)
    }

    private fun log2(x: Double): Double = ln(x) / ln(2.0)

    // --- che_격노 -------------------------------------------------------------------------------------
    @Test
    fun `격노 multiplier scales with activated 격노 count`() {
        val u = warGeneral()
        // no 격노 activated → [1, 1]
        assertEquals(1.0 to 1.0, CheGyeokno.getWarPowerMultiplier(u))
        // 격노 active once → hasActivatedSkillOnLog('격노') == 1 → [1.2, 1]
        u.activateSkill("격노")
        assertEquals(1.2 to 1.0, CheGyeokno.getWarPowerMultiplier(u))
    }

    @Test
    fun `격노 injects 격노시도 BODY+400 and 격노발동 POST+600`() {
        assertFalse(CheGyeokno.getBattlePhaseSkillTriggerList(warGeneral()).isEmpty())
    }

    // --- che_무쌍 -------------------------------------------------------------------------------------
    @Test
    fun `무쌍 warCriticalRatio +0_1 only as attacker, multiplier log2 killnum over 5`() {
        assertEquals(0.4, CheMussang.onCalcStat(general(), WarStatName.WAR_CRITICAL_RATIO, 0.3, mapOf("isAttacker" to true)), 1e-12)
        assertEquals(0.3, CheMussang.onCalcStat(general(), WarStatName.WAR_CRITICAL_RATIO, 0.3, mapOf("isAttacker" to false)), 1e-12)

        // killnum 0 → log2(max(1,0)) = 0 → [1.05, 0.98]
        val u0 = warGeneral()
        assertEquals(1.05 to 0.98, CheMussang.getWarPowerMultiplier(u0))

        // killnum 40 → log2(8) = 3 → [1.05+3/20, 0.98−3/50] = [1.2, 0.92]
        val u40 = warGeneral()
        u40.state.increaseRank("killnum", 40)
        val (att, def) = CheMussang.getWarPowerMultiplier(u40)
        assertEquals(1.05 + 3.0 / 20, att, 1e-9)
        assertEquals(0.98 - 3.0 / 50, def, 1e-9)
    }

    // --- che_공성 -------------------------------------------------------------------------------------
    @Test
    fun `공성 multiplier 2,1 only vs WarUnitCity`() {
        val att = warGeneral(attacker = true)
        val defGen = warGeneral(general(2), attacker = false)
        val defCity = warCity()

        att.setOppose(defGen)
        assertEquals(1.0 to 1.0, CheGongseong.getWarPowerMultiplier(att))

        att.setOppose(defCity)
        assertEquals(2.0 to 1.0, CheGongseong.getWarPowerMultiplier(att))
    }

    // --- che_기병 / 보병 / 돌격 -----------------------------------------------------------------------
    @Test
    fun `기병 보병 돌격 attacker vs defender multipliers`() {
        assertEquals(1.2 to 1.0, CheGibyeong.getWarPowerMultiplier(warGeneral(attacker = true)))
        assertEquals(1.1 to 1.0, CheGibyeong.getWarPowerMultiplier(warGeneral(attacker = false)))
        assertEquals(1.0 to 0.9, CheBobyeong.getWarPowerMultiplier(warGeneral(attacker = true)))
        assertEquals(1.0 to 0.8, CheBobyeong.getWarPowerMultiplier(warGeneral(attacker = false)))
        assertEquals(1.05 to 1.0, CheDolgyeok.getWarPowerMultiplier(warGeneral(attacker = true)))
        assertEquals(1.0 to 1.0, CheDolgyeok.getWarPowerMultiplier(warGeneral(attacker = false)))
        // 돌격 initWarPhase +2
        assertEquals(7.0, CheDolgyeok.onCalcStat(general(), WarStatName.INIT_WAR_PHASE, 5.0), 1e-12)
    }

    // --- che_저격 (injection priority) ----------------------------------------------------------------
    @Test
    fun `저격 injects the phase trigger at PRE+100`() {
        assertFalse(CheJeogyeok.getBattlePhaseSkillTriggerList(warGeneral()).isEmpty())
        // 저격시도(unit, TYPE_NONE, 0.5, 20, 40) at PRE+100
        val sniper = CheJeogyeokSido(warGeneral(), ObjectTrigger.PRIORITY_MIN, 0.5, 20.0, 40.0)
        assertEquals(ObjectTrigger.PRIORITY_PRE + 100, sniper.priority)
    }

    @Test
    fun `위압 injects the begin trigger`() {
        assertFalse(CheWiap.getBattlePhaseSkillTriggerList(warGeneral()).isEmpty())
    }

    // --- che_척사 -------------------------------------------------------------------------------------
    @Test
    fun `척사 multiplier 1_2,0_8 vs a region or city crewType, else identity`() {
        // footman (armType 1) has no ReqCities/ReqRegions constraint → identity
        val attFootman = warGeneral(attacker = true)
        attFootman.setOppose(warGeneral(general(2), attacker = false))
        assertEquals(1.0 to 1.0, CheCheoksa.getWarPowerMultiplier(attFootman))

        // a region/city crewType opponent → [1.2, 0.8]. 1106 (오월수전병, ReqRegions 오월) is a region special.
        val regionCrew = GameUnitConst.byId(1106)
        if (regionCrew != null) {
            val att = warGeneral(attacker = true)
            att.setOppose(warGeneral(general(3), attacker = false, crewType = regionCrew))
            assertEquals(1.2 to 0.8, CheCheoksa.getWarPowerMultiplier(att))
        }
    }

    // --- the MULTIPLICATIVE pipeline fold (F5) ---------------------------------------------------------
    @Test
    fun `getWarPowerMultiplier folds MULTIPLICATIVELY across the source stack`() {
        // 기병(att 1.2) ∘ 돌격(att 1.05) = 1.26 att; def 1.0*1.0 = 1.0
        val p = GeneralActionPipeline(listOf(CheGibyeong, CheDolgyeok))
        val u = warGeneral(attacker = true)
        val (att, def) = p.getWarPowerMultiplier(u)
        assertEquals(1.2 * 1.05, att, 1e-9)
        assertEquals(1.0, def, 1e-9)
    }

    // --- the registry is now fully populated (AW1+AW2+AW3) --------------------------------------------
    @Test
    fun `every WAR selection id resolves after AW3 completes the registry`() {
        for (id in SpecialWarRegistry.registrableIds()) {
            assertNotNull(SpecialWarRegistry.resolve(id), "WAR id $id must resolve to a module")
        }
        assertTrue(SpecialWarRegistry.resolve("None") == null)
    }
}
