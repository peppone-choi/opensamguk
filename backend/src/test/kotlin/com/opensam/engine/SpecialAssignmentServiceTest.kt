package com.opensam.engine

import com.opensam.entity.General
import com.opensam.entity.WorldState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.random.Random

class SpecialAssignmentServiceTest {

    private lateinit var service: SpecialAssignmentService

    @BeforeEach
    fun setUp() {
        service = SpecialAssignmentService()
    }

    private fun createWorld(year: Short = 200, month: Short = 3, startYear: Int = 184): WorldState {
        return WorldState(
            id = 1,
            scenarioCode = "test",
            currentYear = year,
            currentMonth = month,
            tickSeconds = 300,
            config = mutableMapOf("startYear" to startYear),
        )
    }

    private fun createGeneral(
        id: Long = 1,
        age: Short = 25,
        leadership: Short = 70,
        strength: Short = 70,
        intel: Short = 70,
        specialCode: String = "None",
        special2Code: String = "None",
        specAge: Short = 20,
        spec2Age: Short = 20,
        meta: MutableMap<String, Any> = mutableMapOf(),
    ): General {
        return General(
            id = id,
            worldId = 1,
            name = "장수$id",
            nationId = 1,
            cityId = 1,
            age = age,
            leadership = leadership,
            strength = strength,
            intel = intel,
            specialCode = specialCode,
            special2Code = special2Code,
            specAge = specAge,
            spec2Age = spec2Age,
            meta = meta,
            turnTime = OffsetDateTime.now(),
        )
    }

    // ========== checkAndAssignSpecials: skip first 3 years ==========

    @Test
    fun `checkAndAssignSpecials skips assignment in first 3 years`() {
        val world = createWorld(year = 185, startYear = 184)
        val general = createGeneral(age = 30, specAge = 20)

        service.checkAndAssignSpecials(world, listOf(general))

        assertEquals("None", general.specialCode, "Should not assign special in first 3 years")
    }

    @Test
    fun `checkAndAssignSpecials proceeds after 3 years`() {
        val world = createWorld(year = 187, startYear = 184)
        val general = createGeneral(age = 30, specAge = 25, leadership = 80, intel = 80)

        service.checkAndAssignSpecials(world, listOf(general))

        assertNotEquals("None", general.specialCode, "Should assign special after 3 years")
    }

    // ========== checkAndAssignSpecials: domestic special assignment ==========

    @Test
    fun `checkAndAssignSpecials assigns domestic special when age reaches specAge`() {
        val world = createWorld(year = 200, startYear = 184)
        val general = createGeneral(
            age = 30,
            specAge = 25,
            leadership = 80,
            intel = 80,
            strength = 50,
        )

        service.checkAndAssignSpecials(world, listOf(general))

        assertNotEquals("None", general.specialCode, "Should assign domestic special")
        assertTrue(general.specAge > 30, "New specAge should be set for next assignment")
    }

    @Test
    fun `checkAndAssignSpecials does not assign domestic special when age is below specAge`() {
        val world = createWorld(year = 200, startYear = 184)
        val general = createGeneral(
            age = 20,
            specAge = 30,
            leadership = 80,
            intel = 80,
        )

        service.checkAndAssignSpecials(world, listOf(general))

        assertEquals("None", general.specialCode, "Should not assign special when age < specAge")
    }

    @Test
    fun `checkAndAssignSpecials skips generals who already have domestic special`() {
        val world = createWorld(year = 200, startYear = 184)
        val general = createGeneral(
            age = 40,
            specAge = 30,
            specialCode = "농업",
            leadership = 80,
            intel = 80,
        )

        service.checkAndAssignSpecials(world, listOf(general))

        assertEquals("농업", general.specialCode, "Should not replace existing special")
    }

    // ========== checkAndAssignSpecials: war special assignment ==========

    @Test
    fun `checkAndAssignSpecials assigns war special when age reaches spec2Age`() {
        val world = createWorld(year = 200, startYear = 184)
        val general = createGeneral(
            age = 30,
            spec2Age = 25,
            strength = 80,
            leadership = 70,
            intel = 50,
        )

        service.checkAndAssignSpecials(world, listOf(general))

        assertNotEquals("None", general.special2Code, "Should assign war special")
        assertTrue(general.spec2Age > 30, "New spec2Age should be set for next assignment")
    }

    @Test
    fun `checkAndAssignSpecials does not assign war special when age is below spec2Age`() {
        val world = createWorld(year = 200, startYear = 184)
        val general = createGeneral(
            age = 20,
            spec2Age = 30,
            strength = 80,
        )

        service.checkAndAssignSpecials(world, listOf(general))

        assertEquals("None", general.special2Code, "Should not assign war special when age < spec2Age")
    }

    @Test
    fun `checkAndAssignSpecials uses inherited war special when designated`() {
        val world = createWorld(year = 200, startYear = 184)
        val general = createGeneral(
            age = 30,
            spec2Age = 25,
            meta = mutableMapOf("inheritSpecificSpecialWar" to "필살"),
        )

        service.checkAndAssignSpecials(world, listOf(general))

        assertEquals("필살", general.special2Code, "Should use inherited war special")
        assertFalse(general.meta.containsKey("inheritSpecificSpecialWar"), "Should remove meta key")
    }

    // ========== calcStatCondition ==========

    @Test
    fun `calcStatCondition sets STAT_LEADERSHIP for high leadership`() {
        val method = SpecialAssignmentService::class.java.getDeclaredMethod(
            "calcStatCondition",
            General::class.java
        )
        method.isAccessible = true

        val general = createGeneral(leadership = 80, strength = 50, intel = 50)
        val result = method.invoke(service, general) as Int

        // STAT_LEADERSHIP = 0x2
        assertTrue((result and 0x2) != 0, "Should set STAT_LEADERSHIP bit")
    }

    @Test
    fun `calcStatCondition sets STAT_STRENGTH for high strength`() {
        val method = SpecialAssignmentService::class.java.getDeclaredMethod(
            "calcStatCondition",
            General::class.java
        )
        method.isAccessible = true

        val general = createGeneral(leadership = 50, strength = 80, intel = 50)
        val result = method.invoke(service, general) as Int

        // STAT_STRENGTH = 0x4
        assertTrue((result and 0x4) != 0, "Should set STAT_STRENGTH bit")
    }

    @Test
    fun `calcStatCondition sets STAT_INTEL for high intel`() {
        val method = SpecialAssignmentService::class.java.getDeclaredMethod(
            "calcStatCondition",
            General::class.java
        )
        method.isAccessible = true

        val general = createGeneral(leadership = 50, strength = 50, intel = 80)
        val result = method.invoke(service, general) as Int

        // STAT_INTEL = 0x8
        assertTrue((result and 0x8) != 0, "Should set STAT_INTEL bit")
    }

    @Test
    fun `calcStatCondition sets NOT flags for low stats when another stat is high`() {
        val method = SpecialAssignmentService::class.java.getDeclaredMethod(
            "calcStatCondition",
            General::class.java
        )
        method.isAccessible = true

        val general = createGeneral(leadership = 30, strength = 30, intel = 80)
        val result = method.invoke(service, general) as Int

        // Should have STAT_INTEL (0x8) and STAT_NOT_LEADERSHIP (0x20000) and STAT_NOT_STRENGTH (0x40000)
        assertTrue((result and 0x8) != 0, "Should set STAT_INTEL")
        assertTrue((result and 0x20000) != 0, "Should set STAT_NOT_LEADERSHIP")
        assertTrue((result and 0x40000) != 0, "Should set STAT_NOT_STRENGTH")
    }

    // ========== calcDomesticSpecAge formula ==========

    @Test
    fun `calcDomesticSpecAge uses formula max of round 80 minus age div 12 minus relYear div 2 and 3 plus age`() {
        val method = SpecialAssignmentService::class.java.getDeclaredMethod(
            "calcDomesticSpecAge",
            Int::class.java, Int::class.java
        )
        method.isAccessible = true

        val result = method.invoke(service, 30, 10) as Short

        // wait = max(round((80-30)/12 - 10/2), 3) = max(round(50/12 - 5), 3) = max(round(4.17 - 5), 3) = max(-1, 3) = 3
        // result = 3 + 30 = 33
        assertEquals(33.toShort(), result)
    }

    @Test
    fun `calcDomesticSpecAge minimum wait is 3`() {
        val method = SpecialAssignmentService::class.java.getDeclaredMethod(
            "calcDomesticSpecAge",
            Int::class.java, Int::class.java
        )
        method.isAccessible = true

        val result = method.invoke(service, 70, 50) as Short

        // wait = max(round((80-70)/12 - 50/2), 3) = max(round(0.83 - 25), 3) = max(-24, 3) = 3
        // result = 3 + 70 = 73
        assertEquals(73.toShort(), result)
    }

    // ========== calcWarSpecAge formula ==========

    @Test
    fun `calcWarSpecAge uses formula max of round 80 minus age div 6 minus relYear div 2 and 3 plus age`() {
        val method = SpecialAssignmentService::class.java.getDeclaredMethod(
            "calcWarSpecAge",
            Int::class.java, Int::class.java
        )
        method.isAccessible = true

        val result = method.invoke(service, 30, 10) as Short

        // wait = max(round((80-30)/6 - 10/2), 3) = max(round(50/6 - 5), 3) = max(round(8.33 - 5), 3) = max(3, 3) = 3
        // result = 3 + 30 = 33
        assertEquals(33.toShort(), result)
    }

    @Test
    fun `calcWarSpecAge minimum wait is 3`() {
        val method = SpecialAssignmentService::class.java.getDeclaredMethod(
            "calcWarSpecAge",
            Int::class.java, Int::class.java
        )
        method.isAccessible = true

        val result = method.invoke(service, 70, 50) as Short

        // wait = max(round((80-70)/6 - 50/2), 3) = max(round(1.67 - 25), 3) = max(-23, 3) = 3
        // result = 3 + 70 = 73
        assertEquals(73.toShort(), result)
    }

    // ========== Multiple generals ==========

    @Test
    fun `checkAndAssignSpecials processes multiple generals`() {
        val world = createWorld(year = 200, startYear = 184)
        val g1 = createGeneral(id = 1, age = 30, specAge = 25, leadership = 80, intel = 80)
        val g2 = createGeneral(id = 2, age = 35, spec2Age = 30, strength = 90)
        val g3 = createGeneral(id = 3, age = 20, specAge = 30)

        service.checkAndAssignSpecials(world, listOf(g1, g2, g3))

        assertNotEquals("None", g1.specialCode, "G1 should get domestic special")
        assertNotEquals("None", g2.special2Code, "G2 should get war special")
        assertEquals("None", g3.specialCode, "G3 is too young")
    }
}
