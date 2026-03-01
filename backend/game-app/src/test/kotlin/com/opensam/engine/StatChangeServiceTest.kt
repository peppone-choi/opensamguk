package com.opensam.engine

import com.opensam.entity.General
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime

class StatChangeServiceTest {

    private lateinit var service: StatChangeService

    @BeforeEach
    fun setUp() {
        service = StatChangeService()
    }

    private fun createGeneral(
        leadership: Short = 50,
        leadershipExp: Short = 0,
        strength: Short = 50,
        strengthExp: Short = 0,
        intel: Short = 50,
        intelExp: Short = 0,
    ): General {
        return General(
            id = 1,
            worldId = 1,
            name = "테스트",
            nationId = 1,
            cityId = 1,
            leadership = leadership,
            leadershipExp = leadershipExp,
            strength = strength,
            strengthExp = strengthExp,
            intel = intel,
            intelExp = intelExp,
            turnTime = OffsetDateTime.now(),
        )
    }

    @Test
    fun `stat increases by 1 when exp reaches upgrade limit`() {
        val general = createGeneral(leadership = 50, leadershipExp = 30)

        val result = service.checkStatChange(general)

        assertTrue(result.hasChanges)
        assertEquals(51.toShort(), general.leadership)
        assertEquals(0.toShort(), general.leadershipExp, "Exp should be consumed")
        assertEquals(1, result.changes.size)
        assertEquals(+1, result.changes[0].delta)
    }

    @Test
    fun `stat decreases by 1 when exp is negative`() {
        val general = createGeneral(strength = 50, strengthExp = (-5).toShort())

        val result = service.checkStatChange(general)

        assertTrue(result.hasChanges)
        assertEquals(49.toShort(), general.strength)
        // Exp should be adjusted: -5 + 30 = 25
        assertEquals(25.toShort(), general.strengthExp)
        assertEquals(-1, result.changes[0].delta)
    }

    @Test
    fun `no change when exp is between 0 and upgrade limit`() {
        val general = createGeneral(leadershipExp = 15, strengthExp = 0, intelExp = 29)

        val result = service.checkStatChange(general)

        assertFalse(result.hasChanges)
        assertEquals(50.toShort(), general.leadership)
        assertEquals(50.toShort(), general.strength)
        assertEquals(50.toShort(), general.intel)
    }

    @Test
    fun `stat does not increase above MAX_LEVEL`() {
        val general = createGeneral(intel = 255, intelExp = 30)

        val result = service.checkStatChange(general)

        assertFalse(result.hasChanges, "No change entry when already at max")
        assertEquals(255.toShort(), general.intel)
        // Exp should still be consumed
        assertEquals(0.toShort(), general.intelExp)
    }

    @Test
    fun `stat does not decrease below 0`() {
        val general = createGeneral(leadership = 0, leadershipExp = (-5).toShort())

        val result = service.checkStatChange(general)

        assertFalse(result.hasChanges, "No change when stat already 0")
        assertEquals(0.toShort(), general.leadership)
        // Exp adjusted: -5 + 30 = 25
        assertEquals(25.toShort(), general.leadershipExp)
    }
}
