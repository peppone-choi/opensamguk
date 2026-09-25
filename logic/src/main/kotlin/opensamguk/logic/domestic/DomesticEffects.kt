package opensamguk.logic.domestic

import opensamguk.logic.economy.CountyIncome
import opensamguk.logic.economy.Resources
import opensamguk.logic.domestic.ActiveWork
import opensamguk.logic.domestic.CompletedWork
import opensamguk.logic.domestic.CountyIndicators
import opensamguk.logic.input.Phase

/** 縣治 城의 지표와 상한. 민심은 기존 저장 꼴(실수 0..100)을 그대로 쓴다. */
data class CountyLevels(
    val population: Int, val populationMax: Int,
    val agriculture: Int, val agricultureMax: Int,
    val commerce: Int, val commerceMax: Int,
    val security: Int, val securityMax: Int,
    val trust: Double,
    val defence: Int, val defenceMax: Int,
    val wall: Int, val wallMax: Int,
) {
    fun indicators(): CountyIndicators = CountyIndicators(population, agriculture, commerce, security,
        trust.toInt(), defence, wall)
}

/** 배율 계산에 쓰는 앉은 현령의 능력치. [hometown] 은 그 縣이 본관인가. */
data class SeatStats(val leadership: Int, val strength: Int, val intelligence: Int, val politics: Int, val charm: Int,
    val hometown: Boolean) {
    fun of(stat: DomesticDesign.Stat): Int = when (stat) {
        DomesticDesign.Stat.LEADERSHIP -> leadership
        DomesticDesign.Stat.STRENGTH -> strength
        DomesticDesign.Stat.INTELLIGENCE -> intelligence
        DomesticDesign.Stat.POLITICS -> politics
        DomesticDesign.Stat.CHARM -> charm
    }
}

data class PolicyOutcome(val levels: CountyLevels, val credit: Resources, val debit: Resources)

sealed interface WorkStep {
    /** 창고가 모자라 이번 순은 진척이 없다. */
    data class Stopped(val work: ActiveWork, val reason: String) : WorkStep
    data class Advanced(val work: ActiveWork, val debit: Resources) : WorkStep
    data class Completed(val completed: CompletedWork, val debit: Resources, val levels: CountyLevels) : WorkStep
}

/** 방침 효과·공사 진척의 순수 계산. 정수 연산이며 0 쪽으로 자른다(결정론). */
object DomesticEffects {
    const val INSUFFICIENT_STOCK = "INSUFFICIENT_STOCK"

    /** 천분율 배율. 고정량(stat null)은 1000, 빈자리는 emptySeatPermille. */
    fun multiplier(design: DomesticDesign, stat: DomesticDesign.Stat?, seat: SeatStats?): Long {
        if (stat == null) return 1000
        val scaling = design.scaling
        if (seat == null) return scaling.emptySeatPermille.toLong()
        val raw = 1000L + (seat.of(stat) - scaling.neutralStat).toLong() * scaling.permillePerStatPoint +
            if (seat.hometown) scaling.hometownBonusPermille.toLong() else 0L
        return maxOf(scaling.minimumPermille.toLong(), raw)
    }

    fun applyPolicy(design: DomesticDesign, policy: CountyPolicy, levels: CountyLevels, seat: SeatStats?): PolicyOutcome {
        val effect = design.countyPolicies.getValue(policy)
        return applyEffects(design, effect.indicators, effect.resources, levels, seat)
    }

    /** One direct action uses the acting general's stats and the same one-phase effect arithmetic as a policy. */
    fun applyDirect(design: DomesticDesign, inputId: String, levels: CountyLevels,
        actor: SeatStats): PolicyOutcome {
        val action = design.directActions.getValue(inputId)
        val base = applyEffects(design, action.indicators, action.resources, levels, actor)
        val rate = multiplier(design, action.costStat, actor)
        fun scaled(value: Long) = Math.multiplyExact(value, rate) / 1000L
        val fixed = action.fixedCost.let { Resources(scaled(it.money), scaled(it.grain), scaled(it.iron),
            scaled(it.timber), scaled(it.horses)) }
        return base.copy(debit = base.debit.credit(fixed))
    }

    private fun applyEffects(design: DomesticDesign, indicators: List<DomesticDesign.IndicatorEffect>,
        resources: List<DomesticDesign.ResourceFlow>, levels: CountyLevels,
        seat: SeatStats?): PolicyOutcome {
        var next = levels
        for (entry in indicators) {
            val base = when (entry.unit) {
                DomesticDesign.Unit.ABSOLUTE -> entry.amount.toLong()
                DomesticDesign.Unit.PERMILLE_OF_CURRENT -> current(levels, entry.indicator) * entry.amount / 1000
            }
            next = add(next, entry.indicator, base * multiplier(design, entry.stat, seat) / 1000)
        }
        val households = CountyIncome.households(levels.population)
        var credit = Resources()
        var debit = Resources()
        for (flow in resources) {
            val amount = Math.multiplyExact(households, flow.perHouseholdPermille.toLong()) / 1000 *
                multiplier(design, flow.stat, seat) / 1000
            val resources = resource(flow.resource, amount)
            if (flow.direction == DomesticDesign.Direction.CREDIT) credit = credit.credit(resources) else debit = debit.credit(resources)
        }
        return PolicyOutcome(next, credit, debit)
    }

    fun newWork(design: DomesticDesign, work: DomesticWork, requestId: String, actorId: Int, requestedAt: Phase): ActiveWork {
        val spec = design.works.getValue(work)
        return ActiveWork(work, requestId, actorId, requestedAt, 0, spec.requiredProgress, spec.cost, Resources(), null, null)
    }

    /** 이번 순에 한 번 진척한다. [stock] 은 그 縣 창고의 현재 재고다. */
    fun progressWork(design: DomesticDesign, work: ActiveWork, now: Phase, stock: Resources,
        levels: CountyLevels, seat: SeatStats?): WorkStep {
        val speed = design.progressPerPhase.toLong() * multiplier(design, DomesticDesign.Stat.INTELLIGENCE, seat) / 1000
        val next = minOf(work.required.toLong(), work.progress + maxOf(1L, speed)).toInt()
        val due = charged(work.cost, next, work.required).debit(work.charged)
            ?: throw IllegalArgumentException("charged installments exceed the cumulative charge")
        if (stock.debit(due) == null) return WorkStep.Stopped(work.copy(stopReason = INSUFFICIENT_STOCK), INSUFFICIENT_STOCK)
        if (next == work.required) {
            var after = levels
            for (effect in design.works.getValue(work.work).completion) after = add(after, effect.indicator, effect.amount.toLong())
            return WorkStep.Completed(CompletedWork(work.work, now), due, after)
        }
        return WorkStep.Advanced(work.copy(progress = next, charged = work.charged.credit(due), lastProgressAt = now,
            stopReason = null), due)
    }

    /** 진척 [progress] 까지의 누적 청구 = 총비용 × 진척 / 필요량(내림). 완공 시 정확히 총비용이다. */
    fun charged(cost: Resources, progress: Int, required: Int): Resources {
        require(required > 0 && progress in 0..required)
        fun part(total: Long) = Math.multiplyExact(total, progress.toLong()) / required
        return Resources(part(cost.money), part(cost.grain), part(cost.iron), part(cost.timber), part(cost.horses))
    }

    /** 남은 순 수(현재 속도 기준, 올림). */
    fun remainingPhases(design: DomesticDesign, work: ActiveWork, seat: SeatStats?): Int {
        val speed = maxOf(1L, design.progressPerPhase.toLong() * multiplier(design, DomesticDesign.Stat.INTELLIGENCE, seat) / 1000)
        return ((work.required - work.progress + speed - 1) / speed).toInt()
    }

    fun levelsAfter(levels: CountyLevels, indicator: DomesticDesign.Indicator, delta: Long): CountyLevels =
        add(levels, indicator, delta)

    private fun current(levels: CountyLevels, indicator: DomesticDesign.Indicator): Long = when (indicator) {
        DomesticDesign.Indicator.POPULATION -> levels.population.toLong()
        DomesticDesign.Indicator.AGRICULTURE -> levels.agriculture.toLong()
        DomesticDesign.Indicator.COMMERCE -> levels.commerce.toLong()
        DomesticDesign.Indicator.SECURITY -> levels.security.toLong()
        DomesticDesign.Indicator.TRUST -> levels.trust.toLong()
        DomesticDesign.Indicator.DEFENCE -> levels.defence.toLong()
        DomesticDesign.Indicator.WALL -> levels.wall.toLong()
    }

    /** 상한·하한에 맞춘다. 이미 상한을 넘은 값은 올리지 않을 뿐 깎지 않는다. */
    private fun add(levels: CountyLevels, indicator: DomesticDesign.Indicator, delta: Long): CountyLevels {
        if (delta == 0L) return levels
        fun bounded(value: Int, max: Int): Int {
            val raw = value.toLong() + delta
            val capped = if (delta > 0) minOf(raw, maxOf(value, max).toLong()) else raw
            return maxOf(0L, capped).toInt()
        }
        return when (indicator) {
            DomesticDesign.Indicator.POPULATION -> levels.copy(population = bounded(levels.population, levels.populationMax))
            DomesticDesign.Indicator.AGRICULTURE -> levels.copy(agriculture = bounded(levels.agriculture, levels.agricultureMax))
            DomesticDesign.Indicator.COMMERCE -> levels.copy(commerce = bounded(levels.commerce, levels.commerceMax))
            DomesticDesign.Indicator.SECURITY -> levels.copy(security = bounded(levels.security, levels.securityMax))
            DomesticDesign.Indicator.DEFENCE -> levels.copy(defence = bounded(levels.defence, levels.defenceMax))
            DomesticDesign.Indicator.WALL -> levels.copy(wall = bounded(levels.wall, levels.wallMax))
            DomesticDesign.Indicator.TRUST -> {
                val raw = levels.trust + delta
                val capped = if (delta > 0) minOf(raw, maxOf(levels.trust, 100.0)) else raw
                levels.copy(trust = maxOf(0.0, capped))
            }
        }
    }

    private fun resource(kind: DomesticDesign.Resource, amount: Long): Resources = when (kind) {
        DomesticDesign.Resource.MONEY -> Resources(money = amount)
        DomesticDesign.Resource.GRAIN -> Resources(grain = amount)
        DomesticDesign.Resource.IRON -> Resources(iron = amount)
        DomesticDesign.Resource.TIMBER -> Resources(timber = amount)
        DomesticDesign.Resource.HORSES -> Resources(horses = amount)
    }
}
