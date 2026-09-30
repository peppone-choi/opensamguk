package opensamguk.gameapi.dto

/** Null values denote unavailable or unauthorized information, never a synthetic zero. */
data class DirectoryPortrait(val picture: String?, val imageServer: Int)
data class DirectoryAffiliation(val nationId: Int, val name: String, val color: String)
data class DirectoryStats(val leadership: Int, val strength: Int, val intel: Int, val politics: Int, val charm: Int)
data class DirectoryAptitudes(val command: Int, val administration: Int, val strategy: Int, val envoy: Int)
data class DirectoryBond(val kind: String, val targetId: String)
data class DirectoryPerson(
    val generalId: Int,
    val name: String,
    val portrait: DirectoryPortrait,
    val affiliation: DirectoryAffiliation?,
    val role: String?,
    val lordGeneralId: Int?,
    val stats: DirectoryStats?,
    val aptitudes: DirectoryAptitudes?,
    val locationCityId: Int?,
    val bonds: List<DirectoryBond>?,
)
data class PeoplePage(val status: String, val people: List<DirectoryPerson> = emptyList(), val nextCursor: String? = null)
data class SummaryNation(val id: Int, val name: String, val color: String)
data class SummaryLord(val generalId: Int, val name: String, val portrait: DirectoryPortrait)
data class SummaryTroops(val city: Long?, val bugok: Long?)
data class CampaignNationSummary(
    val status: String,
    val nation: SummaryNation? = null,
    val lord: SummaryLord? = null,
    val capitalCityId: Int? = null,
    val countyCount: Int? = null,
    /** Number of persisted retainer cards whose master belongs to this nation. */
    val retinueCount: Int? = null,
    val stockTotal: StockDto? = null,
    val population: Long? = null,
    val troops: SummaryTroops? = null,
)
data class AdminNationDirectory(val status: String, val nations: List<CampaignNationSummary> = emptyList())
