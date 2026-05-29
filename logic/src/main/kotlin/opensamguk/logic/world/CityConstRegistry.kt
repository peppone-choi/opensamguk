package opensamguk.logic.world

import opensamguk.common.constants.CityConst
import opensamguk.common.constants.CityConst.RawCity
import opensamguk.common.constants.CityInitialDetail

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
sealed interface CityConstVariant {
    val mapName: String
    fun all(): Map<Int, CityInitialDetail>
    fun byId(id: Int): CityInitialDetail?
    fun byName(name: String): CityInitialDetail?
    /** Mirrors CityConstBase::byRegion (the last-wins quirk: LAST city encountered per region). */
    fun byRegion(region: Int): CityInitialDetail?
    /** Inherited from the base ($buildInit/$buildInitCommon are NOT overridden by the map files). */
    val buildInit: Map<String, Map<String, Int>>
    val buildInitCommon: Map<String, Int>
}

/** 'che' = the canonical base — delegates directly to the GREEN [CityConst] object (zero delta). */
internal object CheCityConst : CityConstVariant {
    override val mapName: String = "che"
    override fun all(): Map<Int, CityInitialDetail> = CityConst.all()
    override fun byId(id: Int): CityInitialDetail? = CityConst.byId(id)
    override fun byName(name: String): CityInitialDetail? = CityConst.byName(name)
    override fun byRegion(region: Int): CityInitialDetail? = CityConst.byRegion(region)
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
    override val buildInit: Map<String, Map<String, Int>> get() = CityConst.buildInit
    override val buildInitCommon: Map<String, Int> get() = CityConst.buildInitCommon
}

object CityConstRegistry {
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
        linkedMapOf(
            "che" to CheCityConst,
            "miniche" to InitCityOverrideVariant("miniche", minicheInitCity),
        )
    }

    /** The active variant for [mapName]; throws if unknown. */
    fun of(mapName: String): CityConstVariant =
        variants[mapName] ?: error("no CityConst variant for mapName: $mapName")

    /** The active variant for [mapName], or null if unknown (non-throwing lookup). */
    fun find(mapName: String): CityConstVariant? = variants[mapName]
}
