package opensamguk.logic.stats

import opensamguk.logic.domain.General
import opensamguk.logic.traits.OfficerLevelModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Port-faithful tests for the 9-source [GeneralActionModuleFactory] (SM1).
 *
 * PHP grand truth (General.php:787-799 getActionList; :100-132 source construction):
 *   array_merge([nationType, officerLevelObj, specialDomesticObj, specialWarObj,
 *                personalityObj, crewType, inheritBuffObj, scenarioEffect], itemObjs)
 *   itemObjs = [horse, weapon, book, item].
 * null entries are skipped during the fold; crew/inherit/scenario/specialWar are identity stubs in
 * P2 domestic. Officer module = TriggerOfficerLevel (calcLeadershipBonus on leadership).
 */
class ModuleFactoryOrderTest {

    /** A tagged identity module so the test can assert factory ORDER by reading back the tags. */
    private class TagModule(val tag: String) : GeneralActionModule

    private fun reg(prefix: String) = object : GeneralActionModuleSource {
        override fun resolve(code: String?): GeneralActionModule? =
            if (code.isNullOrBlank() || code == "None") null else TagModule("$prefix:$code")
    }

    private val factory = GeneralActionModuleFactory(
        nationTypeRegistry = reg("nationType"),
        specialDomesticRegistry = reg("specialDomestic"),
        personalityRegistry = reg("personality"),
        itemRegistry = reg("item"),
    )

    private fun general(
        officerLevel: Int = 1,
        horse: String = "None", weapon: String = "None",
        book: String = "None", item: String = "None",
    ) = General(
        id = 1, nationId = 1, cityId = 5,
        leadership = 70, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = officerLevel,
        gold = 1000, rice = 1000,
        horse = horse, weapon = weapon, book = book, item = item,
    )

    private fun tagsOf(mods: List<GeneralActionModule>): List<String> = mods.map {
        when (it) {
            is TagModule -> it.tag
            is OfficerLevelModule -> "officer"
            else -> it::class.simpleName ?: "?"
        }
    }

    @Test
    fun `fully equipped general yields modules in MODULE_ORDER`() {
        val mods = factory.build(
            general = general(officerLevel = 5, horse = "명마", weapon = "보검", book = "병법서", item = "보물"),
            nationTypeCode = "che_유가",
            specialDomesticCode = "che_상재",
            personalityCode = "che_명사",
            nationLevel = 3,
        )
        // nationType, officer, specialDomestic, (specialWar stub skipped), personality,
        // (crew/inherit/scenario stubs skipped), horse, weapon, book, item
        assertEquals(
            listOf(
                "nationType:che_유가",
                "officer",
                "specialDomestic:che_상재",
                "personality:che_명사",
                "item:명마",
                "item:보검",
                "item:병법서",
                "item:보물",
            ),
            tagsOf(mods),
        )
    }

    @Test
    fun `null and None slots are skipped`() {
        val mods = factory.build(
            general = general(officerLevel = 5, horse = "명마", weapon = "None", book = "병법서", item = "None"),
            nationTypeCode = null,
            specialDomesticCode = "None",
            personalityCode = "",
            nationLevel = 3,
        )
        // only officer (always present) + horse + book survive
        assertEquals(listOf("officer", "item:명마", "item:병법서"), tagsOf(mods))
    }

    @Test
    fun `default general yields effectively identity stack (officer lbonus 0)`() {
        // officerLevel 1 (< 5) → lbonus 0 → leadership fold is identity; no other modules.
        val mods = factory.build(
            general = general(officerLevel = 1),
            nationTypeCode = null,
            specialDomesticCode = null,
            personalityCode = null,
            nationLevel = 7,
        )
        assertEquals(listOf("officer"), tagsOf(mods))
        val officer = mods.single() as OfficerLevelModule
        // identity: leadership unchanged because lbonus == 0
        assertEquals(70.0, officer.onCalcStat(general(), "leadership", 70.0))
    }

    @Test
    fun `officer lbonus folds leadership per calcLeadershipBonus`() {
        // officerLevel 12 → lbonus = nationLevel * 2 ; officerLevel >=5 → nationLevel ; else 0
        assertEquals(70.0 + 14.0, OfficerLevelModule(12, 7, 5, 5).onCalcStat(general(), "leadership", 70.0))
        assertEquals(70.0 + 7.0, OfficerLevelModule(5, 7, 5, 5).onCalcStat(general(), "leadership", 70.0))
        assertEquals(70.0, OfficerLevelModule(4, 7, 5, 5).onCalcStat(general(), "leadership", 70.0))
        // non-leadership stat untouched
        assertEquals(60.0, OfficerLevelModule(12, 7, 5, 5).onCalcStat(general(), "strength", 60.0))
    }

    @Test
    fun `officer demotion when level 2-4 and officer_city differs from current city`() {
        // 2<=officerLevel<=4 AND officerCity != city → officerLevel coerced to 1 → lbonus 0.
        val demoted = OfficerLevelModule(officerLevel = 3, nationLevel = 9, officerCity = 99, currentCity = 5)
        assertEquals(70.0, demoted.onCalcStat(general(), "leadership", 70.0)) // lbonus 0 (treated as level 1)
        // same city → no demotion (still level 3 < 5 → lbonus 0 anyway, but onCalcDomestic 치안 1.05 fires)
        val notDemoted = OfficerLevelModule(officerLevel = 4, nationLevel = 9, officerCity = 5, currentCity = 5)
        assertEquals(70.0 * 1.05, notDemoted.onCalcDomestic(general(), "치안", "score", 70.0))
        // demoted (level→1) loses the 치안 score buff
        assertEquals(70.0, demoted.onCalcDomestic(general(), "치안", "score", 70.0))
    }

    @Test
    fun `officer onCalcDomestic score buffs match TriggerOfficerLevel table`() {
        val lvl12 = OfficerLevelModule(12, 3, 5, 5)
        assertEquals(100.0 * 1.05, lvl12.onCalcDomestic(general(), "농업", "score", 100.0))
        assertEquals(100.0 * 1.05, lvl12.onCalcDomestic(general(), "기술", "score", 100.0))
        assertEquals(100.0 * 1.05, lvl12.onCalcDomestic(general(), "인구", "score", 100.0))
        assertEquals(100.0 * 1.05, lvl12.onCalcDomestic(general(), "수비", "score", 100.0))
        // level 2: 인구/민심 buffed, but NOT 농업
        val lvl2 = OfficerLevelModule(2, 3, 5, 5)
        assertEquals(100.0 * 1.05, lvl2.onCalcDomestic(general(), "민심", "score", 100.0))
        assertEquals(100.0, lvl2.onCalcDomestic(general(), "농업", "score", 100.0))
        // non-score varType untouched
        assertEquals(100.0, lvl12.onCalcDomestic(general(), "농업", "cost", 100.0))
    }

    @Test
    fun `factory wires the SAME officer instance the lbonus reflects construction`() {
        val mods = factory.build(
            general = general(officerLevel = 12),
            nationTypeCode = null, specialDomesticCode = null, personalityCode = null,
            nationLevel = 4,
        )
        val officer = mods.single() as OfficerLevelModule
        assertEquals(70.0 + 8.0, officer.onCalcStat(general(), "leadership", 70.0)) // 12 → nationLevel*2 = 8
    }

    @Test
    fun `registry resolving null short-circuits to a skipped slot`() {
        val r = reg("x")
        assertNull(r.resolve(null))
        assertNull(r.resolve(""))
        assertNull(r.resolve("None"))
        assertTrue(r.resolve("che_유가") is GeneralActionModule)
    }

    @Test
    fun `built list reuses the registry-resolved instances (no copy)`() {
        val captured = TagModule("captured")
        val capturingReg = object : GeneralActionModuleSource {
            override fun resolve(code: String?): GeneralActionModule? = if (code == "X") captured else null
        }
        val f = GeneralActionModuleFactory(
            nationTypeRegistry = capturingReg,
            specialDomesticRegistry = reg("sd"),
            personalityRegistry = reg("p"),
            itemRegistry = reg("i"),
        )
        val mods = f.build(
            general = general(officerLevel = 1),
            nationTypeCode = "X", specialDomesticCode = null, personalityCode = null,
            nationLevel = 1,
        )
        assertSame(captured, mods.first())
    }
}
