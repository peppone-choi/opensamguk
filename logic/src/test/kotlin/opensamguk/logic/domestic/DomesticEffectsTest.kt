package opensamguk.logic.domestic

import kotlin.test.*
import opensamguk.logic.economy.HwihaResources
import opensamguk.logic.domestic.FieldInput
import opensamguk.logic.input.HwihaPhase

class DomesticEffectsTest {
    private val design = DomesticDesign.CANON
    private val now = HwihaPhase(200, 1, 1)
    private val levels = CountyLevels(50_000, 100_000, 1000, 1200, 1000, 1010, 500, 600, 80.0, 900, 1000, 900, 1000)
    private fun seat(stat: Int, hometown: Boolean = false) = SeatStats(stat, stat, stat, stat, stat, hometown)

    @Test fun `design file confirms policy work and direct action rates`() {
        assertEquals(DomesticDesign.CONFIRMED, design.status)
        assertEquals(DomesticDesign.CONFIRMED, design.directActionStatus)
        assertEquals(FieldInput.INPUT_IDS, design.directActions.keys)
        assertEquals(CountyPolicy.entries.toSet(), design.countyPolicies.keys)
        assertEquals(DomesticWork.entries.toSet(), design.works.keys)
        assertEquals(CountyPolicy.AGRICULTURE, design.defaultCountyPolicy)
        // Merged works (user decision 2026-09-23): 성방 carries both the defence and the wall effect.
        assertEquals(setOf(DomesticDesign.Indicator.DEFENCE, DomesticDesign.Indicator.WALL),
            design.works.getValue(DomesticWork.FORTIFICATION).completion.map { it.indicator }.toSet())
        assertTrue(design.works.getValue(DomesticWork.WAREHOUSE).completion.isEmpty())
        // Every row declares its status: no silent design numbers.
        val raw = javaClass.classLoader.getResource(DomesticDesign.RESOURCE)!!.readText()
        val statuses = Regex("\"status\": \"([^\"]+)\"").findAll(raw).map { it.groupValues[1] }.toList()
        assertTrue(statuses.size >= 1 + 1 + 1 + CountyPolicy.entries.size + CorpsPolicy.entries.size + 1)
        assertEquals(0, statuses.count { it == "PROPOSED" }, statuses.toString())
        assertTrue(statuses.all { it in setOf(DomesticDesign.CONFIRMED, "PROPOSED") }, statuses.toString())
    }

    @Test fun `direct actions use one phase policy magnitude and cost`() {
        val actor = seat(50)
        for ((inputId, policy) in listOf(FieldInput.FARM to CountyPolicy.AGRICULTURE,
            FieldInput.COMMERCE to CountyPolicy.COMMERCE, FieldInput.SETTLE to CountyPolicy.RELIEF)) {
            assertEquals(DomesticEffects.applyPolicy(design, policy, levels, actor),
                DomesticEffects.applyDirect(design, inputId, levels, actor), inputId)
        }
        val fortify = DomesticEffects.applyDirect(design, FieldInput.FORTIFY, levels, actor)
        assertEquals(950, fortify.levels.defence)
        assertEquals(HwihaResources(money = 5_000, timber = 250), fortify.debit)
        val wall = DomesticEffects.applyDirect(design, FieldInput.REPAIR_WALL, levels, actor)
        assertEquals(950, wall.levels.wall)
        assertEquals(fortify.debit, wall.debit)
    }

    @Test fun `malformed design fails instead of defaulting`() {
        val raw = javaClass.classLoader.getResource(DomesticDesign.RESOURCE)!!.readText()
        assertFailsWith<IllegalArgumentException> { DomesticDesign.parse(raw.replace("\"name\": \"권농\"", "\"name\": \"농\"")) }
        assertFailsWith<IllegalArgumentException> { DomesticDesign.parse(raw.replace("\"code\": \"BARRACKS\"", "\"code\": \"CAMP\"")) }
        assertFailsWith<IllegalArgumentException> { DomesticDesign.parse(raw.replace("\"maxActiveWorksPerCounty\": 1", "\"maxActiveWorksPerCounty\": 2")) }
    }

    @Test fun `multiplier follows the section 8_2 stat and empty seat rule`() {
        val s = design.scaling
        assertEquals(1000, DomesticEffects.multiplier(design, null, null))
        assertEquals(s.emptySeatPermille.toLong(), DomesticEffects.multiplier(design, DomesticDesign.Stat.POLITICS, null))
        assertEquals(1000, DomesticEffects.multiplier(design, DomesticDesign.Stat.POLITICS, seat(s.neutralStat)))
        assertEquals(1000L + 30 * s.permillePerStatPoint, DomesticEffects.multiplier(design, DomesticDesign.Stat.POLITICS, seat(s.neutralStat + 30)))
        assertEquals(1000L + 30 * s.permillePerStatPoint + s.hometownBonusPermille,
            DomesticEffects.multiplier(design, DomesticDesign.Stat.POLITICS, seat(s.neutralStat + 30, hometown = true)))
        assertEquals(maxOf(s.minimumPermille.toLong(), 1000L - s.neutralStat * s.permillePerStatPoint),
            DomesticEffects.multiplier(design, DomesticDesign.Stat.POLITICS, seat(0)))
        // The floor binds once the per-point slope is steep enough.
        val steep = DomesticDesign.parse(javaClass.classLoader.getResource(DomesticDesign.RESOURCE)!!.readText()
            .replace("\"permillePerStatPoint\": ${s.permillePerStatPoint}", "\"permillePerStatPoint\": 100"))
        assertEquals(s.minimumPermille.toLong(), DomesticEffects.multiplier(steep, DomesticDesign.Stat.POLITICS, seat(0)))
    }

    @Test fun `agriculture policy scales with politics and stops at the cap`() {
        val base = design.countyPolicies.getValue(CountyPolicy.AGRICULTURE).indicators.single().amount
        val high = DomesticEffects.applyPolicy(design, CountyPolicy.AGRICULTURE, levels, seat(80))
        assertEquals(levels.agriculture + base * DomesticEffects.multiplier(design, DomesticDesign.Stat.POLITICS, seat(80)).toInt() / 1000,
            high.levels.agriculture)
        val capped = DomesticEffects.applyPolicy(design, CountyPolicy.COMMERCE, levels, seat(100))
        assertEquals(levels.commerceMax, capped.levels.commerce)
        assertEquals(HwihaResources(), high.credit)
        assertEquals(HwihaResources(), high.debit)
    }

    @Test fun `tax levy and relief move warehouse resources by households`() {
        val households = levels.population / 5L
        val tax = DomesticEffects.applyPolicy(design, CountyPolicy.HEAVY_TAX, levels, null)
        assertEquals(79.0, tax.levels.trust)
        assertEquals(households * 2000 / 1000 * design.scaling.emptySeatPermille / 1000, tax.credit.money)
        val relief = DomesticEffects.applyPolicy(design, CountyPolicy.RELIEF, levels, seat(50))
        assertEquals(households, relief.debit.grain)
        assertEquals(82.0, relief.levels.trust)
        assertEquals(levels.population + levels.population / 1000, relief.levels.population)
        val levy = DomesticEffects.applyPolicy(design, CountyPolicy.LEVY, levels, seat(50))
        assertEquals(households * 5, levy.credit.grain)
        assertEquals(levels.population - levels.population / 1000, levy.levels.population)
        assertEquals(78.0, levy.levels.trust)
    }

    @Test fun `work charges proportionally and completes at exactly the total cost`() {
        var work = DomesticEffects.newWork(design, DomesticWork.IRRIGATION, "w1", 1, now)
        val spec = design.works.getValue(DomesticWork.IRRIGATION)
        val rich = HwihaResources(10_000_000, 10_000_000, 0, 0, 0)
        var paid = HwihaResources()
        var phase = now
        var steps = 0
        while (true) {
            phase = phase.plus(1); steps++
            when (val step = DomesticEffects.progressWork(design, work, phase, rich, levels, seat(50))) {
                is WorkStep.Advanced -> { paid = paid.credit(step.debit); work = step.work; assertEquals(paid, work.charged) }
                is WorkStep.Completed -> {
                    paid = paid.credit(step.debit)
                    assertEquals(spec.cost, paid)
                    assertEquals(minOf(levels.agricultureMax, levels.agriculture + spec.completion.single().amount), step.levels.agriculture)
                    assertEquals(phase, step.completed.completedAt)
                    break
                }
                is WorkStep.Stopped -> fail("rich warehouse cannot stop")
            }
        }
        assertEquals((spec.requiredProgress + design.progressPerPhase - 1) / design.progressPerPhase, steps)
    }

    @Test fun `a short warehouse stops the work without progress and a later phase resumes`() {
        val work = DomesticEffects.newWork(design, DomesticWork.FORTIFICATION, "w1", 1, now)
        val noTimber = HwihaResources(10_000_000, 0, 0, 0, 0)
        val stopped = assertIs<WorkStep.Stopped>(DomesticEffects.progressWork(design, work, now.plus(1), noTimber, levels, null))
        assertEquals(DomesticEffects.INSUFFICIENT_STOCK, stopped.work.stopReason)
        assertEquals(0, stopped.work.progress)
        assertEquals(HwihaResources(), stopped.work.charged)
        val resumed = assertIs<WorkStep.Advanced>(DomesticEffects.progressWork(design, stopped.work, now.plus(2),
            HwihaResources(10_000_000, 0, 0, 10_000, 0), levels, null))
        assertNull(resumed.work.stopReason)
        assertEquals(design.progressPerPhase * design.scaling.emptySeatPermille / 1000, resumed.work.progress)
    }

    @Test fun `intelligence speeds works`() {
        val work = DomesticEffects.newWork(design, DomesticWork.ROAD, "w1", 1, now)
        val slow = DomesticEffects.remainingPhases(design, work, null)
        val fast = DomesticEffects.remainingPhases(design, work, seat(100))
        assertTrue(fast < slow, "$fast < $slow")
    }
}
