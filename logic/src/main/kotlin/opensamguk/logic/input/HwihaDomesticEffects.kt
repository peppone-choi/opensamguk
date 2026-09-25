package opensamguk.logic.input

import opensamguk.logic.economy.HwihaCountyIncome
import opensamguk.logic.economy.HwihaResources

/** 縣治 城의 지표와 상한. 민심은 기존 저장 꼴(실수 0..100)을 그대로 쓴다. */
data class HwihaCountyLevels(
    val population: Int, val populationMax: Int,
    val agriculture: Int, val agricultureMax: Int,
    val commerce: Int, val commerceMax: Int,
    val security: Int, val securityMax: Int,
    val trust: Double,
    val defence: Int, val defenceMax: Int,
    val wall: Int, val wallMax: Int,
) {
    fun indicators(): HwihaCountyIndicators = HwihaCountyIndicators(population, agriculture, commerce, security,
        trust.toInt(), defence, wall)
}

/** 배율 계산에 쓰는 앉은 현령의 능력치. [hometown] 은 그 縣이 본관인가. */
data class HwihaSeatStats(val leadership: Int, val strength: Int, val intelligence: Int, val politics: Int, val charm: Int,
    val hometown: Boolean) {
    fun of(stat: HwihaDomesticDesign.Stat): Int = when (stat) {
        HwihaDomesticDesign.Stat.LEADERSHIP -> leadership
        HwihaDomesticDesign.Stat.STRENGTH -> strength
        HwihaDomesticDesign.Stat.INTELLIGENCE -> intelligence
        HwihaDomesticDesign.Stat.POLITICS -> politics
        HwihaDomesticDesign.Stat.CHARM -> charm
    }
}

data class HwihaPolicyOutcome(val levels: HwihaCountyLevels, val credit: HwihaResources, val debit: HwihaResources)

sealed interface HwihaWorkStep {
    /** 창고가 모자라 이번 순은 진척이 없다. */
    data class Stopped(val work: HwihaActiveWork, val reason: String) : HwihaWorkStep
    data class Advanced(val work: HwihaActiveWork, val debit: HwihaResources) : HwihaWorkStep
    data class Completed(val completed: HwihaCompletedWork, val debit: HwihaResources, val levels: HwihaCountyLevels) : HwihaWorkStep
}

/** 방침 효과·공사 진척의 순수 계산. 정수 연산이며 0 쪽으로 자른다(결정론). */
object HwihaDomesticEffects {
    const val INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK"

    /** 천분율 배율. 고정량(stat null)은 1000, 빈자리는 emptySeatPermille. */
    fun multiplier(design: HwihaDomesticDesign, stat: HwihaDomesticDesign.Stat?, seat: HwihaSeatStats?): Long {
        if (stat == null) return 1000
        val scaling = design.scaling
        if (seat == null) return scaling.emptySeatPermille.toLong()
        val raw = 1000L + (seat.of(stat) - scaling.neutralStat).toLong() * scaling.permillePerStatPoint +
            if (seat.hometown) scaling.hometownBonusPermille.toLong() else 0L
        return maxOf(scaling.minimumPermille.toLong(), raw)
    }

    fun applyPolicy(design: HwihaDomesticDesign, policy: CountyPolicy, levels: HwihaCountyLevels, seat: HwihaSeatStats?): HwihaPolicyOutcome {
        val effect = design.countyPolicies.getValue(policy)
        return applyEffects(design, effect.indicators, effect.resources, levels, seat)
    }

    /** One direct action uses the acting general's stats and the same one-phase effect arithmetic as a policy. */
    fun applyDirect(design: HwihaDomesticDesign, inputId: String, levels: HwihaCountyLevels,
        actor: HwihaSeatStats): HwihaPolicyOutcome {
        val action = design.directActions.getValue(inputId)
        val base = applyEffects(design, action.indicators, action.resources, levels, actor)
        val rate = multiplier(design, action.costStat, actor)
        fun scaled(value: Long) = Math.multiplyExact(value, rate) / 1000L
        val fixed = action.fixedCost.let { HwihaResources(scaled(it.money), scaled(it.grain), scaled(it.iron),
            scaled(it.timber), scaled(it.horses)) }
        return base.copy(debit = base.debit.credit(fixed))
    }

    private fun applyEffects(design: HwihaDomesticDesign, indicators: List<HwihaDomesticDesign.IndicatorEffect>,
        resources: List<HwihaDomesticDesign.ResourceFlow>, levels: HwihaCountyLevels,
        seat: HwihaSeatStats?): HwihaPolicyOutcome {
        var next = levels
        for (entry in indicators) {
            val base = when (entry.unit) {
                HwihaDomesticDesign.Unit.ABSOLUTE -> entry.amount.toLong()
                HwihaDomesticDesign.Unit.PERMILLE_OF_CURRENT -> current(levels, entry.indicator) * entry.amount / 1000
            }
            next = add(next, entry.indicator, base * multiplier(design, entry.stat, seat) / 1000)
        }
        val households = HwihaCountyIncome.households(levels.population)
        var credit = HwihaResources()
        var debit = HwihaResources()
        for (flow in resources) {
            val amount = Math.multiplyExact(households, flow.perHouseholdPermille.toLong()) / 1000 *
                multiplier(design, flow.stat, seat) / 1000
            val resources = resource(flow.resource, amount)
            if (flow.direction == HwihaDomesticDesign.Direction.CREDIT) credit = credit.credit(resources) else debit = debit.credit(resources)
        }
        return HwihaPolicyOutcome(next, credit, debit)
    }

    fun newWork(design: HwihaDomesticDesign, work: DomesticWork, requestId: String, actorId: Int, requestedAt: HwihaPhase,
        edgeId: String? = null, row: Int? = null, col: Int? = null): HwihaActiveWork {
        val spec = design.works.getValue(work)
        return HwihaActiveWork(work, requestId, actorId, requestedAt, 0, spec.requiredProgress, spec.cost, HwihaResources(), null, null,
            edgeId, row, col)
    }

    /** 이번 순에 한 번 진척한다. [stock] 은 그 縣 창고의 현재 재고다. */
    fun progressWork(design: HwihaDomesticDesign, work: HwihaActiveWork, now: HwihaPhase, stock: HwihaResources,
        levels: HwihaCountyLevels, seat: HwihaSeatStats?, alreadyCompletedInCounty: Boolean = false): HwihaWorkStep {
        val speed = design.progressPerPhase.toLong() * multiplier(design, HwihaDomesticDesign.Stat.INTELLIGENCE, seat) / 1000
        val next = minOf(work.required.toLong(), work.progress + maxOf(1L, speed)).toInt()
        val due = charged(work.cost, next, work.required).debit(work.charged)
            ?: throw IllegalArgumentException("charged installments exceed the cumulative charge")
        if (stock.debit(due) == null) return HwihaWorkStep.Stopped(work.copy(stopReason = INSUFFICIENT_STOCK), INSUFFICIENT_STOCK)
        if (next == work.required) {
            var after = levels
            // Strategic roads and forts change the world map, not the county's commerce or walls.
            if (work.edgeId == null && !alreadyCompletedInCounty)
                for (effect in design.works.getValue(work.work).completion)
                after = add(after, effect.indicator, effect.amount.toLong())
            return HwihaWorkStep.Completed(HwihaCompletedWork(work.work, now, work.edgeId, work.row, work.col), due, after)
        }
        return HwihaWorkStep.Advanced(work.copy(progress = next, charged = work.charged.credit(due), lastProgressAt = now,
            stopReason = null), due)
    }

    /** 진척 [progress] 까지의 누적 청구 = 총비용 × 진척 / 필요량(내림). 완공 시 정확히 총비용이다. */
    fun charged(cost: HwihaResources, progress: Int, required: Int): HwihaResources {
        require(required > 0 && progress in 0..required)
        fun part(total: Long) = Math.multiplyExact(total, progress.toLong()) / required
        return HwihaResources(part(cost.money), part(cost.grain), part(cost.iron), part(cost.timber), part(cost.horses))
    }

    /** 남은 순 수(현재 속도 기준, 올림). */
    fun remainingPhases(design: HwihaDomesticDesign, work: HwihaActiveWork, seat: HwihaSeatStats?): Int {
        val speed = maxOf(1L, design.progressPerPhase.toLong() * multiplier(design, HwihaDomesticDesign.Stat.INTELLIGENCE, seat) / 1000)
        return ((work.required - work.progress + speed - 1) / speed).toInt()
    }

    fun levelsAfter(levels: HwihaCountyLevels, indicator: HwihaDomesticDesign.Indicator, delta: Long): HwihaCountyLevels =
        add(levels, indicator, delta)

    private fun current(levels: HwihaCountyLevels, indicator: HwihaDomesticDesign.Indicator): Long = when (indicator) {
        HwihaDomesticDesign.Indicator.POPULATION -> levels.population.toLong()
        HwihaDomesticDesign.Indicator.AGRICULTURE -> levels.agriculture.toLong()
        HwihaDomesticDesign.Indicator.COMMERCE -> levels.commerce.toLong()
        HwihaDomesticDesign.Indicator.SECURITY -> levels.security.toLong()
        HwihaDomesticDesign.Indicator.TRUST -> levels.trust.toLong()
        HwihaDomesticDesign.Indicator.DEFENCE -> levels.defence.toLong()
        HwihaDomesticDesign.Indicator.WALL -> levels.wall.toLong()
    }

    /** 상한·하한에 맞춘다. 이미 상한을 넘은 값은 올리지 않을 뿐 깎지 않는다. */
    private fun add(levels: HwihaCountyLevels, indicator: HwihaDomesticDesign.Indicator, delta: Long): HwihaCountyLevels {
        if (delta == 0L) return levels
        fun bounded(value: Int, max: Int): Int {
            val raw = value.toLong() + delta
            val capped = if (delta > 0) minOf(raw, maxOf(value, max).toLong()) else raw
            return maxOf(0L, capped).toInt()
        }
        return when (indicator) {
            HwihaDomesticDesign.Indicator.POPULATION -> levels.copy(population = bounded(levels.population, levels.populationMax))
            HwihaDomesticDesign.Indicator.AGRICULTURE -> levels.copy(agriculture = bounded(levels.agriculture, levels.agricultureMax))
            HwihaDomesticDesign.Indicator.COMMERCE -> levels.copy(commerce = bounded(levels.commerce, levels.commerceMax))
            HwihaDomesticDesign.Indicator.SECURITY -> levels.copy(security = bounded(levels.security, levels.securityMax))
            HwihaDomesticDesign.Indicator.DEFENCE -> levels.copy(defence = bounded(levels.defence, levels.defenceMax))
            HwihaDomesticDesign.Indicator.WALL -> levels.copy(wall = bounded(levels.wall, levels.wallMax))
            HwihaDomesticDesign.Indicator.TRUST -> {
                val raw = levels.trust + delta
                val capped = if (delta > 0) minOf(raw, maxOf(levels.trust, 100.0)) else raw
                levels.copy(trust = maxOf(0.0, capped))
            }
        }
    }

    private fun resource(kind: HwihaDomesticDesign.Resource, amount: Long): HwihaResources = when (kind) {
        HwihaDomesticDesign.Resource.MONEY -> HwihaResources(money = amount)
        HwihaDomesticDesign.Resource.GRAIN -> HwihaResources(grain = amount)
        HwihaDomesticDesign.Resource.IRON -> HwihaResources(iron = amount)
        HwihaDomesticDesign.Resource.TIMBER -> HwihaResources(timber = amount)
        HwihaDomesticDesign.Resource.HORSES -> HwihaResources(horses = amount)
    }
}
