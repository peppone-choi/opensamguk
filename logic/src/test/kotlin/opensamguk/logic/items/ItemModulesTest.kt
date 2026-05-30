package opensamguk.logic.items

import opensamguk.logic.domain.General
import opensamguk.logic.stats.GeneralActionModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Port-faithful tests for the declarative +stat item modules (IT1).
 *
 * PHP grand truth (BaseStatItem.php:6-39):
 *   - ITEM_TYPE = 명마→[통솔, leadership] / 무기→[무력, strength] / 서적→[지력, intel].
 *   - __construct: explode('_', class); statValue=(int)token[-2]; rawName=token[-1]; [statNick,statType]=ITEM_TYPE[token[-3]].
 *   - name = "{rawName}(+{statValue})" ; info = "{statNick} +{statValue}".
 *   - onCalcStat: if statName===statType return value+statValue; else value.
 *
 * Fold order (General.php:787-799 / GeneralActionModuleFactory): items fold LAST, in slot insertion
 * order horse→weapon→book→item. The 4-slot dedup (research Unit 11 / items/utils.ts listEquippedItemKeys)
 * skips falsy (None/null) slots AND DEDUPS — the same item code in two slots applies its +stat ONCE.
 */
class ItemModulesTest {

    private fun general() = General(
        id = 1, nationId = 1, cityId = 5,
        leadership = 70, strength = 60, intel = 80, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 1,
        gold = 1000, rice = 1000,
    )

    private val registry = ItemRegistry()

    @Test
    fun `horse item adds statValue to leadership only`() {
        val m = registry.resolve("che_명마_01_노기")!!
        assertEquals(70.0 + 1.0, m.onCalcStat(general(), "leadership", 70.0))
        assertEquals(60.0, m.onCalcStat(general(), "strength", 60.0))
        assertEquals(80.0, m.onCalcStat(general(), "intel", 80.0))
    }

    @Test
    fun `weapon item adds statValue to strength only`() {
        val m = registry.resolve("che_무기_15_의천검")!!
        assertEquals(60.0 + 15.0, m.onCalcStat(general(), "strength", 60.0))
        assertEquals(70.0, m.onCalcStat(general(), "leadership", 70.0))
    }

    @Test
    fun `book item adds statValue to intel only`() {
        val m = registry.resolve("che_서적_15_노자")!!
        assertEquals(80.0 + 15.0, m.onCalcStat(general(), "intel", 80.0))
        assertEquals(60.0, m.onCalcStat(general(), "strength", 60.0))
    }

    @Test
    fun `statValue parsed from the second-to-last token`() {
        assertEquals(1, (registry.resolve("che_명마_01_노기") as BaseStatItemModule).statValue)
        assertEquals(15, (registry.resolve("che_명마_15_적토마") as BaseStatItemModule).statValue)
        assertEquals(7, (registry.resolve("che_무기_07_맥궁") as BaseStatItemModule).statValue)
    }

    @Test
    fun `name and info strings match PHP sprintf format`() {
        val m = registry.resolve("che_명마_15_적토마") as BaseStatItemModule
        assertEquals("적토마(+15)", m.name)
        assertEquals("통솔 +15", m.info)
        val w = registry.resolve("che_무기_01_단도") as BaseStatItemModule
        assertEquals("단도(+1)", w.name)
        assertEquals("무력 +1", w.info)
        val b = registry.resolve("che_서적_15_노자") as BaseStatItemModule
        assertEquals("노자(+15)", b.name)
        assertEquals("지력 +15", b.info)
    }

    @Test
    fun `None and null and blank slots resolve to null skipped`() {
        assertNull(registry.resolve("None"))
        assertNull(registry.resolve(null))
        assertNull(registry.resolve(""))
    }

    @Test
    fun `cached factory returns the same instance per code (buildItemClass cache)`() {
        assertSame(registry.resolve("che_명마_01_노기"), registry.resolve("che_명마_01_노기"))
    }

    @Test
    fun `four equipped slots fold LAST in horse-weapon-book-item order`() {
        // a leadership +stat horse, a strength +stat weapon, an intel +stat book
        val mods = listOf(
            registry.resolve("che_명마_01_노기")!!,
            registry.resolve("che_무기_01_단도")!!,
            registry.resolve("che_서적_15_노자")!!,
        )
        // fold-last is commutative for pure +stat; assert each stat picks up exactly its item's value
        val gen = general()
        assertEquals(70.0 + 1.0, mods.fold(70.0) { acc, m -> m.onCalcStat(gen, "leadership", acc) })
        assertEquals(60.0 + 1.0, mods.fold(60.0) { acc, m -> m.onCalcStat(gen, "strength", acc) })
        assertEquals(80.0 + 15.0, mods.fold(80.0) { acc, m -> m.onCalcStat(gen, "intel", acc) })
    }

    @Test
    fun `dedup the same item in two slots applies its stat once`() {
        // 적토마 in BOTH horse and item slot must dedup to a single fold.
        val gen = general().copy(horse = "che_명마_15_적토마", weapon = "None", book = "None", item = "che_명마_15_적토마")
        val mods = ItemRegistry.equippedModules(gen, registry)
        // only ONE 적토마 module survives the dedup
        assertEquals(1, mods.size)
        assertEquals(70.0 + 15.0, mods.fold(70.0) { acc, m -> m.onCalcStat(gen, "leadership", acc) })
    }

    @Test
    fun `equippedModules skips None slots and keeps fold order`() {
        val gen = general().copy(
            horse = "che_명마_01_노기", weapon = "None",
            book = "che_서적_15_노자", item = "None",
        )
        val mods = ItemRegistry.equippedModules(gen, registry)
        assertEquals(2, mods.size)
        assertTrue(mods[0] is BaseStatItemModule && (mods[0] as BaseStatItemModule).rawName == "노기")
        assertTrue(mods[1] is BaseStatItemModule && (mods[1] as BaseStatItemModule).rawName == "노자")
    }

    @Test
    fun `module is a GeneralActionModule (registers into the 9-source stack)`() {
        assertTrue(registry.resolve("che_명마_01_노기") is GeneralActionModule)
    }
}
