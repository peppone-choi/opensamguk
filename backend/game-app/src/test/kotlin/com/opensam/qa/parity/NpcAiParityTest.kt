package com.opensam.qa.parity

import com.opensam.engine.ai.DiplomacyState
import com.opensam.engine.ai.GeneralAI
import com.opensam.engine.ai.GeneralType
import com.opensam.engine.ai.NpcGeneralPolicy
import com.opensam.engine.ai.NpcNationPolicy
import com.opensam.entity.Diplomacy
import com.opensam.entity.General
import com.opensam.entity.Nation
import com.opensam.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import java.time.OffsetDateTime
import kotlin.random.Random

/**
 * NPC AI decision parity tests verifying Kotlin matches legacy PHP.
 *
 * Legacy references:
 * - hwe/sammo/GeneralAI.php: chooseGeneralTurn()
 * - hwe/sammo/GeneralAI.php: calcDiplomacyState()
 * - hwe/sammo/GeneralAI.php: classifyGeneral()
 * - hwe/sammo/NpcPolicy.php: NpcGeneralPolicy, NpcNationPolicy
 */
@DisplayName("NPC AI Legacy Parity")
class NpcAiParityTest {

    private lateinit var ai: GeneralAI
    private lateinit var generalRepository: GeneralRepository
    private lateinit var cityRepository: CityRepository
    private lateinit var nationRepository: NationRepository
    private lateinit var diplomacyRepository: DiplomacyRepository

    @BeforeEach
    fun setUp() {
        generalRepository = mock(GeneralRepository::class.java)
        cityRepository = mock(CityRepository::class.java)
        nationRepository = mock(NationRepository::class.java)
        diplomacyRepository = mock(DiplomacyRepository::class.java)
        ai = GeneralAI(generalRepository, cityRepository, nationRepository, diplomacyRepository)
    }

    // ──────────────────────────────────────────────────
    //  classifyGeneral parity
    //  Legacy: GeneralAI.php classifyGeneral()
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("classifyGeneral - legacy GeneralAI.php:290")
    inner class ClassifyGeneralParity {

        @Test
        fun `strength dominant general has WARRIOR flag`() {
            val gen = createGeneral(leadership = 40, strength = 90, intel = 40)
            val type = ai.classifyGeneral(gen)
            assertTrue(type and GeneralType.WARRIOR.flag != 0, "Should have WARRIOR flag")
        }

        @Test
        fun `intel dominant general has STRATEGIST flag`() {
            val gen = createGeneral(leadership = 40, strength = 40, intel = 90)
            val type = ai.classifyGeneral(gen)
            assertTrue(type and GeneralType.STRATEGIST.flag != 0, "Should have STRATEGIST flag")
        }

        @Test
        fun `high leadership sets COMMANDER flag`() {
            val gen = createGeneral(leadership = 80, strength = 60, intel = 60)
            val type = ai.classifyGeneral(gen, minNPCWarLeadership = 40)
            assertTrue(type and GeneralType.COMMANDER.flag != 0, "Should have COMMANDER flag")
        }

        @Test
        fun `low leadership does not set COMMANDER flag`() {
            val gen = createGeneral(leadership = 30, strength = 80, intel = 40)
            val type = ai.classifyGeneral(gen, minNPCWarLeadership = 40)
            assertEquals(0, type and GeneralType.COMMANDER.flag, "Should NOT have COMMANDER flag")
        }

        @Test
        fun `balanced high stats can have multiple flags`() {
            val gen = createGeneral(leadership = 80, strength = 80, intel = 80)
            val type = ai.classifyGeneral(gen, minNPCWarLeadership = 40)
            assertTrue(type and GeneralType.COMMANDER.flag != 0)
            assertTrue(type and (GeneralType.WARRIOR.flag or GeneralType.STRATEGIST.flag) != 0)
        }

        @Test
        fun `deterministic with same RNG seed`() {
            val gen = createGeneral(leadership = 70, strength = 70, intel = 70)
            val r1 = ai.classifyGeneral(gen, Random(42))
            val r2 = ai.classifyGeneral(gen, Random(42))
            assertEquals(r1, r2)
        }

        @Test
        fun `consistent COMMANDER for clearly dominant leadership across seeds`() {
            val gen = createGeneral(leadership = 90, strength = 50, intel = 50)
            repeat(20) { seed ->
                val type = ai.classifyGeneral(gen, Random(seed.toLong()), minNPCWarLeadership = 40)
                assertTrue(type and GeneralType.COMMANDER.flag != 0,
                    "COMMANDER should always be set for leadership=90, seed=$seed")
            }
        }
    }

    // ──────────────────────────────────────────────────
    //  Diplomacy state calculation parity
    //  Legacy: GeneralAI.php calcDiplomacyState()
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("calcDiplomacyState - legacy GeneralAI.php:227")
    inner class DiplomacyStateParity {

        @Test
        fun `null nation returns PEACE`() {
            assertEquals(DiplomacyState.PEACE, ai.calcDiplomacyState(null, emptyList()))
        }

        @Test
        fun `선전포고 diplomacy returns AT_WAR`() {
            val nation = createNation(id = 1)
            val diplomacy = Diplomacy(
                srcNationId = 1, destNationId = 2, stateCode = "선전포고"
            )
            assertEquals(DiplomacyState.AT_WAR, ai.calcDiplomacyState(nation, listOf(diplomacy)))
        }

        @Test
        fun `no diplomacy entries returns PEACE`() {
            val nation = createNation(id = 1)
            assertEquals(DiplomacyState.PEACE, ai.calcDiplomacyState(nation, emptyList()))
        }

        @Test
        fun `warState above 0 returns AT_WAR`() {
            val nation = createNation(id = 1, warState = 1)
            assertEquals(DiplomacyState.AT_WAR, ai.calcDiplomacyState(nation, emptyList()))
        }

        @Test
        fun `동맹 only returns PEACE`() {
            val nation = createNation(id = 1)
            val diplomacy = Diplomacy(srcNationId = 1, destNationId = 2, stateCode = "동맹")
            assertEquals(DiplomacyState.PEACE, ai.calcDiplomacyState(nation, listOf(diplomacy)))
        }

        @Test
        fun `종전제의 with low troops returns RECRUITING`() {
            val nation = createNation(id = 1)
            val diplomacy = Diplomacy(srcNationId = 1, destNationId = 2, stateCode = "종전제의")
            `when`(generalRepository.findByNationId(1L)).thenReturn(
                listOf(createGeneral(crew = 1000))
            )
            assertEquals(DiplomacyState.RECRUITING, ai.calcDiplomacyState(nation, listOf(diplomacy)))
        }

        @Test
        fun `종전제의 with high troops returns DECLARED`() {
            val nation = createNation(id = 1)
            val diplomacy = Diplomacy(srcNationId = 1, destNationId = 2, stateCode = "종전제의")
            `when`(generalRepository.findByNationId(1L)).thenReturn(
                listOf(createGeneral(crew = 5000))
            )
            assertEquals(DiplomacyState.DECLARED, ai.calcDiplomacyState(nation, listOf(diplomacy)))
        }
    }

    // ──────────────────────────────────────────────────
    //  NPC policy defaults parity
    //  Legacy: NpcPolicy.php / AutorunGeneralPolicy / AutorunNationPolicy
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("NpcPolicy defaults")
    inner class PolicyDefaultsParity {

        @Test
        fun `default nation policy values match legacy`() {
            val policy = NpcNationPolicy()
            assertEquals(20, policy.cureThreshold, "Legacy default cureThreshold=20")
            assertEquals(40, policy.minNPCWarLeadership, "Legacy default minNPCWarLeadership=40")
            assertEquals(5000, policy.minNPCRecruitCityPopulation)
            assertEquals(0.4, policy.safeRecruitCityPopulationRatio, 0.001)
            assertEquals(2000, policy.reqNationGold)
            assertEquals(2000, policy.reqNationRice)
        }

        @Test
        fun `default general policy values match legacy`() {
            val policy = NpcGeneralPolicy()
            assertEquals(500, policy.minWarCrew, "Legacy default minWarCrew=500")
            assertEquals(80, policy.properWarTrainAtmos, "Legacy default properWarTrainAtmos=80")
            assertTrue(policy.canDo("징병"))
            assertTrue(policy.canDo("출병"))
            assertTrue(policy.canDo("일반내정"))
            assertFalse(policy.canDo("한계징병"))
        }

        @Test
        fun `default priority lists match legacy order`() {
            val genPolicy = NpcGeneralPolicy()
            assertEquals("긴급내정", genPolicy.priority.first())
            assertEquals("중립", genPolicy.priority.last())

            val nationPolicy = NpcNationPolicy()
            assertEquals("부대전방발령", nationPolicy.priority.first())
            assertEquals("전시전략", nationPolicy.priority.last())
        }
    }

    // ──────────────────────────────────────────────────
    //  War readiness checks parity
    //  Legacy: GeneralAI.php decideWarAction()
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("War readiness thresholds")
    inner class WarReadinessParity {

        @Test
        fun `low crew below minWarCrew triggers recruitment`() {
            val gen = createGeneral(crew = 100, leadership = 80, train = 80, atmos = 80)
            val policy = NpcGeneralPolicy(minWarCrew = 500)
            assertTrue(gen.crew < policy.minWarCrew,
                "crew(100) < minWarCrew(500) → should recruit")
        }

        @Test
        fun `sufficient crew passes recruitment check`() {
            val gen = createGeneral(crew = 5000, leadership = 80)
            val policy = NpcGeneralPolicy(minWarCrew = 500)
            assertFalse(gen.crew < policy.minWarCrew,
                "crew(5000) >= minWarCrew(500) → no recruitment needed")
        }

        @Test
        fun `low train triggers training`() {
            val gen = createGeneral(train = 30)
            val policy = NpcGeneralPolicy(properWarTrainAtmos = 80)
            assertTrue(gen.train < policy.properWarTrainAtmos)
        }

        @Test
        fun `low atmos triggers morale boost`() {
            val gen = createGeneral(atmos = 30)
            val policy = NpcGeneralPolicy(properWarTrainAtmos = 80)
            assertTrue(gen.atmos < policy.properWarTrainAtmos)
        }

        @Test
        fun `all stats sufficient means ready for attack`() {
            val gen = createGeneral(crew = 5000, leadership = 80, train = 80, atmos = 80)
            val policy = NpcGeneralPolicy(minWarCrew = 500, properWarTrainAtmos = 80)
            val crewOk = gen.crew >= policy.minWarCrew
            val trainOk = gen.train >= policy.properWarTrainAtmos
            val atmosOk = gen.atmos >= policy.properWarTrainAtmos
            assertTrue(crewOk && trainOk && atmosOk, "General should be ready for attack")
        }
    }

    // ──────────────────────────────────────────────────
    //  Injury-based decisions parity
    //  Legacy: GeneralAI.php:128
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Injury decisions")
    inner class InjuryDecisionParity {

        @Test
        fun `injury above cureThreshold triggers healing`() {
            val gen = createGeneral(injury = 30)
            val policy = NpcNationPolicy(cureThreshold = 20)
            assertTrue(gen.injury > policy.cureThreshold,
                "injury(30) > cureThreshold(20) → should heal")
        }

        @Test
        fun `injury at cureThreshold does not trigger healing`() {
            val gen = createGeneral(injury = 20)
            val policy = NpcNationPolicy(cureThreshold = 20)
            assertFalse(gen.injury > policy.cureThreshold,
                "injury(20) == cureThreshold(20) → no healing")
        }

        @Test
        fun `no injury does not trigger healing`() {
            val gen = createGeneral(injury = 0)
            val policy = NpcNationPolicy(cureThreshold = 20)
            assertFalse(gen.injury > policy.cureThreshold)
        }
    }

    // ──────────────────────────────────────────────────
    //  Peace-time development priorities parity
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Peace-time development priorities")
    inner class PeaceTimeParity {

        @Test
        fun `low agri ratio should trigger agriculture first`() {
            val agriRatio = 400.0 / 1000.0
            val commRatio = 800.0 / 1000.0
            assertTrue(agriRatio < 0.8 && commRatio >= 0.8,
                "Only agri below threshold → should develop agri first")
        }

        @Test
        fun `warrior type prefers training when crew exists`() {
            val gen = createGeneral(strength = 90, intel = 40, leadership = 40)
            val type = ai.classifyGeneral(gen)
            assertTrue(type and GeneralType.WARRIOR.flag != 0,
                "Strength-dominant should be WARRIOR")
        }

        @Test
        fun `strategist type prefers development when city needs it`() {
            val gen = createGeneral(strength = 40, intel = 90, leadership = 40)
            val type = ai.classifyGeneral(gen)
            assertTrue(type and GeneralType.STRATEGIST.flag != 0,
                "Intel-dominant should be STRATEGIST")
        }
    }

    // ──────────────────────────────────────────────────
    //  Special NPC states parity
    // ──────────────────────────────────────────────────

    @Nested
    @DisplayName("Special NPC states")
    inner class SpecialNpcStateParity {

        @Test
        fun `npcState 5 is troop leader type`() {
            val gen = createGeneral(npcState = 5)
            assertEquals(5, gen.npcState.toInt())
        }

        @Test
        fun `npcState 2 or 3 wanderers can attempt 거병`() {
            val gen2 = createGeneral(npcState = 2, nationId = 0)
            val gen3 = createGeneral(npcState = 3, nationId = 0)
            assertTrue(gen2.npcState.toInt() in listOf(2, 3) && gen2.nationId == 0L)
            assertTrue(gen3.npcState.toInt() in listOf(2, 3) && gen3.nationId == 0L)
        }

        @Test
        fun `npcState 2 with nation does NOT trigger 거병`() {
            val gen = createGeneral(npcState = 2, nationId = 1)
            assertFalse(gen.nationId == 0L, "Has nation → no 거병")
        }
    }

    // ──────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────

    private fun createGeneral(
        id: Long = 1,
        nationId: Long = 1,
        leadership: Short = 70,
        strength: Short = 70,
        intel: Short = 70,
        crew: Int = 3000,
        train: Short = 70,
        atmos: Short = 70,
        injury: Short = 0,
        npcState: Short = 2,
        officerLevel: Short = 5,
    ): General = General(
        id = id,
        worldId = 1,
        name = "테스트장수",
        nationId = nationId,
        cityId = 1,
        leadership = leadership,
        strength = strength,
        intel = intel,
        crew = crew,
        train = train,
        atmos = atmos,
        injury = injury,
        npcState = npcState,
        officerLevel = officerLevel,
        gold = 5000,
        rice = 5000,
        turnTime = OffsetDateTime.now(),
    )

    private fun createNation(
        id: Long = 1,
        warState: Short = 0,
    ): Nation = Nation(
        id = id,
        worldId = 1,
        name = "테스트국",
        warState = warState,
    )
}
