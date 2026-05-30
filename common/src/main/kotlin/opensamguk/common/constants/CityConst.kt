package opensamguk.common.constants

/**
 * Faithful port of `legacy/devsam-core/hwe/sammo/CityConstBase.php`.
 *
 * $regionMap / $levelMap (bidirectional label<->int), $initCity 94 rows (13-element tuples),
 * _generate() (×100 stats + label->int + name->id bidirectional path), $buildInit per-level +
 * $buildInitCommon (used at DB seed time). Per project memory: lv=4 "이" = 이민족-only; han
 * county-seats use lv=5 "소" — both preserved distinctly.
 */
object CityConst {
    /** PHP $regionMap (lines 14-23): bidirectional label<->int. */
    val regionMap: Map<Any, Any> = linkedMapOf(
        "하북" to 1, 1 to "하북",
        "중원" to 2, 2 to "중원",
        "서북" to 3, 3 to "서북",
        "서촉" to 4, 4 to "서촉",
        "남중" to 5, 5 to "남중",
        "초" to 6, 6 to "초",
        "오월" to 7, 7 to "오월",
        "동이" to 8, 8 to "동이",
    )

    /** PHP $levelMap (lines 25-34): bidirectional label<->int. lv4='이', lv5='소' (project memory). */
    val levelMap: Map<Any, Any> = linkedMapOf(
        "수" to 1, 1 to "수",
        "진" to 2, 2 to "진",
        "관" to 3, 3 to "관",
        "이" to 4, 4 to "이",
        "소" to 5, 5 to "소",
        "중" to 6, 6 to "중",
        "대" to 7, 7 to "대",
        "특" to 8, 8 to "특",
    )

    private fun levelToInt(label: String): Int = levelMap.getValue(label) as Int
    private fun regionToInt(label: String): Int = regionMap.getValue(label) as Int

    /**
     * Raw $initCity row before _generate(): level/region are labels, path is name-list, stats raw.
     *
     * Public so per-map CityConst variants (the 'miniche' 78-city override that inherits
     * buildInit/levelMap/regionMap — `scenario/map/miniche.php`) can supply their own initCity rows
     * and run them through the SAME GREEN [generateCities] algorithm rather than re-implementing it.
     */
    data class RawCity(
        val id: Int, val name: String, val level: String,
        val population: Int, val agriculture: Int, val commerce: Int,
        val security: Int, val defence: Int, val wall: Int,
        val region: String, val posX: Int, val posY: Int, val path: List<String>,
    )

    /** The three generated maps produced by [generateCities] (mirrors CityConstBase::_generate). */
    class GeneratedCities(
        val constID: Map<Int, CityInitialDetail>,
        val constName: Map<String, CityInitialDetail>,
        val constRegion: Map<Int, CityInitialDetail>,
    )

    /**
     * Faithful port of CityConstBase::_generate() (lines 156-240), extracted as a reusable function
     * so per-map variants reuse the EXACT byte-identical algorithm (stats ×100, label→int via the
     * shared maps, name→id bidirectional path as an insertion-ordered LinkedHashMap, and the byRegion
     * **last-wins quirk** `$constRegion[$region] = $city`). Behavior is unchanged from the original
     * inlined lazy block — this only makes it callable with a different initCity row set.
     */
    fun generateCities(rawRows: List<RawCity>): GeneratedCities {
        val nameMap = LinkedHashMap<String, Int>()
        for (raw in rawRows) nameMap[raw.name] = raw.id

        val constID = LinkedHashMap<Int, CityInitialDetail>()
        val constName = LinkedHashMap<String, CityInitialDetail>()
        val constRegion = LinkedHashMap<Int, CityInitialDetail>()

        for (raw in rawRows) {
            val level = levelToInt(raw.level)
            val population = raw.population * 100
            val agriculture = raw.agriculture * 100
            val commerce = raw.commerce * 100
            val security = raw.security * 100
            val defence = raw.defence * 100
            val wall = raw.wall * 100
            val region = regionToInt(raw.region)
            val newPath = LinkedHashMap<Int, String>()
            for (pathName in raw.path) {
                val pathId = nameMap.getValue(pathName)
                newPath[pathId] = pathName
            }

            val city = CityInitialDetail(
                raw.id, raw.name, level, population, agriculture, commerce,
                security, defence, wall, region, raw.posX, raw.posY, newPath,
            )

            constID[raw.id] = city
            constName[raw.name] = city
            constRegion[region] = city // last-wins quirk (mirrors PHP)
        }

        return GeneratedCities(constID, constName, constRegion)
    }

    /**
     * PHP $initCity (lines 58-154): 94 rows, faithful transcription — the canonical 'che' base.
     *
     * Public so the CityConstRegistry can confirm canonical che data lives HERE in the base (the
     * resolved decision: 'che' is the base, NOT an empty variant file) and so variants can reuse it.
     */
    val cheInitCity: List<RawCity> = listOf(
        RawCity(1, "업", "특", 6205, 125, 113, 100, 117, 122, "하북", 345, 130, listOf("남피", "복양", "호관", "계교", "관도")),
        RawCity(2, "허창", "특", 5876, 121, 124, 100, 117, 125, "중원", 330, 215, listOf("완", "진류", "초", "호로", "사수", "관도")),
        RawCity(3, "낙양", "특", 8357, 117, 120, 100, 121, 124, "중원", 275, 180, listOf("호관", "호로", "사곡", "사수")),
        RawCity(4, "장안", "특", 5923, 116, 123, 100, 120, 118, "서북", 145, 165, listOf("안정", "함곡", "기산")),
        RawCity(5, "성도", "특", 6525, 123, 125, 100, 125, 123, "서촉", 25, 290, listOf("덕양", "강주", "면죽")),
        RawCity(6, "양양", "특", 5837, 120, 126, 100, 115, 117, "초", 255, 290, listOf("신야", "장판")),
        RawCity(7, "건업", "특", 6386, 116, 123, 100, 115, 119, "오월", 505, 305, listOf("오", "합비", "광릉")),
        RawCity(8, "북평", "대", 4862, 102, 95, 80, 103, 99, "하북", 465, 65, listOf("역경", "백랑")),
        RawCity(9, "남피", "대", 5032, 99, 101, 80, 101, 105, "하북", 395, 95, listOf("업", "평원", "역경")),
        RawCity(10, "완", "대", 4724, 103, 100, 80, 101, 99, "중원", 270, 235, listOf("허창", "여남", "신야", "호로")),
        RawCity(11, "수춘", "대", 5143, 99, 96, 80, 99, 95, "중원", 395, 270, listOf("서주", "여남", "초", "합비")),
        RawCity(12, "서주", "대", 4853, 101, 98, 80, 102, 97, "중원", 440, 250, listOf("수춘", "하비", "초", "패")),
        RawCity(13, "강릉", "대", 4850, 105, 96, 80, 95, 96, "초", 245, 335, listOf("장사", "무릉", "이릉", "장판")),
        RawCity(14, "장사", "대", 4710, 97, 99, 80, 100, 105, "초", 255, 375, listOf("강릉", "시상", "계양", "무릉", "영릉")),
        RawCity(15, "시상", "대", 5252, 98, 100, 100, 99, 96, "오월", 360, 360, listOf("장사", "여강", "고창", "적벽", "파양")),
        RawCity(16, "위례", "대", 4926, 100, 93, 80, 98, 103, "동이", 620, 145, listOf("평양", "사비", "계림", "안평", "동황")),
        RawCity(17, "계", "중", 3885, 75, 80, 60, 78, 81, "하북", 365, 35, listOf("진양", "역경")),
        RawCity(18, "복양", "중", 4185, 80, 83, 60, 82, 80, "중원", 410, 170, listOf("업", "진류", "계교", "정도")),
        RawCity(19, "진류", "중", 3957, 82, 80, 60, 80, 83, "중원", 365, 185, listOf("허창", "복양", "사수", "관도", "정도")),
        RawCity(20, "여남", "중", 3831, 77, 81, 60, 84, 77, "중원", 330, 260, listOf("완", "수춘", "초")),
        RawCity(21, "하비", "중", 4278, 85, 83, 60, 82, 78, "중원", 480, 235, listOf("서주", "광릉")),
        RawCity(22, "서량", "중", 3874, 77, 79, 60, 83, 80, "서북", 25, 50, listOf("천수", "강", "저")),
        RawCity(23, "하내", "중", 3736, 77, 81, 60, 81, 80, "서북", 230, 150, listOf("진양", "홍농", "흉노", "호관")),
        RawCity(24, "한중", "중", 4027, 77, 84, 60, 80, 85, "서촉", 135, 205, listOf("상용", "양평", "기산")),
        RawCity(25, "상용", "중", 3687, 78, 76, 60, 77, 81, "서촉", 185, 225, listOf("한중", "신야")),
        RawCity(26, "덕양", "중", 3803, 81, 84, 60, 79, 77, "서촉", 85, 275, listOf("성도", "강주", "자동", "영안")),
        RawCity(27, "강주", "중", 4126, 79, 80, 60, 84, 81, "서촉", 70, 310, listOf("성도", "덕양", "영안", "귀양", "주시")),
        RawCity(28, "건녕", "중", 3765, 82, 80, 60, 86, 81, "남중", 80, 400, listOf("귀양", "운남", "남영")),
        RawCity(29, "남해", "중", 3803, 82, 76, 60, 80, 81, "남중", 245, 480, listOf("교지", "상동", "대", "산월")),
        RawCity(30, "계양", "중", 3955, 83, 80, 60, 81, 77, "초", 230, 400, listOf("장사", "영릉", "상동")),
        RawCity(31, "오", "중", 4355, 77, 81, 60, 77, 76, "오월", 510, 345, listOf("건업", "회계", "파양", "탐라")),
        RawCity(32, "평양", "중", 3982, 78, 80, 60, 83, 78, "동이", 590, 100, listOf("위례", "졸본")),
        RawCity(33, "사비", "중", 4157, 77, 79, 60, 78, 80, "동이", 605, 205, listOf("위례", "계림", "탐라")),
        RawCity(34, "계림", "중", 3911, 80, 74, 60, 81, 78, "동이", 655, 200, listOf("위례", "사비", "이도", "탐라")),
        RawCity(35, "진양", "소", 3074, 56, 59, 40, 64, 59, "하북", 295, 60, listOf("계", "하내", "호관")),
        RawCity(36, "평원", "소", 3074, 62, 65, 40, 61, 63, "하북", 440, 115, listOf("남피", "북해", "계교")),
        RawCity(37, "북해", "소", 3146, 55, 63, 40, 63, 58, "하북", 470, 155, listOf("평원", "패", "동황")),
        RawCity(38, "초", "소", 3286, 60, 62, 40, 62, 57, "중원", 365, 230, listOf("허창", "수춘", "서주", "여남", "정도")),
        RawCity(39, "패", "소", 2877, 64, 58, 40, 58, 59, "중원", 430, 220, listOf("서주", "북해", "정도")),
        RawCity(40, "천수", "소", 2985, 59, 64, 40, 60, 58, "서북", 70, 105, listOf("서량", "안정", "저", "적도")),
        RawCity(41, "안정", "소", 2764, 57, 59, 40, 57, 62, "서북", 95, 145, listOf("장안", "천수", "가정")),
        RawCity(42, "홍농", "소", 2748, 57, 63, 40, 58, 63, "서북", 210, 175, listOf("하내", "사곡", "함곡")),
        RawCity(43, "하변", "소", 2785, 58, 62, 40, 60, 56, "서촉", 45, 190, listOf("가맹", "가정")),
        RawCity(44, "자동", "소", 2870, 57, 55, 40, 60, 58, "서촉", 75, 245, listOf("덕양", "양평", "가맹", "면죽")),
        RawCity(45, "영안", "소", 3153, 62, 59, 40, 58, 59, "서촉", 115, 295, listOf("덕양", "강주", "이릉")),
        RawCity(46, "귀양", "소", 2746, 58, 61, 40, 61, 58, "남중", 90, 360, listOf("강주", "건녕", "주시")),
        RawCity(47, "주시", "소", 2828, 60, 59, 40, 58, 63, "남중", 30, 345, listOf("강주", "귀양", "운남")),
        RawCity(48, "운남", "소", 3258, 62, 60, 40, 64, 61, "남중", 30, 415, listOf("건녕", "주시", "남만")),
        RawCity(49, "남영", "소", 2853, 59, 62, 40, 58, 57, "남중", 135, 405, listOf("건녕", "영릉", "남만")),
        RawCity(50, "교지", "소", 3195, 58, 59, 40, 58, 59, "남중", 130, 480, listOf("남해", "남만")),
        RawCity(51, "신야", "소", 2786, 60, 62, 40, 58, 55, "초", 250, 260, listOf("양양", "완", "상용")),
        RawCity(52, "강하", "소", 3074, 55, 56, 40, 57, 60, "초", 315, 295, listOf("장판", "적벽")),
        RawCity(53, "무릉", "소", 3196, 58, 63, 40, 63, 58, "초", 195, 355, listOf("강릉", "장사", "영릉")),
        RawCity(54, "영릉", "소", 2849, 62, 58, 40, 62, 62, "초", 190, 395, listOf("계양", "남영", "무릉", "장사")),
        RawCity(55, "상동", "소", 2767, 58, 59, 40, 62, 58, "초", 210, 435, listOf("남해", "계양", "고창")),
        RawCity(56, "여강", "소", 2905, 56, 58, 40, 60, 55, "오월", 380, 315, listOf("시상", "합비", "적벽", "파양")),
        RawCity(57, "회계", "소", 3005, 64, 59, 40, 62, 64, "오월", 480, 395, listOf("오", "산월")),
        RawCity(58, "고창", "소", 2802, 57, 62, 40, 58, 63, "오월", 350, 405, listOf("시상", "상동", "산월")),
        RawCity(59, "대", "소", 3256, 60, 62, 40, 57, 60, "오월", 450, 480, listOf("남해", "산월", "유구")),
        RawCity(60, "안평", "소", 2937, 63, 59, 40, 59, 63, "동이", 530, 80, listOf("위례", "졸본", "동황", "백랑")),
        RawCity(61, "졸본", "소", 2939, 55, 59, 40, 60, 58, "동이", 570, 65, listOf("평양", "안평", "오환")),
        RawCity(62, "이도", "소", 3174, 58, 61, 40, 58, 56, "동이", 680, 260, listOf("계림", "왜", "탐라")),
        RawCity(63, "강", "이", 2095, 40, 42, 20, 43, 40, "서북", 95, 35, listOf("서량", "적도")),
        RawCity(64, "저", "이", 1957, 40, 42, 20, 43, 42, "서북", 25, 120, listOf("서량", "천수", "가정")),
        RawCity(65, "흉노", "이", 2064, 40, 41, 20, 40, 38, "서북", 180, 95, listOf("하내", "적도")),
        RawCity(66, "남만", "이", 2378, 40, 42, 20, 43, 45, "남중", 80, 455, listOf("운남", "남영", "교지")),
        RawCity(67, "산월", "이", 2275, 40, 37, 20, 43, 38, "오월", 425, 430, listOf("남해", "회계", "고창", "대")),
        RawCity(68, "오환", "이", 2153, 42, 37, 20, 43, 40, "동이", 610, 20, listOf("졸본", "백랑")),
        RawCity(69, "왜", "이", 2065, 39, 37, 20, 43, 41, "동이", 680, 320, listOf("이도", "유구")),
        RawCity(70, "호관", "관", 887, 19, 18, 20, 95, 96, "하북", 285, 140, listOf("업", "낙양", "하내", "진양")),
        RawCity(71, "호로", "관", 1112, 22, 21, 20, 103, 98, "중원", 285, 205, listOf("허창", "낙양", "완")),
        RawCity(72, "사곡", "관", 1008, 21, 19, 20, 99, 101, "서북", 240, 175, listOf("낙양", "홍농")),
        RawCity(73, "함곡", "관", 1081, 20, 22, 20, 101, 102, "서북", 180, 175, listOf("장안", "홍농")),
        RawCity(74, "사수", "관", 958, 17, 19, 20, 95, 96, "중원", 310, 185, listOf("허창", "낙양", "진류", "관도")),
        RawCity(75, "양평", "관", 868, 19, 19, 20, 97, 96, "서촉", 90, 220, listOf("한중", "자동", "기산")),
        RawCity(76, "가맹", "관", 855, 17, 18, 20, 96, 95, "서촉", 45, 225, listOf("하변", "자동")),
        RawCity(77, "역경", "진", 985, 18, 19, 20, 39, 41, "하북", 410, 65, listOf("북평", "남피", "계")),
        RawCity(78, "계교", "진", 1012, 21, 19, 20, 40, 42, "하북", 405, 135, listOf("업", "복양", "평원")),
        RawCity(79, "동황", "진", 992, 19, 21, 20, 38, 40, "하북", 515, 145, listOf("위례", "북해", "안평")),
        RawCity(80, "관도", "진", 1123, 22, 20, 20, 42, 43, "중원", 340, 165, listOf("업", "허창", "진류", "사수")),
        RawCity(81, "정도", "진", 1085, 21, 21, 20, 41, 38, "중원", 400, 210, listOf("복양", "진류", "초", "패")),
        RawCity(82, "합비", "진", 998, 20, 19, 20, 39, 41, "중원", 435, 285, listOf("건업", "수춘", "여강")),
        RawCity(83, "광릉", "진", 1001, 20, 21, 20, 41, 40, "중원", 490, 275, listOf("건업", "하비")),
        RawCity(84, "적도", "진", 952, 18, 17, 20, 38, 37, "서북", 130, 75, listOf("천수", "강", "흉노")),
        RawCity(85, "가정", "진", 931, 17, 17, 20, 36, 38, "서북", 40, 160, listOf("안정", "하변", "저")),
        RawCity(86, "기산", "진", 1005, 19, 18, 20, 41, 40, "서촉", 110, 180, listOf("장안", "한중", "양평")),
        RawCity(87, "면죽", "관", 1093, 22, 21, 20, 108, 99, "서촉", 35, 255, listOf("성도", "자동")),
        RawCity(88, "이릉", "진", 968, 18, 19, 20, 39, 41, "초", 215, 295, listOf("강릉", "영안")),
        RawCity(89, "장판", "진", 1032, 21, 20, 20, 40, 37, "초", 280, 315, listOf("양양", "강릉", "강하", "적벽")),
        RawCity(90, "백랑", "진", 1052, 22, 19, 20, 38, 42, "동이", 530, 30, listOf("북평", "안평", "오환")),
        RawCity(91, "적벽", "수", 1117, 23, 21, 20, 42, 41, "오월", 330, 325, listOf("시상", "강하", "여강", "장판")),
        RawCity(92, "파양", "수", 1037, 20, 22, 20, 38, 38, "오월", 430, 350, listOf("시상", "오", "여강")),
        RawCity(93, "탐라", "수", 1130, 22, 21, 20, 43, 41, "동이", 605, 260, listOf("오", "사비", "계림", "이도")),
        RawCity(94, "유구", "수", 921, 17, 18, 20, 37, 37, "동이", 625, 435, listOf("대", "왜")),
    )

    /** Port of _generate() (lines 156-240) over the canonical che rows, via the shared [generateCities]. */
    private val generated: GeneratedCities by lazy { generateCities(cheInitCity) }

    fun all(): Map<Int, CityInitialDetail> = generated.constID
    fun byId(id: Int): CityInitialDetail? = generated.constID[id]
    fun byName(name: String): CityInitialDetail? = generated.constName[name]

    /** Mirrors PHP byRegion(): returns the LAST city in the region (last-wins quirk). */
    fun byRegion(region: Int): CityInitialDetail? = generated.constRegion[region]

    /** PHP $buildInitCommon (lines 242-245): applied to every city at DB seed time. */
    val buildInitCommon: Map<String, Int> = linkedMapOf(
        "trust" to 50,
        "trade" to 100,
    )

    /** PHP $buildInit (lines 247-312): per-level seed values, keyed by level label. */
    val buildInit: Map<String, Map<String, Int>> = linkedMapOf(
        "수" to linkedMapOf("pop" to 5000, "agri" to 100, "comm" to 100, "secu" to 100, "def" to 500, "wall" to 500),
        "진" to linkedMapOf("pop" to 5000, "agri" to 100, "comm" to 100, "secu" to 100, "def" to 500, "wall" to 500),
        "관" to linkedMapOf("pop" to 10000, "agri" to 100, "comm" to 100, "secu" to 100, "def" to 1000, "wall" to 1000),
        "이" to linkedMapOf("pop" to 50000, "agri" to 1000, "comm" to 1000, "secu" to 1000, "def" to 1000, "wall" to 1000),
        "소" to linkedMapOf("pop" to 100000, "agri" to 1000, "comm" to 1000, "secu" to 1000, "def" to 2000, "wall" to 2000),
        "중" to linkedMapOf("pop" to 100000, "agri" to 1000, "comm" to 1000, "secu" to 1000, "def" to 3000, "wall" to 3000),
        "대" to linkedMapOf("pop" to 150000, "agri" to 1000, "comm" to 1000, "secu" to 1000, "def" to 4000, "wall" to 4000),
        "특" to linkedMapOf("pop" to 150000, "agri" to 1000, "comm" to 1000, "secu" to 1000, "def" to 5000, "wall" to 5000),
    )
}
