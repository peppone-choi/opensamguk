package opensamguk.logic.statview

import opensamguk.logic.constraints.RequirementKey
import opensamguk.logic.domain.City
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MemoryStateViewTest {

    private fun general(id: Int) = General(
        id = id, nationId = 1, cityId = 5,
        leadership = 10, strength = 10, intel = 10, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 1000, rice = 1000,
    )

    private fun city(id: Int) = City(
        id = id, nationId = 1, level = 5,
        commerce = 100, commerceMax = 1000,
        agriculture = 100, agricultureMax = 1000,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private fun view() = MemoryStateView(
        generals = mapOf(1 to general(1)),
        cities = mapOf(5 to city(5)),
        nations = mapOf(1 to Nation(id = 1, level = 7, capitalCityId = 5)),
        env = mapOf("year" to 260),
    )

    @Test
    fun `has(General(1)) is true when present`() {
        assertTrue(view().has(RequirementKey.General(1)))
    }

    @Test
    fun `has(General) is false when absent`() {
        assertFalse(view().has(RequirementKey.General(2)))
    }

    @Test
    fun `get(City(5)) returns the city`() {
        assertSame(city(5).id, (view().get(RequirementKey.City(5)) as City).id)
        assertEquals(city(5), view().get(RequirementKey.City(5)))
    }

    @Test
    fun `get(Env year) returns the env value`() {
        assertEquals(260, view().get(RequirementKey.Env("year")))
    }

    @Test
    fun `Arg has is always true and get is null`() {
        assertTrue(view().has(RequirementKey.Arg("anything")))
        assertNull(view().get(RequirementKey.Arg("anything")))
    }
}
