package com.opensam.command

import com.opensam.command.constraint.*
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class ConstraintChainTest {

    private fun createGeneral(
        nationId: Long = 1,
        cityId: Long = 1,
        gold: Int = 1000,
        rice: Int = 1000,
        crew: Int = 100,
        officerLevel: Short = 5,
        injury: Short = 0,
    ): General {
        return General(
            id = 1,
            worldId = 1,
            name = "테스트",
            nationId = nationId,
            cityId = cityId,
            gold = gold,
            rice = rice,
            crew = crew,
            officerLevel = officerLevel,
            injury = injury,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun createCity(nationId: Long = 1, supplyState: Short = 1): City {
        return City(
            id = 1,
            worldId = 1,
            name = "테스트도시",
            nationId = nationId,
            supplyState = supplyState,
        )
    }

    private fun ctx(
        general: General = createGeneral(),
        city: City? = null,
        nation: Nation? = null,
    ) = ConstraintContext(general = general, city = city, nation = nation)

    // ========== ConstraintChain.testAll ==========

    @Test
    fun `testAll returns Pass when all constraints pass`() {
        val general = createGeneral(nationId = 1, gold = 500)
        val city = createCity(nationId = 1, supplyState = 1)
        val constraints = listOf(
            NotBeNeutral(),
            OccupiedCity(),
            ReqGeneralGold(100),
        )
        val result = ConstraintChain.testAll(constraints, ctx(general = general, city = city))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `testAll returns first failure`() {
        val general = createGeneral(nationId = 0)
        val constraints = listOf(
            NotBeNeutral(),
            OccupiedCity(),
        )
        val result = ConstraintChain.testAll(constraints, ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("소속 국가"))
    }

    @Test
    fun `testAll with empty list returns Pass`() {
        val result = ConstraintChain.testAll(emptyList(), ctx())
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `testAll stops at first failure`() {
        val general = createGeneral(nationId = 1, gold = 50)
        val city = createCity(nationId = 2) // wrong nation
        val constraints = listOf(
            OccupiedCity(),      // will fail
            ReqGeneralGold(100), // would also fail but won't be reached
        )
        val result = ConstraintChain.testAll(constraints, ctx(general = general, city = city))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("아군 도시"))
    }

    // ========== New constraints: HasRoute ==========

    @Test
    fun `HasRoute passes with dest city`() {
        val destCity = createCity().apply { id = 2 }
        val result = HasRoute().test(ConstraintContext(general = createGeneral(), destCity = destCity))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `HasRoute fails without dest city`() {
        val result = HasRoute().test(ctx())
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("목적지"))
    }

    // ========== New constraints: AllowDiplomacy ==========

    @Test
    fun `AllowDiplomacy passes with sufficient officer level`() {
        val general = createGeneral(officerLevel = 5)
        val result = AllowDiplomacy(5).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `AllowDiplomacy fails with insufficient officer level`() {
        val general = createGeneral(officerLevel = 3)
        val result = AllowDiplomacy(5).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("외교 권한"))
    }

    // ========== New constraints: NotInjured ==========

    @Test
    fun `NotInjured passes when not injured`() {
        val general = createGeneral(injury = 0)
        val result = NotInjured(0).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `NotInjured passes when injury below threshold`() {
        val general = createGeneral(injury = 10)
        val result = NotInjured(20).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Pass)
    }

    @Test
    fun `NotInjured fails when injury exceeds threshold`() {
        val general = createGeneral(injury = 50)
        val result = NotInjured(20).test(ctx(general = general))
        assertTrue(result is ConstraintResult.Fail)
        assertTrue((result as ConstraintResult.Fail).reason.contains("부상"))
    }
}
