package com.opensam.engine.war

import com.opensam.entity.General
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.random.Random

class BattleTriggerTest {

    private fun createGeneral(
        id: Long = 1,
        leadership: Short = 50,
        strength: Short = 50,
        intel: Short = 50,
        crew: Int = 1000,
    ): General {
        return General(
            id = id,
            worldId = 1,
            name = "장수$id",
            nationId = 1,
            cityId = 1,
            leadership = leadership,
            strength = strength,
            intel = intel,
            crew = crew,
            turnTime = OffsetDateTime.now(),
        )
    }

    // ========== 필살 trigger ==========

    @Test
    fun `필살 trigger increases critical chance by 30 percent`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        val ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
        )

        val result = 필살Trigger.onPreCritical(ctx)

        assertEquals(0.30, result.criticalChanceBonus, 0.01)
    }

    @Test
    fun `필살 trigger disables dodge`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        val ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
        )

        val result = 필살Trigger.onPreCritical(ctx)

        assertTrue(result.dodgeDisabled)
    }

    // ========== 회피 trigger ==========

    @Test
    fun `회피 trigger increases dodge chance by 8 percent`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        val ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
        )

        val result = 회피Trigger.onPreDodge(ctx)

        assertEquals(0.08, result.dodgeChanceBonus, 0.01)
    }

    // ========== 반계 trigger ==========

    @Test
    fun `반계 trigger reflects magic damage when magic is activated`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        val ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
            magicActivated = true,
        )

        val result = 반계Trigger.onPostMagic(ctx)

        assertTrue(result.magicReflected)
    }

    @Test
    fun `반계 trigger does not reflect when magic is not activated`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        val ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
            magicActivated = false,
        )

        val result = 반계Trigger.onPostMagic(ctx)

        assertFalse(result.magicReflected)
    }

    // ========== 신산 trigger ==========

    @Test
    fun `신산 trigger increases magic chance by 20 percent`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        val ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
        )

        val result = 신산Trigger.onPreMagic(ctx)

        assertEquals(0.20, result.magicChanceBonus, 0.01)
    }

    // ========== 위압 trigger ==========

    @Test
    fun `위압 trigger multiplies attack by 1_05`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        val ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
        )

        val result = 위압Trigger.onDamageCalc(ctx)

        assertEquals(1.05, result.attackMultiplier, 0.01)
    }

    // ========== 저격 trigger ==========

    @Test
    fun `저격 trigger increases critical chance by 8 percent`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        val ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
        )

        val result = 저격Trigger.onPreCritical(ctx)

        assertEquals(0.08, result.criticalChanceBonus, 0.01)
    }

    // ========== 격노 trigger ==========

    @Test
    fun `격노 trigger multiplies attack by 1_2`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        val ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
        )

        val result = 격노Trigger.onDamageCalc(ctx)

        assertEquals(1.2, result.attackMultiplier, 0.01)
    }

    // ========== 돌격 trigger ==========

    @Test
    fun `돌격 trigger multiplies attack by 1_15`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        val ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
        )

        val result = 돌격Trigger.onDamageCalc(ctx)

        assertEquals(1.15, result.attackMultiplier, 0.01)
    }

    // ========== Trigger priority and chaining ==========

    @Test
    fun `multiple triggers can chain by accumulating bonuses`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        var ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
        )

        // Apply 필살 (crit +30%)
        ctx = 필살Trigger.onPreCritical(ctx)
        // Apply 저격 (crit +8%)
        ctx = 저격Trigger.onPreCritical(ctx)

        // Total critical bonus should be 38%
        assertEquals(0.38, ctx.criticalChanceBonus, 0.01)
    }

    @Test
    fun `multiple attack multipliers stack multiplicatively`() {
        val attacker = createGeneral()
        val defender = createGeneral()
        val rng = Random(42)

        var ctx = BattleTriggerContext(
            attacker = WarUnitGeneral(attacker),
            defender = WarUnitGeneral(defender),
            rng = rng,
        )

        // Apply 격노 (×1.2)
        ctx = 격노Trigger.onDamageCalc(ctx)
        // Apply 위압 (×1.05)
        ctx = 위압Trigger.onDamageCalc(ctx)

        // Total multiplier should be 1.2 × 1.05 = 1.26
        assertEquals(1.26, ctx.attackMultiplier, 0.01)
    }

    // ========== BattleTriggerRegistry ==========

    @Test
    fun `registry can retrieve trigger by code`() {
        val trigger = BattleTriggerRegistry.get("필살")

        assertNotNull(trigger)
        assertEquals("필살", trigger?.code)
    }

    @Test
    fun `registry returns null for unknown trigger code`() {
        val trigger = BattleTriggerRegistry.get("unknown")

        assertNull(trigger)
    }

    @Test
    fun `registry contains all expected triggers`() {
        val expectedCodes = listOf("필살", "회피", "반계", "신산", "위압", "저격", "격노", "돌격")

        for (code in expectedCodes) {
            assertNotNull(BattleTriggerRegistry.get(code), "Registry should contain $code")
        }
    }

    // ========== Trigger priority ordering ==========

    @Test
    fun `triggers have priority values for execution order`() {
        assertEquals(10, 필살Trigger.priority)
        assertEquals(15, 저격Trigger.priority)
        assertEquals(20, 위압Trigger.priority)
    }
}
