package com.opensam.qa.parity

import com.opensam.command.constraint.*
import com.opensam.entity.City
import com.opensam.entity.General
import com.opensam.entity.Nation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

/**
 * Constraint system parity tests verifying Kotlin matches legacy PHP.
 *
 * Legacy references:
 * - hwe/sammo/Constraint/ConstraintHelper.php: all constraint factory methods
 * - hwe/sammo/Constraint/Constraint.php: base interface
 *
 * These test that each constraint produces the same Pass/Fail for the
 * same input state as the legacy PHP implementation.
 */
@DisplayName("Constraint System Legacy Parity")
class ConstraintParityTest {

    // ──────────────────────────────────────────────────
    //  Nation membership constraints
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("NotBeNeutral - legacy ConstraintHelper::NotBeNeutral()")
    inner class NotBeNeutralParity {

        @Test
        fun `fails when nationId is 0`() {
            val gen = createGeneral(nationId = 0)
            val result = NotBeNeutral().test(ctx(gen))
            assertTrue(result is ConstraintResult.Fail)
        }

        @Test
        fun `passes when nationId is nonzero`() {
            val gen = createGeneral(nationId = 1)
            assertEquals(ConstraintResult.Pass, NotBeNeutral().test(ctx(gen)))
        }
    }

    @Nested
    @DisplayName("BeNeutral")
    inner class BeNeutralParity {

        @Test
        fun `passes when nationId is 0`() {
            assertEquals(ConstraintResult.Pass, BeNeutral().test(ctx(createGeneral(nationId = 0))))
        }

        @Test
        fun `fails when nationId is nonzero`() {
            assertTrue(BeNeutral().test(ctx(createGeneral(nationId = 1))) is ConstraintResult.Fail)
        }
    }

    // ──────────────────────────────────────────────────
    //  Resource constraints
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("ReqGeneralGold / ReqGeneralRice")
    inner class ResourceConstraintParity {

        @Test
        fun `gold passes when exactly equal`() {
            val gen = createGeneral(gold = 100)
            assertEquals(ConstraintResult.Pass, ReqGeneralGold(100).test(ctx(gen)))
        }

        @Test
        fun `gold fails when under`() {
            val gen = createGeneral(gold = 99)
            assertTrue(ReqGeneralGold(100).test(ctx(gen)) is ConstraintResult.Fail)
        }

        @Test
        fun `rice passes when over`() {
            val gen = createGeneral(rice = 200)
            assertEquals(ConstraintResult.Pass, ReqGeneralRice(100).test(ctx(gen)))
        }

        @Test
        fun `rice fails when zero`() {
            val gen = createGeneral(rice = 0)
            assertTrue(ReqGeneralRice(1).test(ctx(gen)) is ConstraintResult.Fail)
        }
    }

    // ──────────────────────────────────────────────────
    //  Crew constraints
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("ReqGeneralCrew")
    inner class CrewConstraintParity {

        @Test
        fun `passes with default minCrew 1`() {
            val gen = createGeneral(crew = 1)
            assertEquals(ConstraintResult.Pass, ReqGeneralCrew().test(ctx(gen)))
        }

        @Test
        fun `fails with 0 crew`() {
            val gen = createGeneral(crew = 0)
            assertTrue(ReqGeneralCrew().test(ctx(gen)) is ConstraintResult.Fail)
        }

        @Test
        fun `custom minCrew threshold`() {
            val gen = createGeneral(crew = 99)
            assertTrue(ReqGeneralCrew(100).test(ctx(gen)) is ConstraintResult.Fail)
            assertEquals(ConstraintResult.Pass, ReqGeneralCrew(99).test(ctx(gen)))
        }
    }

    @Nested
    @DisplayName("ReqGeneralTrainMargin / ReqGeneralAtmosMargin")
    inner class TrainAtmosMarginParity {

        @Test
        fun `train margin passes when below max`() {
            val gen = createGeneral(train = 79)
            assertEquals(ConstraintResult.Pass, ReqGeneralTrainMargin(80).test(ctx(gen)))
        }

        @Test
        fun `train margin fails at max`() {
            val gen = createGeneral(train = 80)
            assertTrue(ReqGeneralTrainMargin(80).test(ctx(gen)) is ConstraintResult.Fail)
        }

        @Test
        fun `atmos margin passes when below max`() {
            val gen = createGeneral(atmos = 79)
            assertEquals(ConstraintResult.Pass, ReqGeneralAtmosMargin(80).test(ctx(gen)))
        }

        @Test
        fun `atmos margin fails at max`() {
            val gen = createGeneral(atmos = 80)
            assertTrue(ReqGeneralAtmosMargin(80).test(ctx(gen)) is ConstraintResult.Fail)
        }
    }

    // ──────────────────────────────────────────────────
    //  City constraints
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("OccupiedCity / SuppliedCity")
    inner class CityConstraintParity {

        @Test
        fun `occupied city passes when nationId matches`() {
            val gen = createGeneral(nationId = 1)
            val city = createCity(nationId = 1)
            val c = ctx(gen, city = city)
            assertEquals(ConstraintResult.Pass, OccupiedCity().test(c))
        }

        @Test
        fun `occupied city fails when nationId differs`() {
            val gen = createGeneral(nationId = 1)
            val city = createCity(nationId = 2)
            assertTrue(OccupiedCity().test(ctx(gen, city = city)) is ConstraintResult.Fail)
        }

        @Test
        fun `occupied city with allowNeutral passes for nationId 0`() {
            val gen = createGeneral(nationId = 1)
            val city = createCity(nationId = 0)
            assertEquals(ConstraintResult.Pass, OccupiedCity(allowNeutral = true).test(ctx(gen, city = city)))
        }

        @Test
        fun `supplied city passes when supplyState above 0`() {
            val city = createCity(supplyState = 1)
            assertEquals(ConstraintResult.Pass, SuppliedCity().test(ctx(createGeneral(), city = city)))
        }

        @Test
        fun `supplied city fails when supplyState is 0`() {
            val city = createCity(supplyState = 0)
            assertTrue(SuppliedCity().test(ctx(createGeneral(), city = city)) is ConstraintResult.Fail)
        }
    }

    @Nested
    @DisplayName("RemainCityCapacity")
    inner class CityCapacityParity {

        @Test
        fun `passes when current below max`() {
            val city = createCity(agri = 999, agriMax = 1000)
            assertEquals(ConstraintResult.Pass,
                RemainCityCapacity("agri", "농업").test(ctx(createGeneral(), city = city)))
        }

        @Test
        fun `fails when current equals max`() {
            val city = createCity(agri = 1000, agriMax = 1000)
            assertTrue(RemainCityCapacity("agri", "농업").test(ctx(createGeneral(), city = city)) is ConstraintResult.Fail)
        }

        @Test
        fun `works for all city keys`() {
            for ((key, displayName) in listOf("agri" to "농업", "comm" to "상업", "secu" to "치안", "def" to "수비", "wall" to "성벽")) {
                val city = createCity(agri = 999, comm = 999, secu = 999, def = 999, wall = 999,
                    agriMax = 1000, commMax = 1000, secuMax = 1000, defMax = 1000, wallMax = 1000)
                assertEquals(ConstraintResult.Pass,
                    RemainCityCapacity(key, displayName).test(ctx(createGeneral(), city = city)),
                    "Should pass for $key")
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  Officer level constraints
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("BeChief / BeLord / NotChief")
    inner class OfficerParity {

        @Test
        fun `BeChief passes at level 12`() {
            val gen = createGeneral(officerLevel = 12)
            assertEquals(ConstraintResult.Pass, BeChief().test(ctx(gen)))
        }

        @Test
        fun `BeChief fails below 12`() {
            val gen = createGeneral(officerLevel = 11)
            assertTrue(BeChief().test(ctx(gen)) is ConstraintResult.Fail)
        }

        @Test
        fun `NotChief passes below 12`() {
            val gen = createGeneral(officerLevel = 5)
            assertEquals(ConstraintResult.Pass, NotChief().test(ctx(gen)))
        }

        @Test
        fun `NotChief fails at 12`() {
            val gen = createGeneral(officerLevel = 12)
            assertTrue(NotChief().test(ctx(gen)) is ConstraintResult.Fail)
        }
    }

    // ──────────────────────────────────────────────────
    //  Crew margin constraints
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("ReqGeneralCrewMargin")
    inner class CrewMarginParity {

        @Test
        fun `passes when different crew type`() {
            val gen = createGeneral(crewType = 1100, crew = 10000, leadership = 50)
            assertEquals(ConstraintResult.Pass, ReqGeneralCrewMargin(1200).test(ctx(gen)))
        }

        @Test
        fun `passes when same type with room`() {
            val gen = createGeneral(crewType = 1100, crew = 4999, leadership = 50)
            assertEquals(ConstraintResult.Pass, ReqGeneralCrewMargin(1100).test(ctx(gen)))
        }

        @Test
        fun `fails when same type at max`() {
            // maxCrew = 50*100 = 5000; crew=5000 → not > → fail
            val gen = createGeneral(crewType = 1100, crew = 5000, leadership = 50)
            assertTrue(ReqGeneralCrewMargin(1100).test(ctx(gen)) is ConstraintResult.Fail)
        }
    }

    // ──────────────────────────────────────────────────
    //  Nation resource constraints
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("ReqNationGold / ReqNationRice")
    inner class NationResourceParity {

        @Test
        fun `nation gold passes when sufficient`() {
            val nation = createNation(gold = 1000)
            assertEquals(ConstraintResult.Pass,
                ReqNationGold(1000).test(ctx(createGeneral(), nation = nation)))
        }

        @Test
        fun `nation gold fails when insufficient`() {
            val nation = createNation(gold = 999)
            assertTrue(ReqNationGold(1000).test(ctx(createGeneral(), nation = nation)) is ConstraintResult.Fail)
        }

        @Test
        fun `nation rice passes when sufficient`() {
            val nation = createNation(rice = 500)
            assertEquals(ConstraintResult.Pass,
                ReqNationRice(500).test(ctx(createGeneral(), nation = nation)))
        }
    }

    // ──────────────────────────────────────────────────
    //  Constraint chain
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Constraint chain - all-pass and first-fail")
    inner class ChainParity {

        @Test
        fun `empty chain passes`() {
            assertEquals(ConstraintResult.Pass, ConstraintChain.testAll(emptyList(), ctx(createGeneral())))
        }

        @Test
        fun `chain stops at first failure`() {
            val gen = createGeneral(nationId = 0, gold = 0)
            val constraints = listOf(
                NotBeNeutral(),
                ReqGeneralGold(100),
            )
            val result = ConstraintChain.testAll(constraints, ctx(gen))
            assertTrue(result is ConstraintResult.Fail)
            // Should fail on NotBeNeutral, not ReqGeneralGold
            assertTrue((result as ConstraintResult.Fail).reason.contains("소속 국가"))
        }

        @Test
        fun `chain passes when all pass`() {
            val gen = createGeneral(nationId = 1, gold = 500)
            val constraints = listOf(
                NotBeNeutral(),
                ReqGeneralGold(100),
            )
            assertEquals(ConstraintResult.Pass, ConstraintChain.testAll(constraints, ctx(gen)))
        }
    }

    // ──────────────────────────────────────────────────
    //  AlwaysFail
    // ──────────────────────────────────────────────────

    @Test
    fun `AlwaysFail always fails with given reason`() {
        val result = AlwaysFail("테스트 실패 사유").test(ctx(createGeneral()))
        assertTrue(result is ConstraintResult.Fail)
        assertEquals("테스트 실패 사유", (result as ConstraintResult.Fail).reason)
    }

    // ──────────────────────────────────────────────────
    //  Dest general/city constraints
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Dest entity constraints")
    inner class DestEntityParity {

        @Test
        fun `ExistsDestGeneral passes when present`() {
            val gen = createGeneral()
            val dest = createGeneral(id = 2)
            assertEquals(ConstraintResult.Pass, ExistsDestGeneral().test(ctx(gen, destGeneral = dest)))
        }

        @Test
        fun `ExistsDestGeneral fails when null`() {
            assertTrue(ExistsDestGeneral().test(ctx(createGeneral())) is ConstraintResult.Fail)
        }

        @Test
        fun `FriendlyDestGeneral passes when same nation`() {
            val gen = createGeneral(nationId = 1)
            val dest = createGeneral(id = 2, nationId = 1)
            assertEquals(ConstraintResult.Pass, FriendlyDestGeneral().test(ctx(gen, destGeneral = dest)))
        }

        @Test
        fun `FriendlyDestGeneral fails when different nation`() {
            val gen = createGeneral(nationId = 1)
            val dest = createGeneral(id = 2, nationId = 2)
            assertTrue(FriendlyDestGeneral().test(ctx(gen, destGeneral = dest)) is ConstraintResult.Fail)
        }

        @Test
        fun `NotSameDestCity passes when different`() {
            val gen = createGeneral(cityId = 1)
            val destCity = createCity(id = 2)
            assertEquals(ConstraintResult.Pass, NotSameDestCity().test(ctx(gen, destCity = destCity)))
        }

        @Test
        fun `NotSameDestCity fails when same`() {
            val gen = createGeneral(cityId = 1)
            val destCity = createCity(id = 1)
            assertTrue(NotSameDestCity().test(ctx(gen, destCity = destCity)) is ConstraintResult.Fail)
        }
    }

    // ──────────────────────────────────────────────────
    //  Injury constraint
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("NotInjured")
    inner class InjuryConstraintParity {

        @Test
        fun `passes when no injury`() {
            val gen = createGeneral(injury = 0)
            assertEquals(ConstraintResult.Pass, NotInjured().test(ctx(gen)))
        }

        @Test
        fun `fails when injured`() {
            val gen = createGeneral(injury = 1)
            assertTrue(NotInjured().test(ctx(gen)) is ConstraintResult.Fail)
        }

        @Test
        fun `passes with custom maxInjury threshold`() {
            val gen = createGeneral(injury = 20)
            assertEquals(ConstraintResult.Pass, NotInjured(maxInjury = 20).test(ctx(gen)))
            assertTrue(NotInjured(maxInjury = 19).test(ctx(gen)) is ConstraintResult.Fail)
        }
    }

    // ──────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────

    private fun ctx(
        general: General,
        city: City? = null,
        nation: Nation? = null,
        destGeneral: General? = null,
        destCity: City? = null,
        destNation: Nation? = null,
        env: Map<String, Any> = emptyMap(),
    ) = ConstraintContext(
        general = general,
        city = city,
        nation = nation,
        destGeneral = destGeneral,
        destCity = destCity,
        destNation = destNation,
        env = env,
    )

    private fun createGeneral(
        id: Long = 1,
        nationId: Long = 1,
        cityId: Long = 1,
        gold: Int = 500,
        rice: Int = 500,
        crew: Int = 1000,
        crewType: Short = 0,
        train: Short = 60,
        atmos: Short = 60,
        officerLevel: Short = 5,
        leadership: Short = 70,
        injury: Short = 0,
    ): General = General(
        id = id,
        worldId = 1,
        name = "테스트장수",
        nationId = nationId,
        cityId = cityId,
        gold = gold,
        rice = rice,
        crew = crew,
        crewType = crewType,
        train = train,
        atmos = atmos,
        leadership = leadership,
        strength = 70,
        intel = 70,
        politics = 60,
        charm = 60,
        officerLevel = officerLevel,
        injury = injury,
        turnTime = OffsetDateTime.now(),
    )

    private fun createCity(
        id: Long = 1,
        nationId: Long = 1,
        supplyState: Short = 1,
        agri: Int = 500,
        agriMax: Int = 1000,
        comm: Int = 500,
        commMax: Int = 1000,
        secu: Int = 500,
        secuMax: Int = 1000,
        def: Int = 500,
        defMax: Int = 1000,
        wall: Int = 500,
        wallMax: Int = 1000,
        trust: Float = 80f,
    ): City = City(
        id = id,
        worldId = 1,
        name = "테스트도시",
        nationId = nationId,
        pop = 50000,
        popMax = 100000,
        agri = agri,
        agriMax = agriMax,
        comm = comm,
        commMax = commMax,
        secu = secu,
        secuMax = secuMax,
        def = def,
        defMax = defMax,
        wall = wall,
        wallMax = wallMax,
        trust = trust,
        supplyState = supplyState,
    )

    private fun createNation(
        id: Long = 1,
        gold: Int = 10000,
        rice: Int = 10000,
    ): Nation = Nation(
        id = id,
        worldId = 1,
        name = "테스트국",
        gold = gold,
        rice = rice,
    )
}
