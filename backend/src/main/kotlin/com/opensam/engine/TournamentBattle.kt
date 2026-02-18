package com.opensam.engine

import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.random.Random

object TournamentBattle {
    const val TOURNAMENT_TOTAL = 0
    const val TOURNAMENT_LEADERSHIP = 1
    const val TOURNAMENT_STRENGTH = 2
    const val TOURNAMENT_INTEL = 3

    data class TournamentStats(
        val leadership: Double,
        val strength: Double,
        val intel: Double,
    )

    data class TournamentParticipant(
        val id: Long,
        val name: String,
        val stats: TournamentStats,
        val level: Int,
    )

    data class TournamentBattleContext(
        val openYear: Int,
        val openMonth: Int,
        val stage: Int,
        val phase: Int,
        val matchIndex: Int,
    )

    data class TournamentBattleInput(
        val type: Int,
        val battleType: Int,
        val attacker: TournamentParticipant,
        val defender: TournamentParticipant,
        val context: TournamentBattleContext,
        val baseSeed: String,
    )

    data class TournamentBattleLogEntry(
        val phase: Int,
        val attackerEnergy: Int,
        val defenderEnergy: Int,
        val attackerDamage: Int,
        val defenderDamage: Int,
        val text: String,
    )

    data class TournamentTotalDamage(
        val attacker: Int,
        val defender: Int,
    )

    data class TournamentBattleResult(
        val winnerId: Long?,
        val loserId: Long?,
        val draw: Boolean,
        val rounds: Int,
        val totalDamage: TournamentTotalDamage,
        val log: List<String>,
        val logEntries: List<TournamentBattleLogEntry>,
    )

    fun resolveTournamentBattle(input: TournamentBattleInput): TournamentBattleResult {
        val attacker = input.attacker
        val defender = input.defender

        val attackerStat = resolveTournamentStat(input.type, attacker.stats)
        val defenderStat = resolveTournamentStat(input.type, defender.stats)

        val rng = createTournamentRandom(
            baseSeed = input.baseSeed,
            context = input.context,
            attackerId = attacker.id,
            defenderId = defender.id,
        )

        val energyBaseAttacker = round(attackerStat * getLogRatio(attacker.level, defender.level) * 10)
        val energyBaseDefender = round(defenderStat * getLogRatio(attacker.level, defender.level) * 10)
        var energyAttacker = energyBaseAttacker
        var energyDefender = energyBaseDefender

        val maxTurns = if (input.battleType == 0) 10 else 100
        val log = mutableListOf<String>()
        val logEntries = mutableListOf<TournamentBattleLogEntry>()

        log.add("<S>●</> <Y>${attacker.name}</> <C>($energyBaseAttacker)</> vs <C>($energyBaseDefender)</> <Y>${defender.name}</>")

        var totalDamageAttacker = 0
        var totalDamageDefender = 0
        var selected = 2

        for (phase in 1..maxTurns) {
            val baseDamageAttacker = round((defenderStat * (rng.nextInt(0, 22) + 90)) / 130)
            val baseDamageDefender = round((attackerStat * (rng.nextInt(0, 22) + 90)) / 130)
            var damageAttacker = baseDamageAttacker
            var damageDefender = baseDamageDefender

            if (attackerStat >= rng.nextInt(0, 101).toDouble()) {
                damageDefender += round((attackerStat * (rng.nextInt(0, 42) + 10)) / 130)
            }
            if (defenderStat >= rng.nextInt(0, 101).toDouble()) {
                damageAttacker += round((defenderStat * (rng.nextInt(0, 42) + 10)) / 130)
            }

            var criticalAttacker = false
            var criticalDefender = false
            var factorAttacker = 1
            var factorDefender = 1

            if (
                energyBaseAttacker / 5 > energyAttacker &&
                damageAttacker > damageDefender &&
                attackerStat >= rng.nextInt(0, 301).toDouble()
            ) {
                factorDefender = round((rng.nextInt(0, 302) + 200) / 100.0)
                criticalAttacker = true
                log.add("<S>●</> <Y>${attacker.name}</>의 분노의 일격!")
            }
            if (
                energyBaseDefender / 5 > energyDefender &&
                damageDefender > damageAttacker &&
                defenderStat >= rng.nextInt(0, 301).toDouble()
            ) {
                factorAttacker = round((rng.nextInt(0, 302) + 200) / 100.0)
                criticalDefender = true
                log.add("<S>●</> <Y>${defender.name}</>의 분노의 일격!")
            }

            damageAttacker = round(damageAttacker * factorAttacker)
            damageDefender = round(damageDefender * factorDefender)

            if (phase == 1) {
                if (attackerStat * 0.9 > defenderStat && attackerStat >= rng.nextInt(0, 401).toDouble()) {
                    damageDefender += round((attackerStat * (rng.nextInt(0, 32) + 70)) / 100)
                }
                if (defenderStat * 0.9 > attackerStat && defenderStat >= rng.nextInt(0, 401).toDouble()) {
                    damageAttacker += round((defenderStat * (rng.nextInt(0, 32) + 70)) / 100)
                }
            } else {
                if (!criticalAttacker && attackerStat >= rng.nextInt(0, 1001).toDouble()) {
                    damageDefender += round((attackerStat * (rng.nextInt(0, 32) + 20)) / 100)
                }
                if (!criticalDefender && defenderStat >= rng.nextInt(0, 1001).toDouble()) {
                    damageAttacker += round((defenderStat * (rng.nextInt(0, 32) + 20)) / 100)
                }
            }

            damageAttacker = clampPositive(round(damageAttacker), 0)
            damageDefender = clampPositive(round(damageDefender), 0)

            energyAttacker -= damageAttacker
            energyDefender -= damageDefender

            totalDamageAttacker += damageAttacker
            totalDamageDefender += damageDefender

            val entryText =
                "<S>●</> ${phase.toString().padStart(2, '0')}合 : " +
                    "<C>${round(energyAttacker).toString().padStart(3, '0')}</>" +
                    "<span class=\"ev_highlight\">(-${round(damageAttacker).toString().padStart(3, '0')})</span>" +
                    " vs " +
                    "<span class=\"ev_highlight\">(-${round(damageDefender).toString().padStart(3, '0')})</span>" +
                    "<C>${round(energyDefender).toString().padStart(3, '0')}</>"

            log.add(entryText)
            logEntries.add(
                TournamentBattleLogEntry(
                    phase = phase,
                    attackerEnergy = round(energyAttacker),
                    defenderEnergy = round(energyDefender),
                    attackerDamage = damageAttacker,
                    defenderDamage = damageDefender,
                    text = entryText,
                )
            )

            if (energyAttacker <= 0 && energyDefender <= 0) {
                if (input.battleType == 0) {
                    selected = 2
                    break
                }
                selected = if (energyAttacker > energyDefender) 0 else 1
                break
            }
            if (energyAttacker <= 0) {
                selected = 1
                break
            }
            if (energyDefender <= 0) {
                selected = 0
                break
            }
        }

        when (selected) {
            0 -> log.add("<S>●</> <Y>${attacker.name}</> <S>승리</>!")
            1 -> log.add("<S>●</> <Y>${defender.name}</> <S>승리</>!")
            else -> log.add("<S>●</> <Y>${attacker.name}</> <S>무승부</>!")
        }

        val winnerId = when (selected) {
            0 -> attacker.id
            1 -> defender.id
            else -> null
        }
        val loserId = when (selected) {
            0 -> defender.id
            1 -> attacker.id
            else -> null
        }

        return TournamentBattleResult(
            winnerId = winnerId,
            loserId = loserId,
            draw = selected == 2,
            rounds = logEntries.size,
            totalDamage = TournamentTotalDamage(attacker = totalDamageAttacker, defender = totalDamageDefender),
            log = log,
            logEntries = logEntries,
        )
    }

    private fun createTournamentRandom(
        baseSeed: String,
        context: TournamentBattleContext,
        attackerId: Long,
        defenderId: Long,
    ): Random {
        val seedKey = listOf(
            "Tournament",
            "open:${context.openYear}-${context.openMonth}",
            "stage:${context.stage}",
            "phase:${context.phase}",
            "match:${context.matchIndex}",
            "participant:0",
            "extra:${attackerId}:${defenderId}",
            "seed:$baseSeed",
        ).joinToString("|")

        return DeterministicRng.create(baseSeed, seedKey)
    }

    private fun clampPositive(value: Int, fallback: Int = 0): Int {
        return if (value.toDouble().isFinite()) value else fallback
    }

    private fun round(value: Double): Int = value.roundToInt()

    private fun round(value: Int): Int = value

    private fun getLogRatio(lvl1: Int, lvl2: Int): Double {
        return if (lvl1 >= lvl2) {
            1 + log10(1 + lvl1 - lvl2.toDouble()) / 10
        } else {
            1 - log10(1 + lvl2 - lvl1.toDouble()) / 10
        }
    }

    private fun resolveTournamentStat(type: Int, stats: TournamentStats): Double {
        return when (type) {
            TOURNAMENT_LEADERSHIP -> stats.leadership
            TOURNAMENT_STRENGTH -> stats.strength
            TOURNAMENT_INTEL -> stats.intel
            else -> (stats.leadership + stats.strength + stats.intel) * (7.0 / 15.0)
        }
    }

    private fun Random.nextInt(minInclusive: Int, maxExclusive: Int): Int {
        if (maxExclusive - minInclusive <= 1) {
            return minInclusive
        }
        return nextInt(minInclusive, maxExclusive)
    }
}
