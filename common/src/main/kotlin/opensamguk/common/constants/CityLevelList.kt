package opensamguk.common.constants

/**
 * Faithful port of `legacy/devsam-core/hwe/func_gamerule.php` getCityLevelList() (lines 30-42).
 *
 * City-level label map. Per project memory: lv=4 "이" is 이민족-only; han county-seats use lv=5 "소".
 * Used by UnitConstraint (ReqCitiesWithCityLevel/ReqHighLevelCities) and CityConst level resolution.
 */
fun getCityLevelList(): Map<Int, String> = linkedMapOf(
    1 to "수",
    2 to "진",
    3 to "관",
    4 to "이",
    5 to "소",
    6 to "중",
    7 to "대",
    8 to "특",
)
