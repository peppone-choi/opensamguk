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

        // Phase 1: Fight each defender general
        for (defender in sortedDefenders) {
            if (!attacker.continueWar()) {
                attackerWon = false
                break
            }
            if (!defender.isAlive) continue

            val phaseResult = executeCombatPhase(attacker, defender, rng)
            totalAttackerDamage += phaseResult.first
            totalDefenderDamage += phaseResult.second

            logs.add("<Y>${attacker.name}</> vs <Y>${defender.name}</> - " +
                "공격 피해: ${phaseResult.first}, 방어 피해: ${phaseResult.second}")

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

            // No round cap - legacy has no siege round limit
            while (attacker.continueWar() && cityUnit.isAlive) {
                val phaseResult = executeCombatPhase(attacker, cityUnit, rng)
                totalAttackerDamage += phaseResult.first
                totalDefenderDamage += phaseResult.second

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

        // Apply injury chance (5%)
        if (rng.nextDouble() < 0.05 && attacker.isAlive) {
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

        // Atmos/train modifiers (legacy: warPower *= atmos / opposeTrain)
        warPower *= attacker.atmos / 100.0
        warPower /= maxOf(1.0, defender.train / 100.0)

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

    private fun collectTriggers(unit: WarUnit): List<BattleTrigger> {
        if (unit !is WarUnitGeneral) return emptyList()
        return listOfNotNull(
            BattleTriggerRegistry.get(unit.general.specialCode),
            BattleTriggerRegistry.get(unit.general.special2Code),
        ).sortedBy { it.priority }
    }

    private fun executeCombatPhase(attacker: WarUnit, defender: WarUnit, rng: Random): Pair<Int, Int> {
        attacker.beginPhase()
        defender.beginPhase()

        val attackerTriggers = collectTriggers(attacker)
        val defenderTriggers = collectTriggers(defender)
        val ctx = BattleTriggerContext(attacker = attacker, defender = defender, rng = rng)

        // Compute war power for each side (legacy: each side independently)
        var attackerDamage = computeWarPower(attacker, defender, rng).toInt().coerceAtLeast(1)
        var defenderDamage = computeWarPower(defender, attacker, rng).toInt().coerceAtLeast(1)

        // PRE triggers: modify chances before rolls
        for (trigger in attackerTriggers) trigger.onPreCritical(ctx)
        for (trigger in defenderTriggers) trigger.onPreDodge(ctx)
        for (trigger in attackerTriggers) trigger.onPreMagic(ctx)
        for (trigger in defenderTriggers) trigger.onPreMagic(ctx)

        // Critical hit roll
        if (rng.nextDouble() < attacker.criticalChance + ctx.criticalChanceBonus) {
            attackerDamage = (attackerDamage * 1.5).toInt()
            ctx.criticalActivated = true
            // POST critical
            for (trigger in attackerTriggers) trigger.onPostCritical(ctx)
        }

        // Dodge roll
        if (!ctx.dodgeDisabled && rng.nextDouble() < defender.dodgeChance + ctx.dodgeChanceBonus) {
            attackerDamage = (attackerDamage * 0.3).toInt()
            ctx.dodgeActivated = true
            // POST dodge
            for (trigger in defenderTriggers) trigger.onPostDodge(ctx)
        }

        // Magic/tactics roll
        if (rng.nextDouble() < attacker.magicChance + ctx.magicChanceBonus) {
            val magicDamage = (attacker.intel * 2 * attacker.magicDamageMultiplier).toInt()
            attackerDamage += magicDamage
            ctx.magicActivated = true
            // POST magic
            for (trigger in attackerTriggers) trigger.onPostMagic(ctx)
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

        ctx.attackerDamage = attackerDamage
        ctx.defenderDamage = defenderDamage

        // Apply damage
        defender.takeDamage(attackerDamage)
        attacker.takeDamage(defenderDamage)

        // Rice consumption (generals only)
        if (attacker is WarUnitGeneral) attacker.consumeRice(attackerDamage)
        if (defender is WarUnitGeneral) defender.consumeRice(defenderDamage)

        // Morale loss
        if (defender is WarUnitGeneral) {
            defender.atmos = (defender.atmos - 3).coerceAtLeast(0)
        }
        if (attacker is WarUnitGeneral) {
            attacker.atmos = (attacker.atmos - 1).coerceAtLeast(0)
        }

        return Pair(attackerDamage, defenderDamage)
    }
}
