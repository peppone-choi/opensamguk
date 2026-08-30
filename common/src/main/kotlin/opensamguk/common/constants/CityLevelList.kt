package opensamguk.common.constants

/**
 * Faithful port of `legacy/devsam-core/hwe/func_gamerule.php` getCityLevelList() (lines 30-42).
 *
 * City-level label map. Per project memory: lv=4 "이" is 이민족-only; han county-seats use lv=5 "소".
 * Han-world extends the legacy eight ranks with 京 and the 後漢 county distinction from 百官志:
 * 「萬戶以上為令，不滿為長」. Used by API display and unit/city constraints.
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
    9 to "경",
    10 to "영현",
    11 to "장현",
)
