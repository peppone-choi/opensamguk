package opensamguk.gameapi.controller

import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class SimulatorController {

    @PostMapping("/simulate-battle")
    fun simulateBattle(@RequestBody body: Map<String, Any>): Map<String, Any> {
        val attackerId = body["attackerId"] as? Number ?: return mapOf("error" to "attackerId required")
        val defenderId = body["defenderId"] as? Number ?: return mapOf("error" to "defenderId required")

        return mapOf(
            "attackerId" to attackerId,
            "defenderId" to defenderId,
            "winner" to if ((attackerId.toInt() % 2) == 1) "attacker" else "defender",
            "damageDealt" to (100..500).random(),
            "damageReceived" to (50..300).random(),
            "turns" to (3..10).random(),
            "log" to listOf("전투 시작", "첫 공격", "반격", "전투 종료"),
        )
    }
}
