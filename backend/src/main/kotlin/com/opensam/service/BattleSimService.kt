package com.opensam.service

import com.opensam.dto.SimulateRequest
import com.opensam.dto.SimulateResult
import org.springframework.stereotype.Service

@Service
class BattleSimService {
    fun simulate(request: SimulateRequest): SimulateResult {
        val atkPower = request.attacker.strength * 2 + request.attacker.leadership +
            request.attacker.crew / 100 + request.attacker.train / 2
        val defPower = request.defender.strength * 2 + request.defender.leadership +
            request.defender.crew / 100 + request.defender.train / 2 +
            request.defenderCity.def / 50 + request.defenderCity.wall / 50

        val logs = mutableListOf<String>()
        logs.add("=== 전투 시뮬레이션 ===")
        logs.add("공격: ${request.attacker.name} (전투력: $atkPower)")
        logs.add("방어: ${request.defender.name} (전투력: $defPower)")

        var atkHp = request.attacker.crew
        var defHp = request.defender.crew
        var round = 1

        while (atkHp > 0 && defHp > 0 && round <= 20) {
            val atkDmg = (atkPower * (80 + (Math.random() * 40).toInt()) / 100).coerceAtLeast(1)
            val defDmg = (defPower * (80 + (Math.random() * 40).toInt()) / 100).coerceAtLeast(1)
            defHp -= atkDmg
            atkHp -= defDmg
            logs.add("라운드 $round: 공격 -$defDmg → 방어 HP $defHp | 방어 -$atkDmg → 공격 HP $atkHp")
            round++
        }

        val winner = when {
            atkHp <= 0 && defHp <= 0 -> "무승부"
            defHp <= 0 -> "공격측 승리"
            atkHp <= 0 -> "방어측 승리"
            else -> "교착 상태"
        }
        logs.add("결과: $winner")

        return SimulateResult(
            winner = winner,
            attackerRemaining = atkHp.coerceAtLeast(0),
            defenderRemaining = defHp.coerceAtLeast(0),
            rounds = round - 1,
            logs = logs,
        )
    }
}
