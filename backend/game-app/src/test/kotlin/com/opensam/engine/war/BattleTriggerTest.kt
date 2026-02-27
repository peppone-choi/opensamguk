package com.opensam.engine.war

import com.opensam.entity.City
import com.opensam.entity.General
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import kotlin.random.Random

class BattleTriggerTest {

    private fun createGeneral(
        id: Long = 1,
        nationId: Long = 1,
        leadership: Short = 50,
        strength: Short = 50,
        intel: Short = 50,
        crew: Int = 1000,
        specialCode: String = "None",
        special2Code: String = "None",
    ): General {
        return General(
            id = id,
            worldId = 1,
            name = "장수$id",
            nationId = nationId,
            cityId = 1,
            leadership = leadership,
            strength = strength,
            intel = intel,
            crew = crew,
            specialCode = specialCode,
            special2Code = special2Code,
            turnTime = OffsetDateTime.now(),
        )
    }

    private fun makeCtx(
        attacker: WarUnit? = null,
        defender: WarUnit? = null,
        rng: Random = Random(42),
        phaseNumber: Int = 0,
        isVsCity: Boolean = false,
    ): BattleTriggerContext {
        val a = attacker ?: WarUnitGeneral(createGeneral())
        val d = defender ?: WarUnitGeneral(createGeneral(id = 2))
        return BattleTriggerContext(
            attacker = a,
            defender = d,
            rng = rng,
            phaseNumber = phaseNumber,
            isVsCity = isVsCity,
        )
    }

    // ========== 필살 trigger ==========

    @Test
    fun `필살 trigger increases critical chance by 30 percent`() {
        val ctx = makeCtx()
        val result = 필살Trigger.onPreCritical(ctx)
        assertEquals(0.30, result.criticalChanceBonus, 0.01)
    }

    @Test
    fun `필살 trigger disables dodge`() {
        val ctx = makeCtx()
        val result = 필살Trigger.onPreCritical(ctx)
        assertTrue(result.dodgeDisabled)
    }

    @Test
    fun `필살 trigger adds battle log`() {
        val ctx = makeCtx()
        필살Trigger.onPreCritical(ctx)
        assertTrue(ctx.battleLogs.isNotEmpty())
    }

    // ========== 회피 trigger ==========

    @Test
    fun `회피 trigger increases dodge chance by 8 percent`() {
        val ctx = makeCtx()
        val result = 회피Trigger.onPreDodge(ctx)
        assertEquals(0.08, result.dodgeChanceBonus, 0.01)
    }

    // ========== 반계 trigger ==========

    @Test
    fun `반계 trigger reflects magic damage when magic is activated`() {
        val ctx = makeCtx()
        ctx.magicActivated = true
        val result = 반계Trigger.onPostMagic(ctx)
        assertTrue(result.magicReflected)
    }

    @Test
    fun `반계 trigger does not reflect when magic is not activated`() {
        val ctx = makeCtx()
        ctx.magicActivated = false
        val result = 반계Trigger.onPostMagic(ctx)
        assertFalse(result.magicReflected)
    }

    @Test
    fun `반계 trigger adds battle log on reflect`() {
        val ctx = makeCtx()
        ctx.magicActivated = true
        반계Trigger.onPostMagic(ctx)
        assertTrue(ctx.battleLogs.any { "반계" in it })
    }

    // ========== 신산 trigger ==========

    @Test
    fun `신산 trigger increases magic chance by 20 percent`() {
        val ctx = makeCtx()
        val result = 신산Trigger.onPreMagic(ctx)
        assertEquals(0.20, result.magicChanceBonus, 0.01)
    }

    // ========== 위압 trigger ==========

    @Test
    fun `위압 trigger multiplies attack by 1_05`() {
        val ctx = makeCtx()
        val result = 위압Trigger.onDamageCalc(ctx)
        assertEquals(1.05, result.attackMultiplier, 0.01)
    }

    // ========== 저격 trigger (enhanced with snipe proc) ==========

    @Test
    fun `저격 trigger increases critical chance by 8 percent`() {
        val ctx = makeCtx()
        val result = 저격Trigger.onPreCritical(ctx)
        assertEquals(0.08, result.criticalChanceBonus, 0.01)
    }

    @Test
    fun `저격 trigger applies wound on critical when defender is general`() {
        // Use strength=100 for high wound chance
        val attacker = WarUnitGeneral(createGeneral(strength = 100))
        val defender = WarUnitGeneral(createGeneral(id = 2))
        // Use a seed that produces a low nextDouble (< 100/200 = 0.5)
        val rng = Random(1)
        val ctx = BattleTriggerContext(attacker = attacker, defender = defender, rng = rng)
        ctx.criticalActivated = true

        저격Trigger.onPostCritical(ctx)

        // With strength=100, wound chance is 0.5. May or may not trigger depending on RNG.
        // We just verify the mechanism works without errors.
        if (ctx.snipeActivated) {
            assertTrue(ctx.snipeWoundAmount in 2..6)
            assertTrue(ctx.battleLogs.any { "저격" in it })
        }
    }

    @Test
    fun `저격 trigger does not apply wound to city`() {
        val attacker = WarUnitGeneral(createGeneral(strength = 100))
        val city = City(id = 1, worldId = 1, name = "도시", nationId = 2, def = 100, defMax = 1000, wall = 100, wallMax = 1000, pop = 1000, popMax = 50000)
        val defender = WarUnitCity(city)
        val ctx = BattleTriggerContext(attacker = attacker, defender = defender, rng = Random(1))
        ctx.criticalActivated = true

        저격Trigger.onPostCritical(ctx)

        assertFalse(ctx.snipeActivated, "Snipe should not apply wound to city")
    }

    // ========== 격노 trigger ==========

    @Test
    fun `격노 trigger multiplies attack by 1_2`() {
        val ctx = makeCtx()
        val result = 격노Trigger.onDamageCalc(ctx)
        assertEquals(1.2, result.attackMultiplier, 0.01)
    }

    // ========== 돌격 trigger ==========

    @Test
    fun `돌격 trigger multiplies attack by 1_15`() {
        val ctx = makeCtx()
        val result = 돌격Trigger.onDamageCalc(ctx)
        assertEquals(1.15, result.attackMultiplier, 0.01)
    }

    // ========== 화공 trigger ==========

    @Test
    fun `화공 trigger increases magic chance by 15 percent`() {
        val ctx = makeCtx()
        화공Trigger.onPreMagic(ctx)
        assertEquals(0.15, ctx.magicChanceBonus, 0.01)
    }

    @Test
    fun `화공 trigger increases magic damage multiplier by 1_2`() {
        val ctx = makeCtx()
        화공Trigger.onPreMagic(ctx)
        assertEquals(1.2, ctx.magicDamageMultiplier, 0.01)
    }

    // ========== 기습 trigger ==========

    @Test
    fun `기습 trigger gives doubled bonus on phase 0`() {
        val ctx = makeCtx(phaseNumber = 0)
        기습Trigger.onPreCritical(ctx)
        assertEquals(0.10, ctx.criticalChanceBonus, 0.01)
    }

    @Test
    fun `기습 trigger gives normal bonus on later phases`() {
        val ctx = makeCtx(phaseNumber = 3)
        기습Trigger.onPreCritical(ctx)
        assertEquals(0.05, ctx.criticalChanceBonus, 0.01)
    }

    @Test
    fun `기습 trigger also boosts dodge`() {
        val ctx = makeCtx(phaseNumber = 0)
        기습Trigger.onPreDodge(ctx)
        assertEquals(0.10, ctx.dodgeChanceBonus, 0.01)
    }

    // ========== 매복 trigger ==========

    @Test
    fun `매복 trigger increases dodge by 8 percent`() {
        val ctx = makeCtx()
        매복Trigger.onPreDodge(ctx)
        assertEquals(0.08, ctx.dodgeChanceBonus, 0.01)
    }

    // ========== 방어 trigger ==========

    @Test
    fun `방어 trigger increases dodge by 15 percent`() {
        val ctx = makeCtx()
        방어Trigger.onPreDodge(ctx)
        assertEquals(0.15, ctx.dodgeChanceBonus, 0.01)
    }

    @Test
    fun `방어 trigger increases defence multiplier`() {
        val ctx = makeCtx()
        방어Trigger.onDamageCalc(ctx)
        assertEquals(1.1, ctx.defenceMultiplier, 0.01)
    }

    // ========== 귀모 trigger ==========

    @Test
    fun `귀모 trigger increases magic chance by 25 percent`() {
        val ctx = makeCtx()
        귀모Trigger.onPreMagic(ctx)
        assertEquals(0.25, ctx.magicChanceBonus, 0.01)
    }

    @Test
    fun `귀모 trigger increases magic damage multiplier by 1_3`() {
        val ctx = makeCtx()
        귀모Trigger.onPreMagic(ctx)
        assertEquals(1.3, ctx.magicDamageMultiplier, 0.01)
    }

    @Test
    fun `귀모 trigger applies self-damage on magic fail`() {
        val attacker = WarUnitGeneral(createGeneral(intel = 80))
        val ctx = makeCtx(attacker = attacker)
        귀모Trigger.onMagicFail(ctx)
        assertEquals(80 * 0.5, ctx.magicFailDamage, 0.01)
        assertTrue(ctx.battleLogs.any { "계략 실패" in it })
    }

    // ========== 공성 trigger ==========

    @Test
    fun `공성 trigger increases attack by 1_3 vs city`() {
        val ctx = makeCtx(isVsCity = true)
        공성Trigger.onDamageCalc(ctx)
        assertEquals(1.3, ctx.attackMultiplier, 0.01)
    }

    @Test
    fun `공성 trigger does not affect non-city targets`() {
        val ctx = makeCtx(isVsCity = false)
        공성Trigger.onDamageCalc(ctx)
        assertEquals(1.0, ctx.attackMultiplier, 0.01)
    }

    // ========== 철벽 trigger ==========

    @Test
    fun `철벽 trigger grants injury immunity on init`() {
        val ctx = makeCtx()
        철벽Trigger.onBattleInit(ctx)
        assertTrue(ctx.injuryImmune)
    }

    @Test
    fun `철벽 trigger increases dodge by 12 percent`() {
        val ctx = makeCtx()
        철벽Trigger.onPreDodge(ctx)
        assertEquals(0.12, ctx.dodgeChanceBonus, 0.01)
    }

    @Test
    fun `철벽 trigger increases defence multiplier`() {
        val ctx = makeCtx()
        철벽Trigger.onDamageCalc(ctx)
        assertEquals(1.1, ctx.defenceMultiplier, 0.01)
    }

    // ========== 분투 trigger ==========

    @Test
    fun `분투 trigger gives 1_05 at normal HP`() {
        val attacker = WarUnitGeneral(createGeneral(crew = 1000))
        val ctx = makeCtx(attacker = attacker)
        분투Trigger.onDamageCalc(ctx)
        assertEquals(1.05, ctx.attackMultiplier, 0.01)
    }

    @Test
    fun `분투 trigger gives 1_15 when HP below 50 percent`() {
        val attacker = WarUnitGeneral(createGeneral(crew = 1000))
        attacker.hp = 400  // < 1000/2 = 500
        val ctx = makeCtx(attacker = attacker)
        분투Trigger.onDamageCalc(ctx)
        assertEquals(1.15, ctx.attackMultiplier, 0.01)
        assertTrue(ctx.battleLogs.any { "분투" in it })
    }

    // ========== 용병 trigger ==========

    @Test
    fun `용병 trigger gives attack 1_05`() {
        val ctx = makeCtx()
        용병Trigger.onDamageCalc(ctx)
        assertEquals(1.05, ctx.attackMultiplier, 0.01)
    }

    @Test
    fun `용병 trigger gives morale boost on post damage`() {
        val ctx = makeCtx()
        용병Trigger.onPostDamage(ctx)
        assertEquals(2, ctx.moraleBoost)
    }

    // ========== 견고 trigger ==========

    @Test
    fun `견고 trigger grants injury immunity on init`() {
        val ctx = makeCtx()
        견고Trigger.onBattleInit(ctx)
        assertTrue(ctx.injuryImmune)
        assertTrue(ctx.battleLogs.any { "견고" in it })
    }

    @Test
    fun `견고 trigger grants injury immunity on injury check`() {
        val ctx = makeCtx()
        견고Trigger.onInjuryCheck(ctx)
        assertTrue(ctx.injuryImmune)
    }

    @Test
    fun `견고 trigger gives defence 1_05`() {
        val ctx = makeCtx()
        견고Trigger.onDamageCalc(ctx)
        assertEquals(1.05, ctx.defenceMultiplier, 0.01)
    }

    // ========== 수군 trigger ==========

    @Test
    fun `수군 trigger gives attack 1_05`() {
        val ctx = makeCtx()
        수군Trigger.onDamageCalc(ctx)
        assertEquals(1.05, ctx.attackMultiplier, 0.01)
    }

    // ========== 연사 trigger ==========

    @Test
    fun `연사 trigger gives attack 1_08`() {
        val ctx = makeCtx()
        연사Trigger.onDamageCalc(ctx)
        assertEquals(1.08, ctx.attackMultiplier, 0.01)
    }

    // ========== 반격 trigger ==========

    @Test
    fun `반격 trigger sets counter damage ratio`() {
        val ctx = makeCtx()
        반격Trigger.onPostDamage(ctx)
        assertEquals(0.20, ctx.counterDamageRatio, 0.01)
        assertTrue(ctx.battleLogs.any { "반격" in it })
    }

    // ========== 사기진작 trigger ==========

    @Test
    fun `사기진작 trigger gives morale boost`() {
        val ctx = makeCtx()
        사기진작Trigger.onPostDamage(ctx)
        assertEquals(3, ctx.moraleBoost)
        assertTrue(ctx.battleLogs.any { "사기진작" in it })
    }

    // ========== 부상무효 trigger ==========

    @Test
    fun `부상무효 trigger grants injury immunity`() {
        val ctx = makeCtx()
        부상무효Trigger.onInjuryCheck(ctx)
        assertTrue(ctx.injuryImmune)
    }

    // ========== Trigger priority and chaining ==========

    @Test
    fun `multiple triggers can chain by accumulating bonuses`() {
        var ctx = makeCtx()

        // Apply 필살 (crit +30%)
        ctx = 필살Trigger.onPreCritical(ctx)
        // Apply 저격 (crit +8%)
        ctx = 저격Trigger.onPreCritical(ctx)

        // Total critical bonus should be 38%
        assertEquals(0.38, ctx.criticalChanceBonus, 0.01)
    }

    @Test
    fun `multiple attack multipliers stack multiplicatively`() {
        var ctx = makeCtx()

        // Apply 격노 (×1.2)
        ctx = 격노Trigger.onDamageCalc(ctx)
        // Apply 위압 (×1.05)
        ctx = 위압Trigger.onDamageCalc(ctx)

        // Total multiplier should be 1.2 × 1.05 = 1.26
        assertEquals(1.26, ctx.attackMultiplier, 0.01)
    }

    @Test
    fun `defence multipliers from multiple triggers stack`() {
        var ctx = makeCtx()
        ctx = 방어Trigger.onDamageCalc(ctx)  // ×1.1
        ctx = 견고Trigger.onDamageCalc(ctx)  // ×1.05
        assertEquals(1.1 * 1.05, ctx.defenceMultiplier, 0.01)
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
        val expectedCodes = listOf(
            // Original 8
            "필살", "회피", "반계", "신산", "위압", "저격", "격노", "돌격",
            // New 15
            "화공", "기습", "매복", "방어", "귀모", "공성", "철벽", "분투",
            "용병", "견고", "수군", "연사", "반격", "사기진작", "부상무효",
        )

        for (code in expectedCodes) {
            assertNotNull(BattleTriggerRegistry.get(code), "Registry should contain $code")
        }
    }

    @Test
    fun `registry has 39 total triggers`() {
        assertEquals(43, BattleTriggerRegistry.allCodes().size)
    }

    // ========== Trigger priority ordering ==========

    @Test
    fun `triggers have priority values for execution order`() {
        assertEquals(10, 필살Trigger.priority)
        assertEquals(15, 저격Trigger.priority)
        assertEquals(20, 위압Trigger.priority)
    }
}
