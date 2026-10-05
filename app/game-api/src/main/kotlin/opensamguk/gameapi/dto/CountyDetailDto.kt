package opensamguk.gameapi.dto

/** The flat county core contract. A null value is unknown or withheld, never a fabricated zero. */
data class CountyGarrisonDto(val troops: Int, val training: Int, val morale: Int)

data class CountyDetailDto(
    val status: String,
    val cityId: Int,
    val name: String? = null,
    val nameCh: String? = null,
    val level: Int? = null,
    val levelLabel: String? = null,
    val commandery: CountyDirectoryCommandery? = null,
    val owner: DirectoryAffiliation? = null,
    val visibility: String? = null,
    val intelAgeTurns: Int? = null,
    val population: Int? = null,
    val defense: Int? = null,
    val specialties: List<SpecialtyDto>? = null,
    val garrison: CountyGarrisonDto? = null,
    val income: CountyIncomeDto? = null,
)
