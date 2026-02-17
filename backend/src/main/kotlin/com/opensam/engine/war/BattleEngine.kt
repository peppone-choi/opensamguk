package com.opensam.engine.war

import com.opensam.entity.City
import kotlin.math.round
import kotlin.random.Random

data class BattleResult(
    val attackerWon: Boolean,
    val attackerLogs: MutableList<String> = mutableListOf(),
    val defenderLogs: MutableList<String> = mutableListOf(),
    val attackerDamageDealt: Int = 0,
    val defenderDamageDealt: Int = 0,
    val cityOccupied: Boolean = false,
)

class BattleEngine {

    companion object {
        /** Legacy GameConst::$armperphase - base war power per phase. */
        const val ARM_PER_PHASE = 500.0
    }

    fun resolveBattle(
        attacker: WarUnitGeneral,
        defenders: List<WarUnit>,
        city: City,
        rng: Random,
    ): BattleResult {
        val logs = mutableListOf<String>()
        var totalAttackerDamage = 0
        var totalDefenderDamage = 0
        var attackerWon = true
        var cityOccupied = false

        // Sort defenders by battle order (highest first)
        val sortedDefenders = defenders.sortedByDescending { it.calcBattleOrder() }

        // Collect attacker triggers once (used across engagements)
        val attackerTriggers = collectTriggers(attacker)

        // Track injury immunity from init triggers
        var attackerInjuryImmune = false

        // Phase 1: Fight each defender general
        for (defender in sortedDefenders) {
            if (!attacker.continueWar()) {
                attackerWon = false
                break
            }
            if (!defender.isAlive) continue

            val defenderTriggers = collectTriggers(defender)

            // Fire battle-init triggers (once per engagement)
            val initCtx = BattleTriggerContext(attacker = attacker, defender = defender, rng = rng)
            for (trigger in attackerTriggers) trigger.onBattleInit(initCtx)
            for (trigger in defenderTriggers) trigger.onBattleInit(initCtx)
            if (initCtx.injuryImmune) attackerInjuryImmune = true
            logs.addAll(initCtx.battleLogs)

            val phaseResult = executeCombatPhase(attacker, defender, rng, phaseNumber = 0, isVsCity = false)
            totalAttackerDamage += phaseResult.damage.first
            totalDefenderDamage += phaseResult.damage.second
            logs.addAll(phaseResult.logs)

            logs.add("<Y>${attacker.name}</> vs <Y>${defender.name}</> - " +
                "공격 피해: ${phaseResult.damage.first}, 방어 피해: ${phaseResult.damage.second}")

            // Attacker continuation check
            if (!attacker.continueWar()) {
                logs.add("<Y>${attacker.name}</>이(가) 퇴각합니다.")
                attackerWon = false
                break
            }

            // Defender continuation check (rice + HP)
            if (defender is WarUnitGeneral && !defender.continueWar()) {
                logs.add("<Y>${defender.name}</>이(가) 퇴각합니다.")
            }
        }

        // Phase 2: Siege if all defender generals eliminated and attacker can continue
        val allDefendersDown = sortedDefenders.all {
            (it is WarUnitGeneral && !it.continueWar()) || it is WarUnitCity
        }
        if (attackerWon && attacker.continueWar() && allDefendersDown) {
            val cityUnit = WarUnitCity(city)
            var siegePhase = 0

            // Fire battle-init triggers for siege engagement
            val siegeInitCtx = BattleTriggerContext(attacker = attacker, defender = cityUnit, rng = rng, isVsCity = true)
            for (trigger in attackerTriggers) trigger.onBattleInit(siegeInitCtx)
            if (siegeInitCtx.injuryImmune) attackerInjuryImmune = true
            logs.addAll(siegeInitCtx.battleLogs)

            // No round cap - legacy has no siege round limit
            while (attacker.continueWar() && cityUnit.isAlive) {
                val phaseResult = executeCombatPhase(attacker, cityUnit, rng, phaseNumber = siegePhase, isVsCity = true)
                totalAttackerDamage += phaseResult.damage.first
                totalDefenderDamage += phaseResult.damage.second
                logs.addAll(phaseResult.logs)
                siegePhase++

                if (!attacker.continueWar()) {
                    attackerWon = false
                    break
                }
            }

            if (!cityUnit.isAlive || cityUnit.hp <= 0) {
                cityOccupied = true
                cityUnit.applyResults()
                logs.add("<R>${city.name}</> 점령!")
            }
        }

        // Injury check: fire onInjuryCheck triggers before wound roll
        val injuryCtx = BattleTriggerContext(attacker = attacker, defender = attacker, rng = rng)
        for (trigger in attackerTriggers) trigger.onInjuryCheck(injuryCtx)
        val effectiveInjuryImmune = attackerInjuryImmune || injuryCtx.injuryImmune

        // Apply injury chance (5%) unless immune
        if (!effectiveInjuryImmune && rng.nextDouble() < 0.05 && attacker.isAlive) {
            attacker.injury = (attacker.injury + rng.nextInt(1, 4)).coerceAtMost(80)
            logs.add("<Y>${attacker.name}</>이(가) 부상을 입었습니다.")
        }

        attacker.applyResults()
        for (def in sortedDefenders) {
            if (def is WarUnitGeneral) def.applyResults()
        }

        return BattleResult(
            attackerWon = attackerWon && attacker.isAlive,
            attackerLogs = logs,
            defenderLogs = logs.toMutableList(),
            attackerDamageDealt = totalAttackerDamage,
            defenderDamageDealt = totalDefenderDamage,
            cityOccupied = cityOccupied,
        )
    }

    /**
     * Compute war power for one side attacking another.
     * Legacy: WarUnit::computeWarPower() + WarUnitGeneral::computeWarPower()
     *
     * Formula: (armperphase + myAttack - opDefence) × atmos/train × expLevel × random
     */
    private fun computeWarPower(attacker: WarUnit, defender: WarUnit, rng: Random): Double {
        val myAttack = attacker.getBaseAttack()
        val opDefence = defender.getBaseDefence()

        var warPower = ARM_PER_PHASE + myAttack - opDefence

        // Floor guarantee: minimum ~50 war power
        if (warPower < 100.0) {
            warPower = maxOf(0.0, warPower)
            warPower = (warPower + 100.0) / 2.0
            warPower = warPower + rng.nextDouble() * (100.0 - warPower)
        }

        warPower *= attacker.atmos.toDouble()
        warPower /= maxOf(1.0, defender.train.toDouble())

        val attackerDex = 0
        val defenderDex = 0
        warPower *= getDexLog(attackerDex, defenderDex)

        // TODO: Replace with CrewType coefficient tables from legacy parity data.
        val attackTypeCoef = 1.0
        val defenceTypeCoef = 1.0
        warPower *= attackTypeCoef
        warPower /= maxOf(0.01, defenceTypeCoef)

        // Experience level scaling (WarUnitGeneral only)
        if (attacker is WarUnitGeneral) {
            val expLevel = attacker.general.expLevel.toInt()
            if (defender is WarUnitCity) {
                warPower *= 1.0 + expLevel / 600.0
            } else {
                warPower /= maxOf(0.01, 1.0 - expLevel / 300.0)
            }
        }

        // Random variance ±10%
        warPower *= 0.9 + rng.nextDouble() * 0.2

        return maxOf(1.0, round(warPower))
    }

    internal fun collectTriggers(unit: WarUnit): List<BattleTrigger> {
        if (unit !is WarUnitGeneral) return emptyList()
        return listOfNotNull(
            BattleTriggerRegistry.get(unit.general.specialCode),
            BattleTriggerRegistry.get(unit.general.special2Code),
        ).sortedBy { it.priority }
    }

    data class PhaseResult(
        val damage: Pair<Int, Int>,
        val logs: List<String>,
    )

    private fun executeCombatPhase(
        attacker: WarUnit,
        defender: WarUnit,
        rng: Random,
        phaseNumber: Int = 0,
        isVsCity: Boolean = false,
    ): PhaseResult {
        attacker.beginPhase()
        defender.beginPhase()

        val attackerTriggers = collectTriggers(attacker)
        val defenderTriggers = collectTriggers(defender)
        val ctx = BattleTriggerContext(
            attacker = attacker,
            defender = defender,
            rng = rng,
            phaseNumber = phaseNumber,
            isVsCity = isVsCity,
        )

        // Compute war power for each side (legacy: each side independently)
        var attackerDamage = computeWarPower(attacker, defender, rng).toInt().coerceAtLeast(1)
        var defenderDamage = computeWarPower(defender, attacker, rng).toInt().coerceAtLeast(1)

        // PRE triggers: modify chances before rolls (legacy: 시도)
        for (trigger in attackerTriggers) trigger.onPreCritical(ctx)
        for (trigger in defenderTriggers) trigger.onPreDodge(ctx)
        for (trigger in attackerTriggers) trigger.onPreMagic(ctx)
        for (trigger in defenderTriggers) trigger.onPreMagic(ctx)

        // Critical hit roll (legacy: 필살시도/발동)
        if (rng.nextDouble() < attacker.criticalChance + ctx.criticalChanceBonus) {
            attackerDamage = (attackerDamage * 1.5).toInt()
            ctx.criticalActivated = true
            // POST critical (legacy: 필살발동)
            for (trigger in attackerTriggers) trigger.onPostCritical(ctx)
        }

        // Dodge roll (legacy: 회피시도/발동)
        if (!ctx.dodgeDisabled && rng.nextDouble() < defender.dodgeChance + ctx.dodgeChanceBonus) {
            attackerDamage = (attackerDamage * 0.3).toInt()
            ctx.dodgeActivated = true
            // POST dodge (legacy: 회피발동)
            for (trigger in defenderTriggers) trigger.onPostDodge(ctx)
        }

        // Magic/stratagem roll (legacy: 계략시도/발동/실패)
        val totalMagicChance = attacker.magicChance + ctx.magicChanceBonus
        if (totalMagicChance > 0) {
            if (rng.nextDouble() < totalMagicChance) {
                // Stratagem success (legacy: 계략발동)
                val magicDamage = (attacker.intel * 2 * attacker.magicDamageMultiplier * ctx.magicDamageMultiplier).toInt()
                attackerDamage += magicDamage
                ctx.magicActivated = true
                for (trigger in attackerTriggers) trigger.onPostMagic(ctx)
            } else {
                // Stratagem failure (legacy: 계략실패)
                ctx.magicFailed = true
                for (trigger in attackerTriggers) trigger.onMagicFail(ctx)
                if (ctx.magicFailDamage > 0) {
                    defenderDamage += ctx.magicFailDamage.toInt()
                }
            }
        }

        // Defender 반계 check (after magic resolved)
        if (ctx.magicActivated) {
            for (trigger in defenderTriggers) trigger.onPostMagic(ctx)
            if (ctx.magicReflected) {
                val reflectedDamage = (attacker.intel * 2 * attacker.magicDamageMultiplier * 0.3).toInt()
                defenderDamage += reflectedDamage
            }
        }

        // Damage calc triggers
        for (trigger in attackerTriggers) trigger.onDamageCalc(ctx)
        attackerDamage = (attackerDamage * ctx.attackMultiplier).toInt()

        // Defence multiplier from defender triggers
        for (trigger in defenderTriggers) trigger.onDamageCalc(ctx)
        if (ctx.defenceMultiplier != 1.0) {
            attackerDamage = (attackerDamage / ctx.defenceMultiplier).toInt()
        }

        ctx.attackerDamage = attackerDamage
        ctx.defenderDamage = defenderDamage

        // Apply damage
        defender.takeDamage(attackerDamage)
        attacker.takeDamage(defenderDamage)

        // Snipe wound application (legacy: 저격발동 applies wound)
        if (ctx.snipeActivated && defender is WarUnitGeneral) {
            defender.injury = (defender.injury + ctx.snipeWoundAmount).coerceAtMost(80)
        }

        // Post damage triggers (counter, morale)
        for (trigger in attackerTriggers) trigger.onPostDamage(ctx)
        for (trigger in defenderTriggers) trigger.onPostDamage(ctx)

        // Apply counter damage (legacy: 반격)
        if (ctx.counterDamageRatio > 0) {
            val counterDamage = (attackerDamage * ctx.counterDamageRatio).toInt()
            attacker.takeDamage(counterDamage)
        }

        // Apply morale boost (legacy: 사기진작)
        if (ctx.moraleBoost > 0 && attacker is WarUnitGeneral) {
            attacker.atmos = (attacker.atmos + ctx.moraleBoost).coerceAtMost(100)
        }

        // Rice consumption (generals only)
        if (attacker is WarUnitGeneral) {
            attacker.consumeRice(
                damageDealt = attackerDamage,
                isAttacker = true,
                vsCity = isVsCity,
            )
        }
        if (defender is WarUnitGeneral) {
            defender.consumeRice(
                damageDealt = defenderDamage,
                isAttacker = false,
                vsCity = isVsCity,
            )
        }

        // Morale loss
        if (defender is WarUnitGeneral) {
            defender.atmos = (defender.atmos - 3).coerceAtLeast(0)
        }
        if (attacker is WarUnitGeneral) {
            attacker.atmos = (attacker.atmos - 1).coerceAtLeast(0)
        }

        return PhaseResult(
            damage = Pair(attackerDamage, defenderDamage),
            logs = ctx.battleLogs.toList(),
        )
    }
}
