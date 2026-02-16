package com.opensam.engine

import com.opensam.entity.General
import com.opensam.entity.WorldState
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class GeneralMaintenanceServiceTest {

    private lateinit var service: GeneralMaintenanceService

    @BeforeEach
    fun setUp() {
        service = GeneralMaintenanceService()
    }

    private fun createWorld(year: Short = 200, month: Short = 1): WorldState {
        return WorldState(
            id = 1,
            scenarioCode = "test",
            currentYear = year,
            currentMonth = month,
            tickSeconds = 300,
        )
    }

    private fun createGeneral(
        age: Short = 30,
        deadYear: Short = 60,
        experience: Int = 1000,
        dedication: Int = 1000,
        blockState: Short = 0,
        killTurn: Short? = null,
        npcState: Short = 0,
    ): General {
        return General(
            id = 1,
            worldId = 1,
            name = "테스트",
            nationId = 1,
            cityId = 1,
            age = age,
            deadYear = deadYear,
            experience = experience,
            dedication = dedication,
            blockState = blockState,
            killTurn = killTurn,
            npcState = npcState,
            turnTime = OffsetDateTime.now(),
        )
    }

    @Test
    fun `age increments in January`() {
        val world = createWorld(month = 1)
        val general = createGeneral(age = 30)

        service.processGeneralMaintenance(world, listOf(general))

        assertEquals(31.toShort(), general.age)
    }

    @Test
    fun `age does not increment in non-January month`() {
        val world = createWorld(month = 6)
        val general = createGeneral(age = 30)

        service.processGeneralMaintenance(world, listOf(general))

        assertEquals(30.toShort(), general.age)
    }

    @Test
    fun `experience increases by 10 per turn`() {
        val world = createWorld(month = 3)
        val general = createGeneral(experience = 1000)

        service.processGeneralMaintenance(world, listOf(general))

        assertEquals(1010, general.experience)
    }

    @Test
    fun `dedication decays by 1 percent`() {
        val world = createWorld(month = 3)
        val general = createGeneral(dedication = 1000)

        service.processGeneralMaintenance(world, listOf(general))

        // 1000 * 0.99 = 990
        assertEquals(990, general.dedication)
    }

    @Test
    fun `dedication does not decay below zero`() {
        val world = createWorld(month = 3)
        val general = createGeneral(dedication = 0)

        service.processGeneralMaintenance(world, listOf(general))

        assertEquals(0, general.dedication)
    }

    @Test
    fun `retirement triggers when currentYear reaches deadYear`() {
        val world = createWorld(year = 260, month = 1)
        val general = createGeneral(age = 59, deadYear = 260)

        service.processGeneralMaintenance(world, listOf(general))

        assertEquals((-1).toShort(), general.npcState)
    }

    @Test
    fun `no retirement when currentYear is below deadYear`() {
        val world = createWorld(year = 199, month = 1)
        val general = createGeneral(age = 40, deadYear = 200)

        service.processGeneralMaintenance(world, listOf(general))

        assertNotEquals((-1).toShort(), general.npcState)
    }

    @Test
    fun `injury recovers by 1 per turn`() {
        val world = createWorld(month = 3)
        val general = createGeneral(age = 30, deadYear = 300)
        general.injury = 5

        service.processGeneralMaintenance(world, listOf(general))

        assertEquals(4.toShort(), general.injury)
    }

    @Test
    fun `injury does not go below zero`() {
        val world = createWorld(month = 3)
        val general = createGeneral(age = 30, deadYear = 300)
        general.injury = 0

        service.processGeneralMaintenance(world, listOf(general))

        assertEquals(0.toShort(), general.injury)
    }

    @Test
    fun `multiple generals processed independently`() {
        val world = createWorld(month = 1)
        val g1 = createGeneral(age = 25, experience = 100, dedication = 500)
        val g2 = createGeneral(age = 50, experience = 200, dedication = 1000)

        service.processGeneralMaintenance(world, listOf(g1, g2))

        // g1
        assertEquals(26.toShort(), g1.age)
        assertEquals(110, g1.experience)
        assertEquals(495, g1.dedication)

        // g2
        assertEquals(51.toShort(), g2.age)
        assertEquals(210, g2.experience)
        assertEquals(990, g2.dedication)
    }
}
