package opensamguk.gameapi.controller

import opensamguk.infra.read.GeneralReadRepository
import opensamguk.infra.read.NationReadRepository
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/rankings")
class RankingController(
    private val generalRepo: GeneralReadRepository,
    private val nationRepo: NationReadRepository,
) {

    @GetMapping("/best-generals")
    fun bestGenerals() = generalRepo.findAll()
        .sortedByDescending { it.leadership + it.strength + it.intel }
        .take(50)

    @GetMapping("/emperor")
    fun emperor(): Map<String, Any> {
        val top = generalRepo.findAll().maxByOrNull { it.experience }
        return mapOf("emperor" to (top ?: ""))
    }

    @GetMapping("/emperor/{id}")
    fun emperorDetail(@PathVariable id: Long) = generalRepo.findById(id)

    @GetMapping("/generals")
    fun allGenerals() = generalRepo.findAll()

    @GetMapping("/kingdoms")
    fun kingdoms() = nationRepo.findAll().sortedByDescending { it.power }

    @GetMapping("/npcs")
    fun npcs() = generalRepo.findAll().filter { it.owner == 0 }

    @GetMapping("/hall-of-fame")
    fun hallOfFame(): List<Map<String, Any>> = emptyList()

    @GetMapping("/traffic")
    fun traffic(): Map<String, Any> = mapOf(
        "totalPlayers" to 0,
        "activeToday" to 0,
        "peakConcurrent" to 0,
    )
}
