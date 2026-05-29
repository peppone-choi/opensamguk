package opensamguk.common.constants

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GameConstTest {
    @Test fun scalarSlice() {
        assertEquals("che", GameConst.mapName); assertEquals("che", GameConst.unitSet)
        assertEquals(50, GameConst.develrate); assertEquals(30, GameConst.upgradeLimit)
        assertEquals(0.35, GameConst.sabotageDefaultProb, 0.0)
        assertEquals(255, GameConst.maxLevel)   // PHP grand truth; do NOT change to 7/9
        assertEquals(30, GameConst.maxTurn); assertEquals(12, GameConst.maxTechLevel); assertEquals(80, GameConst.retirementYear)
    }
    @Test fun enumeratedSets() {
        assertEquals(13, GameConst.availableNationType.size); assertEquals("che_도적", GameConst.availableNationType.first())
        assertEquals("che_중립", GameConst.neutralNationType)
        assertEquals(8, GameConst.availableSpecialDomestic.size); assertEquals(20, GameConst.availableSpecialWar.size)
        assertEquals(10, GameConst.availablePersonality.size)
    }
    @Test fun itemCatalogShape() {
        assertEquals(2, GameConst.allItems.getValue("horse").getValue("che_명마_15_적토마"))
        assertEquals(0, GameConst.allItems.getValue("weapon").getValue("che_무기_01_단도"))
    }
    @Test fun commandMenusAreData() {
        assertEquals(listOf("che_농지개간","che_상업투자","che_기술연구","che_수비강화","che_성벽보수","che_치안강화","che_정착장려","che_주민선정"),
            GameConst.availableGeneralCommand.getValue("내정"))
        assertTrue(GameConst.availableChiefCommand.getValue("외교").contains("che_물자원조"))
    }
    @Test fun eventTablesAreDeclarativeData() {
        val first = GameConst.defaultInitialEvents.first(); assertEquals(2, first.size)
        assertTrue(GameConst.defaultEvents.any { it.firstOrNull() == "pre_month" })
        assertTrue(GameConst.defaultEvents.any { it.firstOrNull() == "united" })
    }
}
