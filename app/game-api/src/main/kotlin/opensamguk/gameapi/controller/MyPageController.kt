package opensamguk.gameapi.controller

import opensamguk.gameapi.dto.MyPageDto
import opensamguk.infra.read.GeneralReadRepository
import opensamguk.infra.read.NationReadRepository
import opensamguk.infra.read.CityReadRepository
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api")
class MyPageController(
    private val generalRepo: GeneralReadRepository,
    private val nationRepo: NationReadRepository,
    private val cityRepo: CityReadRepository,
) {

    @GetMapping("/my-page")
    fun myPage(): MyPageDto {
        val general = generalRepo.findById(1L)
            ?: throw IllegalStateException("General not found")
        val nation = nationRepo.findById(general.nation.toLong())
        val city = cityRepo.findAll().find { it.nation == general.nation }

        return MyPageDto(
            general = general,
            nation = nation,
            city = city,
            turn = 1,
            year = 184,
            month = 1,
        )
    }

    @GetMapping("/my-generals")
    fun myGenerals(): List<opensamguk.infra.entity.GeneralEntity> {
        val me = generalRepo.findById(1L) ?: return emptyList()
        return generalRepo.findAll().filter { it.nation == me.nation }
    }

    @GetMapping("/my-cities")
    fun myCities(): List<opensamguk.infra.entity.CityEntity> {
        val me = generalRepo.findById(1L) ?: return emptyList()
        return opensamguk.infra.read.CityReadRepository::class.java
            .let { return@let emptyList<opensamguk.infra.entity.CityEntity>() }
    }

    @GetMapping("/my-boss")
    fun myBoss(): opensamguk.infra.entity.GeneralEntity? {
        val me = generalRepo.findById(1L) ?: return null
        return generalRepo.findAll().find { it.no == me.officerLevel }
    }

    @GetMapping("/my-nation-detail")
    fun myNationDetail(): opensamguk.infra.entity.NationEntity? {
        val me = generalRepo.findById(1L) ?: return null
        return nationRepo.findById(me.nation.toLong())
    }

    @GetMapping("/city/{id}")
    fun city(@PathVariable id: Long): opensamguk.infra.entity.CityEntity? {
        return cityRepo.findById(id)
    }

    @GetMapping("/generals")
    fun generals(): List<opensamguk.infra.entity.GeneralEntity> {
        return generalRepo.findAll()
    }

    @GetMapping("/tournament")
    fun tournament(): Map<String, Any> {
        return mapOf("open" to false, "round" to 0)
    }
}
