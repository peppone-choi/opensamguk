package opensamguk.common.constants

/**
 * Faithful port of `legacy/devsam-core/hwe/sammo/CityInitialDetail.php`.
 *
 * Values as resolved by CityConstBase::_generate(): level/region resolved to ints via the maps;
 * stats (population..wall) ×100; posX/posY NOT scaled; path is connectedId->name. Per project
 * memory: lv=4 "이" = 이민족-only; han county-seats use lv=5 "소" — do not collapse them.
 */
data class CityInitialDetail(
    val id: Int,
    val name: String,
    val level: Int,
    val population: Int,
    val agriculture: Int,
    val commerce: Int,
    val security: Int,
    val defence: Int,
    val wall: Int,
    val region: Int,
    val posX: Int,
    val posY: Int,
    val path: Map<Int, String>,
)
