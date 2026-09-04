package opensamguk.logic.world

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.CityConst.RawCity
import opensamguk.common.constants.CityInitialDetail
import opensamguk.common.constants.HanCityConst
import opensamguk.common.constants.HanGateIndex
import opensamguk.common.constants.Han780V1CityConst
import opensamguk.common.constants.Han780V1GateIndex
import opensamguk.common.constants.HanWorldV2CityConst
import opensamguk.common.constants.HanWorldV2GateIndex

/**
 * F6 Task FM1 — per-map CityConst variant registry, keyed by `mapName`.
 *
 * Port target: `Scenario.php:494-527` buildConf (`copy("$mapPath/$mapName.php", CityConst.php)`) +
 * the map files `scenario/map/{che,miniche}.php`. RESOLVED decision (plan §"Per-map CityConst
 * override"): per-map override = whole-FILE class substitution by `mapName`, NOT a per-city field
 * patch (scenario `cities[]` is DEAD/vestigial). `che.php` is an EMPTY subclass — the canonical
 * 94-city data lives in `CityConstBase`, so the 'che' Kotlin variant simply delegates to the GREEN
 * [CityConst] base object (canonical che data stays in the base, NOT an empty 'che' variant file).
 * `miniche.php` overrides ONLY `$initCity` (78 rows) and INHERITS buildInit/levelMap/regionMap and
 * the `_generate()` algorithm — modeled here by running the 78 rows through the SAME GREEN
 * [CityConst.generateCities] algorithm.
 *
 * Each variant is immutable and selected at scenario-load, then threaded as a RUNTIME config (NOT a
 * compile-time singleton) into the supply BFS / income ceilings / trade-rate / front-recalc / the
 * `level==4` invader gate. The `path` adjacency is an insertion-ordered LinkedHashMap (the BFS
 * input order is load-bearing).
 */
/**
 * 그 등급의 공백지에서 **건국·무작위수도이전**이 가능한가.
 *
 * PHP `ConstructableCity.php` 는 {5,6}(중·소)이다. che 는 등급이 1~8뿐이라 이 함수는 che 에서
 * **완전한 무변**이다 — 9 이상이 존재하지 않는다.
 *
 * han 은 郡治 위에 京(9), 아래에 縣급 영현(10)·장현(11)을 얹었다. 縣급을 빼면 사료상 郡을
 * 領有하지 않고 縣 하나만 가진 세력(1060 유비 = 신야현)이 영영 건국을 못 한다 — 시나리오가
 * 실제로 그런 세력을 두므로 縣급도 건국지로 연다. 京(9)·대(7)·특(8)은 그대로 막힌다.
 */
fun isFoundableCityLevel(level: Int): Boolean = level in 5..6 || level >= 10

/**
 * 건국 시 공백지 수비병(city.def)을 뚫는 데 필요한 병력 비율.
 *
 * **패러티 아님 · 게임 밸런스 튜닝값.** PHP devsam/core 에는 건국 전투 판정 자체가 없다(공백지에 서 있기만
 * 하면 건국). han 맵 전용 divergence 이므로 골든/RNG/로그와 무관하며, 밸런싱은 이 값 하나만 고치면 된다.
 *
 * 값을 2.0 으로 잡은 근거는 **장수 한 명의 병력 상한**이다 — `RecruitAlgorithm.maxCrewOf` 가
 * `통솔 * 100` 이라 통솔 70 이면 7,000 이 최대다. 그 기준으로 필요 병력은
 * 장현 2,000(통솔 20+) · 영현 3,000(통솔 30+) · 소 4,000(통솔 40+) · 중 6,000(통솔 60+) 이 된다.
 * 0.5 였을 때는 소 郡治가 1,000(통솔 10+)이라 사실상 아무 관문도 아니었다.
 * 이 계단이 「縣부터 물고 郡治로 넓힌다」는 동선을 만든다.
 */
const val FOUND_ASSAULT_RATIO: Double = 2.0

/** [CityConstVariant.mapName] of the han map — the ONLY map the founding assault applies to. */
const val HAN_MAP_NAME: String = "han"
const val HAN_780_V1_MAP_NAME: String = "han-780-v1"
const val HAN_WORLD_V2_MAP_NAME: String = "han-world-v2"

fun isHanMapName(mapName: Any?): Boolean =
    mapName == HAN_MAP_NAME || mapName == HAN_780_V1_MAP_NAME || mapName == HAN_WORLD_V2_MAP_NAME

fun foundingDefenseAfterCapture(mapName: Any?, currentDefense: Int, postDefense: Int): Int =
    if (isHanMapName(mapName)) postDefense else currentDefense

/**
 * 건국에 필요한 돌파 병력 = `ceil(city.def * FOUND_ASSAULT_RATIO)`.
 *
 * **han 전용**이다. che·miniche 는 항상 0 을 돌려주므로 판정·차감·def 초기화가 전부 사라진다
 * (패러티 골든 무변). 수비병이 없는 城(def<=0)도 0 = 판정 없음.
 */
fun foundAssaultCrewCost(mapName: String?, cityDefense: Int): Int =
    if (!isHanMapName(mapName) || cityDefense <= 0) 0
    else kotlin.math.ceil(cityDefense * FOUND_ASSAULT_RATIO).toInt()

sealed interface CityConstVariant {
    val mapName: String
    fun all(): Map<Int, CityInitialDetail>
    fun byId(id: Int): CityInitialDetail?
    fun byName(name: String): CityInitialDetail?
    /** Mirrors CityConstBase::byRegion (the last-wins quirk: LAST city encountered per region). */
    fun byRegion(region: Int): CityInitialDetail?
    fun regionIdByName(name: String): Int?

    /**
     * 그 城이 가진 **게이트 키**(漢字) 집합 — 州 · 郡 · 治所 縣 · 이민족 거점.
     * han 병종의 `ReqRegions`/`ForbidRegions` 가 지역 라벨이 아니라 漢字 4축을 쓰기 때문에 필요하다.
     * 기본은 빈 집합이며(che·miniche), 그 경우 판정은 기존 [regionIdByName] 경로만 탄다.
     */
    fun gateKeys(cityId: Int): Set<String> = emptySet()

    /**
     * 그 城이 **국가 등급 산정**에 세어지는가 (`UpdateNationLevel`).
     *
     * PHP 는 `WHERE LEVEL>=4` 하나뿐이다(`UpdateNationLevel.php:34-37`). che 는 등급이 1~8이라
     * 수·진·관(1~3) 25성이 빠지고 **69성만** 세어진다 — 이 기본 구현이 그 규칙 그대로다.
     *
     * han 은 이 필터가 무력화된다. 縣을 영현(10)·장현(11)로 매겼기 때문에 605개 縣이 전부
     * `>=4` 를 통과해 774성 전부가 세어지고, 94성 세계에 맞춰 얼어 있는 문턱
     * ([opensamguk.common.constants.GameConst.nationLevelByCityCnt09] = 1/2/5/8/11/16/21/28/36)을
     * 즉시 넘겨버린다. 실측: 1010 황건적이 104성이라 **첫 월간틱에 천자**가 된다.
     * han 변형은 郡治만 세어(4~9) 세는 대상을 172개로 되돌린다 — 필터가 원래 하려던 일
     * (작은 거점은 빼고 실질 거점만 센다)을 han 등급 축으로 옮긴 것이다.
     */
    fun countsForNationLevel(level: Int): Boolean = level >= 4

    /**
     * 국가 등급 0~9 의 城 수 문턱. 기본은 PHP 패러티 테이블
     * ([opensamguk.common.constants.GameConst.nationLevelByCityCnt09] 3번째 열) 그대로다.
     *
     * han 은 [countsForNationLevel] 로 세는 대상이 69(che) → 172(郡治) 로 2.5배가 되므로
     * 문턱도 같이 올리지 않으면 등급이 통째로 앞당겨진다. han 변형이 이 값을 재정의한다.
     */
    val nationLevelCityThresholds: List<Int>
        get() = opensamguk.common.constants.GameConst.nationLevelByCityCnt09.map { (it[2] as Number).toInt() }

    /** Inherited from the base ($buildInit/$buildInitCommon are NOT overridden by the map files). */
    val buildInit: Map<String, Map<String, Int>>
    val buildInitCommon: Map<String, Int>

    /**
     * 3축 등급(爵·官·天子)을 이 맵이 지원하는가. 기본 false = 지금 단일 사다리 그대로.
     * che 는 PHP 패러티 경로라 영원히 false 다.
     */
    val supportsThreeAxisRank: Boolean get() = false

    /**
     * 州(region) → 그 州에 속한 **郡治 城의 총 수**.
     * 지방관(太守/刺史/州牧) 판정이 「州 장악도」이므로 분모가 필요하다.
     * 기본은 빈 맵 = 3축 미지원.
     */
    val seatCountByProvince: Map<Int, Int> get() = emptyMap()

    /**
     * 국호·국기 변경이 풀리는 spine level. che 는 PHP 패러티(7 = 황제)를 그대로 쓰고,
     * han 은 公(5)부터 국호를 쓴다(스펙 2026-08-19-nation-rank-three-axis.md §10.3).
     */
    val nationTitleUnlockLevel: Int get() = 7
}

/** 'che' = the canonical base — delegates directly to the GREEN [CityConst] object (zero delta). */
internal object CheCityConst : CityConstVariant {
    override val mapName: String = "che"
    override fun all(): Map<Int, CityInitialDetail> = CityConst.all()
    override fun byId(id: Int): CityInitialDetail? = CityConst.byId(id)
    override fun byName(name: String): CityInitialDetail? = CityConst.byName(name)
    override fun byRegion(region: Int): CityInitialDetail? = CityConst.byRegion(region)
    override fun regionIdByName(name: String): Int? = CityConst.regionMap[name] as? Int
    override val buildInit: Map<String, Map<String, Int>> get() = CityConst.buildInit
    override val buildInitCommon: Map<String, Int> get() = CityConst.buildInitCommon
}

/**
 * A map variant whose ONLY override is `$initCity`; everything else (buildInit/levelMap/regionMap +
 * the `_generate()` algorithm) is inherited from the base. Built by running the variant's raw rows
 * through [CityConst.generateCities] (the SAME byte-identical generation as the base).
 */
internal class InitCityOverrideVariant(
    override val mapName: String,
    rawRows: List<RawCity>,
) : CityConstVariant {
    private val generated = CityConst.generateCities(rawRows)
    override fun all(): Map<Int, CityInitialDetail> = generated.constID
    override fun byId(id: Int): CityInitialDetail? = generated.constID[id]
    override fun byName(name: String): CityInitialDetail? = generated.constName[name]
    override fun byRegion(region: Int): CityInitialDetail? = generated.constRegion[region]
    override fun regionIdByName(name: String): Int? = CityConst.regionMap[name] as? Int
    override val buildInit: Map<String, Map<String, Int>> get() = CityConst.buildInit
    override val buildInitCommon: Map<String, Int> get() = CityConst.buildInitCommon
}

/**
 * 'han' — 續漢書 郡國志 + CHGIS 격자에서 구운 178 郡 지도([HanCityConst], 생성물).
 * region 라벨이 州 이름이라 base 의 8개 라벨 대신 자기 [regionMap] 을 쓰고,
 * 게이트 키는 같은 생성기가 낸 [HanGateIndex] 에서 온다.
 */
internal class HanCityConstVariant(
    override val mapName: String,
    rawRows: List<RawCity>,
    private val gateKeysFor: (Int) -> Set<String>,
    override val nationLevelCityThresholds: List<Int>,
) : CityConstVariant {
    /** han.json `_meta.regions` 순서 그대로 — build_han_world.py 의 `ju_order`(1-based). */
    private val hanRegions: Map<Any, Any> = linkedMapOf<Any, Any>().apply {
        listOf(
            "사예", "예주", "기주", "연주", "서주", "청주", "형주",
            "양주", "익주", "량주", "병주", "유주", "교주", "동이",
        ).forEachIndexed { i, label ->
            put(label, i + 1)
            put(i + 1, label)
        }
    }

    /** Han-only generation view; the shared table also exposes these labels to API readers. */
    private val hanLevels: Map<Any, Any> =
        LinkedHashMap<Any, Any>(CityConst.levelMap).apply {
            put("경", 9); put(9, "경")
            // 治所가 아닌 縣. 續漢書 百官志 「萬戶以上為令，不滿為長」 — 令이 앉는 縣이 '영현',
            // 長이 앉는 縣이 '장현'이다. 숫자는 크기 순서가 아니라 그냥 식별자다(che 1-8 도 그렇다).
            put("영현", 10); put(10, "영현")
            put("장현", 11); put(11, "장현")
        }

    /** '경'·'영현'·'장현'의 초기값 — che 계단(def/wall +1000, pop 100k→150k)을 한 칸 더 이은 밸런스값이다. */
    private val hanBuildInit: Map<String, Map<String, Int>> =
        LinkedHashMap(CityConst.buildInit).apply {
            put("경", linkedMapOf("pop" to 200000, "agri" to 1000, "comm" to 1000,
                                 "secu" to 1000, "def" to 6000, "wall" to 6000))
            // 縣 두 등급 — 생성기(tools/scenario/build_han_world.py)의 BUILD_INIT 과 같은 값이다.
            put("영현", linkedMapOf("pop" to 50000, "agri" to 1000, "comm" to 1000,
                                   "secu" to 1000, "def" to 1500, "wall" to 1500))
            put("장현", linkedMapOf("pop" to 20000, "agri" to 500, "comm" to 500,
                                   "secu" to 500, "def" to 1000, "wall" to 1000))
        }

    private val generated = generateHanCities(rawRows)

    private fun generateHanCities(rawRows: List<RawCity>): CityConst.GeneratedCities {
        val base = CityConst.generateCities(rawRows, hanRegions, hanLevels)
        val rowsByName = rawRows.groupBy { it.name }
        val constId = LinkedHashMap<Int, CityInitialDetail>()
        val constName = LinkedHashMap<String, CityInitialDetail>()
        val constRegion = LinkedHashMap<Int, CityInitialDetail>()

        for (raw in rawRows) {
            val usedByName = HashMap<String, Int>()
            val path = LinkedHashMap<Int, String>()
            for (pathName in raw.path) {
                val named = rowsByName.getValue(pathName).filter { it.id != raw.id }
                val reciprocal = named.filter { raw.name in it.path }
                val candidates = reciprocal.ifEmpty { named }
                val occurrence = usedByName.getOrDefault(pathName, 0)
                val target = candidates.getOrElse(occurrence) { candidates.last() }
                usedByName[pathName] = occurrence + 1
                path[target.id] = pathName
            }
            val city = base.constID.getValue(raw.id).copy(path = path)
            constId[city.id] = city
            constName[city.name] = city
            constRegion[city.region] = city
        }
        return CityConst.GeneratedCities(constId, constName, constRegion)
    }
    override fun all(): Map<Int, CityInitialDetail> = generated.constID
    override fun byId(id: Int): CityInitialDetail? = generated.constID[id]
    /**
     * 이름으로 찾는다. **han 은 이름이 겹칠 수 있다** — 서로 다른 漢字가 같은 한글 독음이
     * 되는 城이 57쌍 있다(零陵郡 零陵縣·梁國 寧陵縣 둘 다 '영릉', 京兆尹·會稽郡 둘 다 '장안').
     * 겹치면 마지막 城이 이긴다. 城을 확정해 가리켜야 하는 곳(시나리오·엔진·DB)은 **id** 를 써라.
     */
    override fun byName(name: String): CityInitialDetail? = generated.constName[name]
    override fun byRegion(region: Int): CityInitialDetail? = generated.constRegion[region]
    override fun regionIdByName(name: String): Int? = hanRegions[name] as? Int

    /** 郡治(이 4 ~ 경 9)만 센다. 영현(10)·장현(11)은 郡의 하급 행정구역이라 세지 않는다. */
    override fun countsForNationLevel(level: Int): Boolean = level in 4..9

    /**
     * **패러티 아님 · han 전용 밸런스값.** 도출 규칙 하나로 전부 나온다 —
     * che 문턱을 「세는 城 전체 대비 비율」로 읽어 han 의 세는 城 수에 다시 씌웠다.
     *
     * che 는 `LEVEL>=4` 로 94성 중 **69성**을 세고, han 은 郡治만 세어 (phantom/중복 郡 노드
     * 병합·삭제 뒤) 774성 중 **172성**을 센다. 그래서 배율은 172/69 = 2.4928 다.
     * `round(che문턱 * 172 / 69)`:
     *
     * | 등급 | che | han |
     * |---|---|---|
     * | 군벌 | 2 | 5 |
     * | 주자사 | 5 | 12 |
     * | 주목 | 8 | 20 |
     * | 공 | 11 | 27 |
     * | 왕 | 16 | 40 |
     * | 황제 | 21 | 52 |
     * | 대황제 | 28 | 70 |
     * | 천자 | 36 | 90 |
     *
     * 0(방랑군)·1(호족)은 비율이 아니라 **구조**라 그대로 둔다 — 방랑군은 城 0 의 상태이고,
     * 郡治 하나를 가진 세력이 방랑군으로 남으면 안 된다.
     *
     * 이 값을 바꿔도 che 골든은 무관하다(che 는 기본 구현을 쓴다). 어떤 테스트도 이 리스트를
     * 직접 단언하지 않는다(2026-08-24 measured, `grep -rln nationLevelCityThresholds *Test.kt` = 0건).
     */
    override fun gateKeys(cityId: Int): Set<String> = gateKeysFor(cityId)
    override val buildInit: Map<String, Map<String, Int>> get() = hanBuildInit
    override val buildInitCommon: Map<String, Int> get() = CityConst.buildInitCommon

    override val supportsThreeAxisRank: Boolean = true

    /** 公(5)부터 국호·국기를 쓴다 — 侯 3등급은 아직 남의 조정 신하다(스펙 §10.3). */
    override val nationTitleUnlockLevel: Int = 5

    /**
     * 州(region) → 그 州의 郡治(=[countsForNationLevel] 참인 城) 수. [generated] 처럼 한 번만
     * 계산해 캐시한다 — 지방관(太守/刺史/州牧) 판정마다 재계산하지 않는다.
     */
    override val seatCountByProvince: Map<Int, Int> by lazy {
        generated.constID.values
            .filter { countsForNationLevel(it.level) }
            .groupingBy { it.region }
            .eachCount()
    }

    /** Current-Han facade retained for existing package-level seat callers. */
    companion object {
        fun all(): Map<Int, CityInitialDetail> = currentHan.all()
        fun countsForNationLevel(level: Int): Boolean = currentHan.countsForNationLevel(level)
        val seatCountByProvince: Map<Int, Int> get() = currentHan.seatCountByProvince
    }
}

private val currentHan = HanCityConstVariant(
    mapName = HAN_MAP_NAME,
    rawRows = HanCityConst.initCity,
    gateKeysFor = HanGateIndex::keys,
    nationLevelCityThresholds = listOf(0, 1, 5, 12, 20, 27, 40, 52, 70, 90),
)
private val legacyHan = HanCityConstVariant(
    mapName = HAN_780_V1_MAP_NAME,
    rawRows = Han780V1CityConst.initCity,
    gateKeysFor = Han780V1GateIndex::keys,
    nationLevelCityThresholds = listOf(0, 1, 5, 13, 20, 28, 41, 53, 71, 91),
)
private val hanWorldV2 = HanCityConstVariant(
    mapName = HAN_WORLD_V2_MAP_NAME,
    rawRows = HanWorldV2CityConst.initCity,
    gateKeysFor = HanWorldV2GateIndex::keys,
    nationLevelCityThresholds = listOf(0, 1, 5, 12, 20, 27, 40, 52, 70, 90),
)

object CityConstRegistry {
    const val DEFAULT_MAP_NAME = "che"

    /** 'miniche' $initCity override — `scenario/map/miniche.php`, 78 rows, faithful transcription. */
    private val minicheInitCity: List<RawCity> = listOf(
        RawCity(1, "낙양", "특", 8357, 117, 120, 100, 121, 124, "중원", 285, 176, listOf("하내", "홍농", "호로")),
        RawCity(2, "성도", "특", 6525, 123, 125, 100, 125, 123, "서촉", 30, 285, listOf("덕양", "강주")),
        RawCity(3, "건업", "특", 6386, 116, 123, 100, 115, 119, "오월", 507, 303, listOf("광릉", "합비", "오")),
        RawCity(4, "업", "특", 6205, 125, 113, 100, 117, 122, "하북", 355, 135, listOf("하내", "거록", "남피", "제남", "진류")),
        RawCity(5, "장안", "특", 5923, 116, 123, 100, 120, 118, "서북", 162, 173, listOf("안정", "오장원", "한중", "홍농")),
        RawCity(6, "허창", "특", 5876, 121, 124, 100, 117, 125, "중원", 325, 218, listOf("호로", "진류", "초", "여남", "완")),
        RawCity(7, "양양", "특", 5837, 120, 126, 100, 115, 117, "초", 259, 295, listOf("신야", "강릉", "강하")),
        RawCity(8, "시상", "대", 5252, 98, 100, 80, 99, 96, "오월", 357, 357, listOf("적벽", "여강", "단양", "상동", "장사")),
        RawCity(9, "수춘", "대", 5143, 99, 96, 80, 99, 95, "중원", 385, 270, listOf("여남", "초", "하비", "합비")),
        RawCity(10, "한중", "대", 5137, 96, 101, 80, 102, 103, "서촉", 130, 218, listOf("무도", "오장원", "장안", "상용", "자동")),
        RawCity(11, "남피", "대", 5032, 99, 101, 80, 101, 105, "하북", 410, 93, listOf("계", "북평", "평원", "업", "거록")),
        RawCity(12, "위례", "대", 4926, 100, 93, 80, 98, 103, "동이", 618, 140, listOf("평양", "북해", "웅진", "계림")),
        RawCity(13, "북평", "대", 4862, 102, 95, 80, 103, 99, "하북", 442, 53, listOf("계", "요동", "남피")),
        RawCity(14, "강릉", "대", 4850, 105, 96, 80, 95, 96, "초", 245, 330, listOf("이릉", "양양", "적벽", "장사", "무릉")),
        RawCity(15, "완", "대", 4724, 103, 100, 80, 101, 99, "중원", 275, 235, listOf("허창", "여남", "신야")),
        RawCity(16, "장사", "대", 4710, 97, 99, 80, 100, 105, "초", 258, 373, listOf("강릉", "시상", "계양", "무릉")),
        RawCity(17, "오", "중", 4355, 77, 81, 60, 77, 76, "오월", 515, 340, listOf("건업", "단양", "회계", "탐라")),
        RawCity(18, "하비", "중", 4278, 85, 83, 60, 82, 78, "중원", 460, 240, listOf("패", "북해", "광릉", "수춘")),
        RawCity(19, "복양", "중", 4185, 80, 83, 60, 82, 80, "중원", 412, 170, listOf("제남", "진류", "패")),
        RawCity(20, "웅진", "중", 4157, 77, 79, 60, 78, 80, "동이", 615, 205, listOf("위례", "계림", "탐라")),
        RawCity(21, "강주", "중", 4126, 79, 80, 60, 84, 81, "서촉", 75, 305, listOf("성도", "덕양", "영안", "주제", "월수")),
        RawCity(22, "무도", "중", 4027, 77, 84, 60, 80, 85, "서촉", 55, 191, listOf("저", "한중", "자동")),
        RawCity(23, "국내", "중", 3982, 78, 80, 60, 83, 78, "동이", 596, 48, listOf("요동", "오환", "평양")),
        RawCity(24, "진류", "중", 3957, 82, 80, 60, 80, 83, "중원", 370, 175, listOf("업", "복양", "패", "초", "허창", "호로")),
        RawCity(25, "계양", "중", 3955, 83, 80, 60, 81, 77, "초", 242, 408, listOf("영릉", "장사", "상동")),
        RawCity(26, "계림", "중", 3911, 80, 74, 60, 81, 78, "동이", 660, 195, listOf("위례", "웅진", "왜")),
        RawCity(27, "계", "중", 3885, 75, 80, 60, 78, 81, "하북", 386, 55, listOf("진양", "북평", "남피")),
        RawCity(28, "무위", "중", 3874, 77, 79, 60, 83, 80, "서북", 56, 76, listOf("강", "안정", "천수", "저")),
        RawCity(29, "제남", "중", 3831, 77, 81, 60, 84, 77, "하북", 402, 132, listOf("업", "평원", "복양")),
        RawCity(30, "남해", "중", 3803, 82, 76, 60, 80, 81, "오월", 270, 474, listOf("상동", "산월", "교지")),
        RawCity(31, "덕양", "중", 3803, 81, 84, 60, 79, 77, "서촉", 73, 276, listOf("자동", "영안", "강주", "성도")),
        RawCity(32, "하내", "중", 3736, 77, 81, 60, 81, 80, "하북", 295, 140, listOf("진양", "업", "낙양", "하동")),
        RawCity(33, "상용", "중", 3687, 78, 76, 60, 77, 81, "서촉", 190, 220, listOf("한중", "신야")),
        RawCity(34, "초", "소", 3286, 60, 62, 40, 62, 57, "중원", 375, 225, listOf("허창", "진류", "패", "수춘", "여남")),
        RawCity(35, "운남", "소", 3258, 62, 60, 40, 64, 61, "남중", 45, 405, listOf("월수", "건녕", "남만")),
        RawCity(36, "대", "소", 3256, 60, 62, 40, 57, 60, "오월", 450, 470, listOf("산월", "회계", "왜")),
        RawCity(37, "하동", "소", 3208, 60, 60, 40, 62, 55, "서북", 240, 140, listOf("흉노", "진양", "하내", "홍농")),
        RawCity(38, "무릉", "소", 3196, 58, 63, 40, 63, 58, "초", 195, 352, listOf("강릉", "장사", "영릉")),
        RawCity(39, "교지", "소", 3195, 58, 59, 40, 58, 59, "남중", 136, 480, listOf("남만", "남해")),
        RawCity(40, "단양", "소", 3183, 62, 64, 40, 58, 57, "오월", 440, 350, listOf("여강", "오", "건안", "시상")),
        RawCity(41, "영안", "소", 3153, 62, 59, 40, 58, 59, "서촉", 116, 282, listOf("덕양", "이릉", "강주")),
        RawCity(42, "북해", "소", 3146, 55, 63, 40, 63, 58, "하북", 470, 150, listOf("평원", "위례", "하비")),
        RawCity(43, "합비", "진", 998, 20, 19, 20, 39, 41, "중원", 420, 294, listOf("수춘", "건업", "여강")),
        RawCity(44, "이릉", "진", 968, 18, 19, 20, 39, 41, "초", 188, 275, listOf("영안", "강릉")),
        RawCity(45, "건녕", "소", 3082, 58, 59, 40, 63, 56, "남중", 85, 390, listOf("주제", "장가", "운남")),
        RawCity(46, "강하", "소", 3074, 55, 56, 40, 57, 60, "초", 320, 299, listOf("양양", "적벽", "여강")),
        RawCity(47, "진양", "소", 3074, 56, 59, 40, 64, 59, "하북", 310, 75, listOf("흉노", "하동", "하내", "거록", "계")),
        RawCity(48, "평원", "소", 3074, 62, 65, 40, 61, 63, "하북", 445, 110, listOf("남피", "제남", "북해")),
        RawCity(49, "회계", "소", 3005, 64, 59, 40, 62, 64, "오월", 485, 390, listOf("오", "건안", "대")),
        RawCity(50, "천수", "소", 2985, 59, 64, 40, 60, 58, "서북", 76, 140, listOf("무위", "안정", "오장원", "저")),
        RawCity(51, "평양", "소", 2939, 55, 59, 40, 60, 58, "동이", 606, 97, listOf("국내", "위례")),
        RawCity(52, "요동", "소", 2937, 63, 59, 40, 59, 63, "동이", 549, 26, listOf("북평", "오환", "국내")),
        RawCity(53, "거록", "소", 2936, 61, 57, 40, 64, 58, "하북", 355, 95, listOf("진양", "남피", "업")),
        RawCity(54, "여강", "소", 2905, 56, 58, 40, 60, 55, "오월", 392, 325, listOf("합비", "단양", "시상", "강하")),
        RawCity(55, "패", "소", 2877, 64, 58, 40, 58, 59, "중원", 425, 210, listOf("진류", "복양", "하비", "초")),
        RawCity(56, "자동", "소", 2870, 57, 55, 40, 60, 58, "서촉", 62, 240, listOf("무도", "한중", "덕양")),
        RawCity(57, "광릉", "소", 2867, 61, 55, 40, 60, 62, "오월", 478, 270, listOf("하비", "건업")),
        RawCity(58, "장가", "소", 2853, 59, 62, 40, 58, 57, "남중", 136, 395, listOf("건녕", "영릉", "남만")),
        RawCity(59, "영릉", "소", 2849, 62, 58, 40, 62, 62, "초", 197, 390, listOf("무릉", "계양", "장가")),
        RawCity(60, "월수", "소", 2828, 60, 59, 40, 58, 63, "남중", 39, 349, listOf("강주", "주제", "운남")),
        RawCity(61, "건안", "소", 2802, 57, 62, 40, 58, 63, "오월", 440, 420, listOf("단양", "회계", "산월")),
        RawCity(62, "신야", "소", 2786, 60, 62, 40, 58, 55, "초", 245, 255, listOf("상용", "완", "양양")),
        RawCity(63, "탐라", "수", 1130, 22, 21, 20, 43, 41, "동이", 614, 259, listOf("웅진", "왜", "오")),
        RawCity(64, "상동", "소", 2767, 58, 59, 40, 62, 58, "초", 285, 405, listOf("계양", "시상", "남해")),
        RawCity(65, "안정", "소", 2764, 57, 59, 40, 57, 62, "서북", 135, 130, listOf("강", "무위", "천수", "장안")),
        RawCity(66, "여남", "소", 2749, 63, 56, 40, 64, 64, "중원", 335, 255, listOf("완", "허창", "초", "수춘")),
        RawCity(67, "홍농", "소", 2748, 57, 63, 40, 58, 63, "서북", 220, 170, listOf("하동", "낙양", "장안")),
        RawCity(68, "주제", "소", 2746, 58, 61, 40, 61, 58, "남중", 93, 357, listOf("강주", "월수", "건녕")),
        RawCity(69, "남만", "이", 2378, 40, 42, 20, 43, 45, "남중", 90, 454, listOf("운남", "장가", "교지")),
        RawCity(70, "산월", "이", 2275, 40, 37, 20, 43, 38, "오월", 373, 447, listOf("건안", "대", "남해")),
        RawCity(71, "오환", "이", 2153, 42, 37, 20, 43, 40, "동이", 628, 19, listOf("요동", "국내")),
        RawCity(72, "강", "이", 2095, 40, 42, 20, 43, 40, "서북", 154, 70, listOf("무위", "안정")),
        RawCity(73, "왜", "이", 2065, 39, 37, 20, 43, 41, "동이", 681, 292, listOf("계림", "탐라", "대")),
        RawCity(74, "흉노", "이", 2064, 40, 41, 20, 40, 38, "서북", 227, 79, listOf("진양", "하동")),
        RawCity(75, "저", "이", 1957, 40, 42, 20, 43, 42, "서북", 24, 123, listOf("무위", "천수", "무도")),
        RawCity(76, "호로", "관", 958, 17, 19, 20, 95, 96, "중원", 317, 182, listOf("낙양", "진류", "허창")),
        RawCity(77, "오장원", "진", 1005, 19, 18, 20, 41, 40, "서북", 104, 175, listOf("천수", "장안", "한중")),
        RawCity(78, "적벽", "수", 1117, 23, 21, 20, 42, 41, "오월", 335, 330, listOf("강하", "강릉", "시상")),
    )

    private val variants: Map<String, CityConstVariant> by lazy {
        val miniche = InitCityOverrideVariant("miniche", minicheInitCity)
        linkedMapOf(
            "che" to CheCityConst,
            "miniche" to miniche,
            "miniche_b" to miniche,
            "miniche_clean" to miniche,
            HAN_MAP_NAME to currentHan,
            HAN_WORLD_V2_MAP_NAME to hanWorldV2,
            HAN_780_V1_MAP_NAME to legacyHan,
        )
    }

    /** The active variant for [mapName]; throws if unknown. */
    fun of(mapName: String): CityConstVariant =
        variants[mapName] ?: error("no CityConst variant for mapName: $mapName")

    /** The active variant for [mapName], or null if unknown (non-throwing lookup). */
    fun find(mapName: String): CityConstVariant? = variants[mapName]

    fun activeMapName(config: Map<String, Any?>, meta: Map<String, Any?>): String =
        stringField(config["mapName"])
            ?: mapField(config["map"], "mapName")
            ?: stringField(meta["mapName"])
            ?: mapField(meta["map"], "mapName")
            ?: DEFAULT_MAP_NAME

    private fun mapField(raw: Any?, key: String): String? = when (raw) {
        is Map<*, *> -> (raw[key] as? String)?.takeIf { it.isNotBlank() }
        is String -> raw.takeIf { key == "mapName" && it.isNotBlank() }
        else -> null
    }

    private fun stringField(raw: Any?): String? =
        (raw as? String)?.takeIf { it.isNotBlank() }
}
