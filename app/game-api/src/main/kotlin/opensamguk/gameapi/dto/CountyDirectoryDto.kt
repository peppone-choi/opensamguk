package opensamguk.gameapi.dto

/** Current-state gross production forecast per game month, not an already credited receipt. */
data class CountyIncomeDto(val money: Long, val grain: Long)
data class CountyDirectoryCommandery(val id: String, val name: String)
data class CountyDirectoryRow(
    val cityId: Int,
    val name: String,
    val commanderyId: String,
    val visibility: String,
    val income: CountyIncomeDto?,
)
data class CountyDirectoryResponse(
    val status: String,
    val scope: String,
    val commandery: CountyDirectoryCommandery? = null,
    val period: String = "GAME_MONTH",
    val basis: String = "CURRENT_STATE_FORECAST",
    val stamp: StampDto? = null,
    val counties: List<CountyDirectoryRow> = emptyList(),
)
