package opensamguk.logic.input

import kotlin.test.*
import opensamguk.logic.economy.HwihaResources

class HwihaDomesticEffectsTest {
    private val design = HwihaDomesticDesign.CANON
    private val now = HwihaPhase(200, 1, 1)
    private val levels = HwihaCountyLevels(50_000, 100_000, 1000, 1200, 1000, 1010, 500, 600, 80.0, 900, 1000, 900, 1000)
    private fun seat(stat: Int, hometown: Boolean = false) = HwihaSeatStats(stat, stat, stat, stat, stat, hometown)

    @Test fun `design file confirms policy work and direct action rates`() {
        assertEquals(HwihaDomesticDesign.CONFIRMED, design.status)
        assertEquals(HwihaDomesticDesign.CONFIRMED, design.directActionStatus)
        assertEquals(HwihaFieldInput.INPUT_IDS, design.directActions.keys)
        assertEquals(CountyPolicy.entries.toSet(), design.countyPolicies.keys)
        assertEquals(DomesticWork.entries.toSet(), design.works.keys)
        assertEquals(CountyPolicy.AGRICULTURE, design.defaultCountyPolicy)
        // Merged works (user decision 2026-09-23): 성방 carries both the defence and the wall effect.
        assertEquals(setOf(HwihaDomesticDesign.Indicator.DEFENCE, HwihaDomesticDesign.Indicator.WALL),
            design.works.getValue(DomesticWork.FORTIFICATION).completion.map { it.indicator }.toSet())
        assertTrue(design.works.getValue(DomesticWork.WAREHOUSE).completion.isEmpty())
        // Every row declares its status: no silent design numbers.
        val raw = javaClass.classLoader.getResource(HwihaDomesticDesign.RESOURCE)!!.readText()
        val statuses = Regex("\"status\": \"([^\"]+)\"").findAll(raw).map { it.groupValues[1] }.toList()
        assertTrue(statuses.size >= 1 + 1 + 1 + CountyPolicy.entries.size + CorpsPolicy.entries.size + 1)
        assertEquals(0, statuses.count { it == "PROPOSED" }, statuses.toString())
        assertTrue(statuses.all { it in setOf(HwihaDomesticDesign.CONFIRMED, "PROPOSED") }, statuses.toString())
    }

    @Test fun `direct actions use one phase policy magnitude and cost`() {
        val actor = seat(50)
        for ((inputId, policy) in listOf(HwihaFieldInput.FARM to CountyPolicy.AGRICULTURE,
            HwihaFieldInput.COMMERCE to CountyPolicy.COMMERCE, HwihaFieldInput.SETTLE to CountyPolicy.RELIEF)) {
            assertEquals(HwihaDomesticEffects.applyPolicy(design, policy, levels, actor),
                HwihaDomesticEffects.applyDirect(design, inputId, levels, actor), inputId)
        }
        val fortify = HwihaDomesticEffects.applyDirect(design, HwihaFieldInput.FORTIFY, levels, actor)
        assertEquals(950, fortify.levels.defence)
        assertEquals(HwihaResources(money = 5_000, timber = 250), fortify.debit)
        val wall = HwihaDomesticEffects.applyDirect(design, HwihaFieldInput.REPAIR_WALL, levels, actor)
        assertEquals(950, wall.levels.wall)
        assertEquals(fortify.debit, wall.debit)
    }

    @Test fun `malformed design fails instead of defaulting`() {
        val raw = javaClass.classLoader.getResource(HwihaDomesticDesign.RESOURCE)!!.readText()
        assertFailsWith<IllegalArgumentException> { HwihaDomesticDesign.parse(raw.replace("\"name\": \"권농\"", "\"name\": \"농\"")) }
        assertFailsWith<IllegalArgumentException> { HwihaDomesticDesign.parse(raw.replace("\"code\": \"BARRACKS\"", "\"code\": \"CAMP\"")) }
        assertFailsWith<IllegalArgumentException> { HwihaDomesticDesign.parse(raw.replace("\"maxActiveWorksPerCounty\": 1", "\"maxActiveWorksPerCounty\": 2")) }
    }

    @Test fun `multiplier follows the section 8_2 stat and empty seat rule`() {
        val s = design.scaling
        assertEquals(1000, HwihaDomesticEffects.multiplier(design, null, null))
        assertEquals(s.emptySeatPermille.toLong(), HwihaDomesticEffects.multiplier(design, HwihaDomesticDesign.Stat.POLITICS, null))
        assertEquals(1000, HwihaDomesticEffects.multiplier(design, HwihaDomesticDesign.Stat.POLITICS, seat(s.neutralStat)))
        assertEquals(1000L + 30 * s.permillePerStatPoint, HwihaDomesticEffects.multiplier(design, HwihaDomesticDesign.Stat.POLITICS, seat(s.neutralStat + 30)))
        assertEquals(1000L + 30 * s.permillePerStatPoint + s.hometownBonusPermille,
            HwihaDomesticEffects.multiplier(design, HwihaDomesticDesign.Stat.POLITICS, seat(s.neutralStat + 30, hometown = true)))
        assertEquals(maxOf(s.minimumPermille.toLong(), 1000L - s.neutralStat * s.permillePerStatPoint),
            HwihaDomesticEffects.multiplier(design, HwihaDomesticDesign.Stat.POLITICS, seat(0)))
        // The floor binds once the per-point slope is steep enough.
        val steep = HwihaDomesticDesign.parse(javaClass.classLoader.getResource(HwihaDomesticDesign.RESOURCE)!!.readText()
            .replace("\"permillePerStatPoint\": ${s.permillePerStatPoint}", "\"permillePerStatPoint\": 100"))
        assertEquals(s.minimumPermille.toLong(), HwihaDomesticEffects.multiplier(steep, HwihaDomesticDesign.Stat.POLITICS, seat(0)))
    }

    @Test fun `agriculture policy scales with politics and stops at the cap`() {
        val base = design.countyPolicies.getValue(CountyPolicy.AGRICULTURE).indicators.single().amount
        val high = HwihaDomesticEffects.applyPolicy(design, CountyPolicy.AGRICULTURE, levels, seat(80))
        assertEquals(levels.agriculture + base * HwihaDomesticEffects.multiplier(design, HwihaDomesticDesign.Stat.POLITICS, seat(80)).toInt() / 1000,
            high.levels.agriculture)
        val capped = HwihaDomesticEffects.applyPolicy(design, CountyPolicy.COMMERCE, levels, seat(100))
        assertEquals(levels.commerceMax, capped.levels.commerce)
        assertEquals(HwihaResources(), high.credit)
        assertEquals(HwihaResources(), high.debit)
    }

    @Test fun `tax levy and relief move warehouse resources by households`() {
        val households = levels.population / 5L
        val tax = HwihaDomesticEffects.applyPolicy(design, CountyPolicy.HEAVY_TAX, levels, null)
        assertEquals(79.0, tax.levels.trust)
        assertEquals(households * 2000 / 1000 * design.scaling.emptySeatPermille / 1000, tax.credit.money)
        val relief = HwihaDomesticEffects.applyPolicy(design, CountyPolicy.RELIEF, levels, seat(50))
        assertEquals(households, relief.debit.grain)
        assertEquals(82.0, relief.levels.trust)
        assertEquals(levels.population + levels.population / 1000, relief.levels.population)
        val levy = HwihaDomesticEffects.applyPolicy(design, CountyPolicy.LEVY, levels, seat(50))
        assertEquals(households * 5, levy.credit.grain)
        assertEquals(levels.population - levels.population / 1000, levy.levels.population)
        assertEquals(78.0, levy.levels.trust)
    }

    @Test fun `work charges proportionally and completes at exactly the total cost`() {
        var work = HwihaDomesticEffects.newWork(design, DomesticWork.IRRIGATION, "w1", 1, now)
        val spec = design.works.getValue(DomesticWork.IRRIGATION)
        val rich = HwihaResources(10_000_000, 10_000_000, 0, 0, 0)
        var paid = HwihaResources()
        var phase = now
        var steps = 0
        while (true) {
            phase = phase.plus(1); steps++
            when (val step = HwihaDomesticEffects.progressWork(design, work, phase, rich, levels, seat(50))) {
                is HwihaWorkStep.Advanced -> { paid = paid.credit(step.debit); work = step.work; assertEquals(paid, work.charged) }
                is HwihaWorkStep.Completed -> {
                    paid = paid.credit(step.debit)
                    assertEquals(spec.cost, paid)
                    assertEquals(minOf(levels.agricultureMax, levels.agriculture + spec.completion.single().amount), step.levels.agriculture)
                    assertEquals(phase, step.completed.completedAt)
                    break
                }
                is HwihaWorkStep.Stopped -> fail("rich warehouse cannot stop")
            }
        }
        assertEquals((spec.requiredProgress + design.progressPerPhase - 1) / design.progressPerPhase, steps)
    }

    @Test fun `a short warehouse stops the work without progress and a later phase resumes`() {
        val work = HwihaDomesticEffects.newWork(design, DomesticWork.FORTIFICATION, "w1", 1, now)
        val noTimber = HwihaResources(10_000_000, 0, 0, 0, 0)
        val stopped = assertIs<HwihaWorkStep.Stopped>(HwihaDomesticEffects.progressWork(design, work, now.plus(1), noTimber, levels, null))
        assertEquals(HwihaDomesticEffects.INSUFFICIENT_STOCK, stopped.work.stopReason)
        assertEquals(0, stopped.work.progress)
        assertEquals(HwihaResources(), stopped.work.charged)
        val resumed = assertIs<HwihaWorkStep.Advanced>(HwihaDomesticEffects.progressWork(design, stopped.work, now.plus(2),
            HwihaResources(10_000_000, 0, 0, 10_000, 0), levels, null))
        assertNull(resumed.work.stopReason)
        assertEquals(design.progressPerPhase * design.scaling.emptySeatPermille / 1000, resumed.work.progress)
    }

    @Test fun `intelligence speeds works`() {
        val work = HwihaDomesticEffects.newWork(design, DomesticWork.ROAD, "w1", 1, now)
        val slow = HwihaDomesticEffects.remainingPhases(design, work, null)
        val fast = HwihaDomesticEffects.remainingPhases(design, work, seat(100))
        assertTrue(fast < slow, "$fast < $slow")
    }

    @Test fun `another road site completes without granting county commerce twice`() {
        val spec = design.works.getValue(DomesticWork.ROAD)
        val nearCompletion = HwihaDomesticEffects.newWork(design, DomesticWork.ROAD, "second-road", 1, now)
            .copy(progress = spec.requiredProgress - 1,
                charged = HwihaDomesticEffects.charged(spec.cost, spec.requiredProgress - 1, spec.requiredProgress))
        val stock = HwihaResources(10_000_000, 10_000_000, 10_000_000, 10_000_000, 10_000_000)
        val first = assertIs<HwihaWorkStep.Completed>(HwihaDomesticEffects.progressWork(
            design, nearCompletion, now.plus(1), stock, levels, null))
        val repeated = assertIs<HwihaWorkStep.Completed>(HwihaDomesticEffects.progressWork(
            design, nearCompletion, now.plus(1), stock, levels, null, alreadyCompletedInCounty = true))
        assertTrue(first.levels.commerce > levels.commerce)
        assertEquals(levels, repeated.levels)
        assertEquals(first.debit, repeated.debit)
    }

    @Test fun `strategic road and fort completion never grant county level bonuses`() {
        val stock = HwihaResources(10_000_000, 10_000_000, 10_000_000, 10_000_000, 10_000_000)
        for (kind in listOf(DomesticWork.ROAD, DomesticWork.FORTIFICATION)) {
            val spec = design.works.getValue(kind)
            val work = HwihaDomesticEffects.newWork(design, kind, "targeted", 1, now,
                "road-piece", if (kind == DomesticWork.FORTIFICATION) 1 else null,
                if (kind == DomesticWork.FORTIFICATION) 1 else null)
                .copy(progress = spec.requiredProgress - 1,
                    charged = HwihaDomesticEffects.charged(spec.cost, spec.requiredProgress - 1, spec.requiredProgress))
            val completed = assertIs<HwihaWorkStep.Completed>(HwihaDomesticEffects.progressWork(
                design, work, now.plus(1), stock, levels, null))
            assertEquals(levels, completed.levels, kind.name)
            assertEquals("road-piece", completed.completed.edgeId)
        }
    }
}
