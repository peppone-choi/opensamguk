package opensamguk.logic.items

import opensamguk.common.constants.GameUnitConst
import opensamguk.common.constants.GameUnitDetail
import opensamguk.common.rng.LiteHashDrbg
import opensamguk.common.rng.RandUtil
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionPipeline
import opensamguk.logic.war.WarStatName
import opensamguk.logic.war.WarUnitCity
import opensamguk.logic.war.WarUnitCityState
import opensamguk.logic.war.WarUnitGeneral
import opensamguk.logic.war.WarUnitGeneralState
import opensamguk.logic.war.specialty.ChePilsal
import opensamguk.logic.war.specialty.CheGyeokno
import opensamguk.logic.war.specialty.CheGyeongo
import opensamguk.logic.war.specialty.CheGongseong
import opensamguk.logic.war.specialty.CheGibyeong
import opensamguk.logic.war.trigger.BaseWarUnitTrigger
import opensamguk.logic.war.trigger.ObjectTrigger
import opensamguk.logic.war.trigger.TriggerEnv
import opensamguk.logic.war.trigger.WarUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * AI1 — war-side ItemHooks population (A2). Port-faithful tests for the war-item modules that POPULATE the
 * previously-identity war hooks in `ItemHooks.kt`, reusing the A1 canonical specialty trigger classes with a
 * DISTINCT item raiseType (TYPE_ITEM + TYPE_DEDUP_TYPE_BASE*nnn) so item-version and specialty-version COEXIST.
 *
 * PHP grand truth (ActionItem PHP classes):
 *   - che_저격_비도        : getBattlePhaseSkillTriggerList → che_저격시도(TYPE_ITEM+DEDUP*305, 0.5,20,40)/che_저격발동(*305).
 *   - che_무기_07_맥궁     : BaseStatItem 무력+7 PLUS che_저격시도(TYPE_ITEM+DEDUP*107, 0.2,20,40)/발동(*107).
 *   - che_부적_태현청생부   : onCalcStat injuryProb −1; init+phase inject che_부상무효(*303) + WarActivateSkills(저격불가,*303).
 *   - che_격노_구정신단경   : getWarPowerMultiplier [1+0.05*격노cnt,1]; inject che_격노시도(TYPE_ITEM)/격노발동.
 *   - event_충차           : consumable getWarPowerMultiplier [1.5,1] vs WarUnitCity; inject event_충차아이템소모(TYPE_CONSUMABLE_ITEM).
 *   - event_전투특기_*     : 1:1 clones of the war specialties — reuse the A1 canonical module body (single-sourced).
 */
class WarItemHooksTest {

    private val footman: GameUnitDetail = GameUnitConst.byId(1100)!!
    private val pipeline = GeneralActionPipeline()

    private fun general(id: Int = 1, item: String = "None"): General = General(
        id = id, nationId = 1, cityId = 10,
        leadership = 80, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 10000,
        crew = 5000, train = 100.0, atmos = 100.0, crewTypeId = 1100,
        item = item,
        meta = linkedMapOf("explevel" to 0),
    )

    private fun warGeneral(
        g: General = general(),
        attacker: Boolean = true,
    ): WarUnitGeneral = WarUnitGeneral(
        rng = RandUtil(LiteHashDrbg("00")),
        state = WarUnitGeneralState(g),
        pipeline = pipeline,
        crewType = footman, tech = 0, isAttacker = attacker, cityLevel = 9, isCapital = false,
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

    private fun registry() = ItemRegistry { 0 }

    // === 비도 — 저격 trigger item with item raiseType (TYPE_ITEM+DEDUP*305) ============================

    @Test
    fun `bido resolves and injects a non-empty phase caller`() {
        val m = registry().resolve("che_저격_비도")!!
        assertFalse(m.getBattlePhaseSkillTriggerList(warGeneral())!!.isEmpty())
    }

    @Test
    fun `bido item-저격시도 and specialty-저격시도 COEXIST (distinct uniqueID, no dedup)`() {
        // Item version: che_저격시도(unit, TYPE_ITEM+DEDUP*305, ...) ; specialty version: TYPE_NONE.
        val u = warGeneral()
        val itemRaise = BaseWarUnitTrigger.TYPE_ITEM + BaseWarUnitTrigger.TYPE_DEDUP_TYPE_BASE * 305
        val itemSido = opensamguk.logic.war.specialty.triggers.CheJeogyeokSido(u, itemRaise, 0.5, 20.0, 40.0)
        val specialtySido = opensamguk.logic.war.specialty.triggers.CheJeogyeokSido(u, BaseWarUnitTrigger.TYPE_NONE, 0.5, 20.0, 40.0)
        // Same priority + class + unit, DIFFERENT raiseType ⇒ DIFFERENT uniqueID ⇒ both survive the bucket dedup.
        assertNotEquals(specialtySido.getUniqueID(), itemSido.getUniqueID())
        assertTrue(itemSido.getUniqueID().endsWith("_$itemRaise"))
        assertEquals(ObjectTrigger.PRIORITY_PRE + 100, itemSido.priority)
    }

    // === 맥궁 — BaseStatItem 무력+7 PLUS a 저격(0.2) injection ==========================================

    @Test
    fun `maekgung keeps the parent stat boost AND injects 저격`() {
        val m = registry().resolve("che_무기_07_맥궁")!!
        // parent BaseStatItem: 무기 → 무력/strength +7 (token che_무기_07_맥궁 → statValue 07 = 7).
        assertEquals(60.0 + 7.0, m.onCalcStat(general(), "strength", 60.0), 1e-9)
        assertEquals(80.0, m.onCalcStat(general(), "intel", 80.0))
        // injects the 저격 phase trigger with its own item raiseType (*107).
        assertFalse(m.getBattlePhaseSkillTriggerList(warGeneral())!!.isEmpty())
    }

    // === 태현청생부 — injuryProb −1 + 부상무효/저격불가 injection =======================================

    @Test
    fun `taehyeon onCalcStat injuryProb minus 1, identity elsewhere`() {
        val m = registry().resolve("che_부적_태현청생부")!!
        assertEquals(0.05 - 1.0, m.onCalcStat(general(), WarStatName.INJURY_PROB, 0.05), 1e-12)
        assertEquals(0.05, m.onCalcStat(general(), WarStatName.WAR_CRITICAL_RATIO, 0.05))
    }

    @Test
    fun `taehyeon injects init and phase callers (부상무효 + 저격불가)`() {
        val m = registry().resolve("che_부적_태현청생부")!!
        assertFalse(m.getBattleInitSkillTriggerList(warGeneral())!!.isEmpty())
        assertFalse(m.getBattlePhaseSkillTriggerList(warGeneral())!!.isEmpty())
    }

    // === 구정신단경 — 격노 warpower [1+0.05*cnt, 1] ====================================================

    @Test
    fun `gujeong warpower multiplier scales 0_05 per activated 격노`() {
        val m = registry().resolve("che_격노_구정신단경")!!
        val u = warGeneral()
        assertEquals(1.0 to 1.0, m.getWarPowerMultiplier(u))
        u.activateSkill("격노")
        assertEquals(1.05 to 1.0, m.getWarPowerMultiplier(u))
        assertFalse(m.getBattlePhaseSkillTriggerList(u)!!.isEmpty())
    }

    // === event_충차 — consumable [1.5,1] vs city; identity vs general ==================================

    @Test
    fun `chungcha warpower 1_5 vs city, identity vs general`() {
        val m = registry().resolve("event_충차")!!
        val attVsCity = warGeneral(attacker = true)
        attVsCity.setOppose(warCity())
        assertEquals(1.5 to 1.0, m.getWarPowerMultiplier(attVsCity))

        val attVsGeneral = warGeneral(attacker = true)
        attVsGeneral.setOppose(warGeneral(general(2), attacker = false))
        assertEquals(1.0 to 1.0, m.getWarPowerMultiplier(attVsGeneral))
    }

    @Test
    fun `chungcha injects the consumable phase trigger (TYPE_CONSUMABLE_ITEM)`() {
        val m = registry().resolve("event_충차")!!
        assertFalse(m.getBattlePhaseSkillTriggerList(warGeneral())!!.isEmpty())
    }

    @Test
    fun `processConsumableItem deletes the held item as a state delta (no inline DB)`() {
        // A consumable trigger (TYPE_CONSUMABLE_ITEM) calling processConsumableItem must clear the held item.
        val g = general(item = "event_충차")
        val u = warGeneral(g)
        val trigger = TestConsumable(u, BaseWarUnitTrigger.TYPE_CONSUMABLE_ITEM)
        assertEquals("event_충차", u.getItemName())
        assertTrue(trigger.consume())
        assertEquals("None", u.getItemName()) // deleteItem → state item "None" (the flush-delta, not inline DB)
    }

    // === event_전투특기_* — 1:1 clones of the war specialties (single-sourced canonical body) ==========

    @Test
    fun `event_전투특기_필살 reuses the canonical 필살 module body`() {
        val m = registry().resolve("event_전투특기_필살")!!
        // warCriticalRatio +0.30 (same as A1 ChePilsal).
        assertEquals(0.10 + 0.30, m.onCalcStat(general(), WarStatName.WAR_CRITICAL_RATIO, 0.10), 1e-12)
        // criticalDamageRange [(min+max)/2, max] (the pair hook).
        assertEquals(1.65 to 2.0, m.onCalcStatRange(general(), WarStatName.CRITICAL_DAMAGE_RANGE, 1.3 to 2.0))
        // Single-sourced: the resolved clone IS the A1 canonical module object.
        assertSame(ChePilsal, m)
    }

    @Test
    fun `event_전투특기 clones map to the same A1 canonical objects`() {
        val r = registry()
        assertSame(CheGyeokno, r.resolve("event_전투특기_격노"))
        assertSame(CheGyeongo, r.resolve("event_전투특기_견고"))
        assertSame(CheGongseong, r.resolve("event_전투특기_공성"))
        assertSame(CheGibyeong, r.resolve("event_전투특기_기병"))
    }

    // === wiring / non-regression ======================================================================

    @Test
    fun `unknown war item resolves null, None resolves null`() {
        val r = registry()
        assertNull(r.resolve("event_전투특기_없는특기"))
        assertNull(r.resolve("None"))
    }

    @Test
    fun `domestic IT2 items still resolve (war population is additive, no regression)`() {
        val r = registry()
        assertEquals(0.5 + 0.15, r.resolve("che_내정_납금박산로")!!.onCalcDomestic(general(), "상업", "success", 0.5), 1e-9)
    }

    /** A throwaway TYPE_CONSUMABLE_ITEM trigger exposing [BaseWarUnitTrigger.processConsumableItem] for the test. */
    private class TestConsumable(unit: WarUnit, raiseType: Int) : BaseWarUnitTrigger(unit, raiseType) {
        override val priority: Int = ObjectTrigger.PRIORITY_PRE
        override fun actionWar(self: WarUnit, oppose: WarUnit, selfEnv: TriggerEnv, opposeEnv: TriggerEnv): Boolean = true
        fun consume(): Boolean = processConsumableItem()
    }
}
