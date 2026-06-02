package opensamguk.gameapi.dto

import opensamguk.infra.entity.CityEntity
import opensamguk.infra.entity.GeneralEntity
import opensamguk.infra.entity.NationEntity

data class MyPageDto(
    val general: GeneralEntity,
    val nation: NationEntity?,
    val city: CityEntity?,
    val turn: Int,
    val year: Int,
    val month: Int,
)