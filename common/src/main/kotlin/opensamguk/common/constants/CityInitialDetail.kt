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
    /**
     * 화면·로그에 적을 이름. `name` 이 식별자라면 이쪽은 표기다 — 「장안(京兆尹)」 대 「장안현」.
     *
     * 「로그와 맵의 현 이름을 같게 만들어」(2026-09-11). 지도는 web/shared/src/iso/cityName.ts
     * 가 縣을 붙이고 한정자를 떼어 그려 왔는데, 서버 로그는 [name] 을 그대로 써서 같은 城이
     * 두 이름으로 불렸다. 값은 생성기가 계산해 [CityConst.RawCity] 로 싣는다.
     *
     * 기본값이 [name] 인 이유: che·han·han-780 표는 생성기 입력이 gitignored 라 다시 낼 수
     * 없다. 그 표들은 13 개 인자 그대로 두고, 표기가 따로 없으면 식별자를 그대로 쓴다.
     */
    val displayName: String = name,
)
