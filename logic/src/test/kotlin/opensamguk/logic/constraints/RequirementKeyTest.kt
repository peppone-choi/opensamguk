package opensamguk.logic.constraints

import opensamguk.logic.domain.City
import opensamguk.logic.domain.Diplomacy
import opensamguk.logic.domain.General
import opensamguk.logic.domain.Nation
import opensamguk.logic.statview.MemoryStateView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CD1 — `RequirementKey` Dest-* / NationList / GeneralList / Diplomacy + `ConstraintContext` dest-ids.
 *
 * The DB-backed dest-* constraint family NEVER touches the DB inside `test()`: the two adapters
 * (precheck + daemon) preload the dest general/city/nation rows + the diplomacy rows + the full
 * nation/general collections. [MemoryStateView] is the precheck-side resolver — assert `has`/`get`
 * for every new key, and that `NationList`/`GeneralList` return the full collections
 * (CheckNationNameDuplicate scans NationList; ReqNationValue('gennum') etc. read individual rows).
 */
class RequirementKeyTest {

    private fun general(id: Int, nationId: Int = 1, cityId: Int = 5) = General(
        id = id, nationId = nationId, cityId = cityId,
        leadership = 10, strength = 10, intel = 10, injury = 0,
        experience = 0.0, dedication = 0.0, officerLevel = 0,
        gold = 100, rice = 100,
    )

    private fun city(id: Int, nationId: Int = 1) = City(
        id = id, nationId = nationId, level = 5,
        commerce = 1, commerceMax = 10, agriculture = 1, agricultureMax = 10,
        supplyState = 1, frontState = 0, trust = 50.0,
    )

    private fun nation(id: Int, name: String = "위") = Nation(id = id, level = 7, capitalCityId = null, name = name)

    @Test fun `DestGeneral key resolves the preloaded dest general row`() {
        val actor = general(1, nationId = 1)
        val dest = general(2, nationId = 2)
        val view = MemoryStateView(
            generals = linkedMapOf(actor.id to actor, dest.id to dest),
            cities = emptyMap(), nations = emptyMap(), env = emptyMap(),
        )
        assertTrue(view.has(RequirementKey.DestGeneral(2)))
        assertEquals(dest, view.get(RequirementKey.DestGeneral(2)))
        assertFalse(view.has(RequirementKey.DestGeneral(99)))
        assertNull(view.get(RequirementKey.DestGeneral(99)))
    }

    @Test fun `DestCity key resolves the preloaded dest city row`() {
        val dest = city(7, nationId = 2)
        val view = MemoryStateView(
            generals = emptyMap(), cities = linkedMapOf(dest.id to dest),
            nations = emptyMap(), env = emptyMap(),
        )
        assertTrue(view.has(RequirementKey.DestCity(7)))
        assertEquals(dest, view.get(RequirementKey.DestCity(7)))
        assertFalse(view.has(RequirementKey.DestCity(99)))
    }

    @Test fun `DestNation key resolves the preloaded dest nation row`() {
        val dest = nation(2, name = "촉")
        val view = MemoryStateView(
            generals = emptyMap(), cities = emptyMap(),
            nations = linkedMapOf(dest.id to dest), env = emptyMap(),
        )
        assertTrue(view.has(RequirementKey.DestNation(2)))
        assertEquals(dest, view.get(RequirementKey.DestNation(2)))
        assertFalse(view.has(RequirementKey.DestNation(99)))
    }

    @Test fun `NationList returns the full nation collection`() {
        val n1 = nation(1, name = "위")
        val n2 = nation(2, name = "촉")
        val view = MemoryStateView(
            generals = emptyMap(), cities = emptyMap(),
            nations = linkedMapOf(n1.id to n1, n2.id to n2), env = emptyMap(),
        )
        assertTrue(view.has(RequirementKey.NationList))
        @Suppress("UNCHECKED_CAST")
        val list = view.get(RequirementKey.NationList) as Collection<Nation>
        assertEquals(listOf(n1, n2), list.toList())
    }

    @Test fun `GeneralList returns the full general collection`() {
        val g1 = general(1)
        val g2 = general(2, nationId = 2)
        val view = MemoryStateView(
            generals = linkedMapOf(g1.id to g1, g2.id to g2),
            cities = emptyMap(), nations = emptyMap(), env = emptyMap(),
        )
        assertTrue(view.has(RequirementKey.GeneralList))
        @Suppress("UNCHECKED_CAST")
        val list = view.get(RequirementKey.GeneralList) as Collection<General>
        assertEquals(listOf(g1, g2), list.toList())
    }

    @Test fun `Diplomacy key resolves the preloaded directional row`() {
        val dip = Diplomacy(me = 1, you = 2, state = 0, term = 3)
        val view = MemoryStateView(
            generals = emptyMap(), cities = emptyMap(), nations = emptyMap(), env = emptyMap(),
            diplomacy = listOf(dip),
        )
        assertTrue(view.has(RequirementKey.Diplomacy(1, 2)))
        assertEquals(dip, view.get(RequirementKey.Diplomacy(1, 2)))
        assertFalse(view.has(RequirementKey.Diplomacy(2, 1)))
        assertNull(view.get(RequirementKey.Diplomacy(2, 1)))
    }

    @Test fun `ConstraintContext carries the dest ids`() {
        val ctx = ConstraintContext(
            actorId = 1, cityId = 5, nationId = 1,
            destGeneralId = 2, destCityId = 7, destNationId = 3,
            mode = ConstraintMode.FULL,
        )
        assertEquals(2, ctx.destGeneralId)
        assertEquals(7, ctx.destCityId)
        assertEquals(3, ctx.destNationId)
    }
}
