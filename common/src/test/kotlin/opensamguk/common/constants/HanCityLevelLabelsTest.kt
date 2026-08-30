package opensamguk.common.constants

import kotlin.test.Test
import kotlin.test.assertEquals

class HanCityLevelLabelsTest {
    @Test
    fun `han capital and county ranks are visible through shared API labels`() {
        val expected = mapOf(9 to "경", 10 to "영현", 11 to "장현")

        expected.forEach { (level, label) ->
            assertEquals(label, CityConst.levelMap[level])
            assertEquals(level, CityConst.levelMap[label])
            assertEquals(label, getCityLevelList()[level])
        }
    }
}
