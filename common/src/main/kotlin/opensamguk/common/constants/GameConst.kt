package opensamguk.common.constants

/**
 * Immutable boot config — faithful port of `legacy/devsam-core/hwe/sammo/GameConstBase.php`.
 *
 * Scalars, enumerated string sets, item catalog, command menus, name pools, and the declarative
 * event tables (`defaultInitialEvents`, `defaultEvents`). Event tables are tuple-arrays-of-strings
 * = DATA, not control flow. `ActionLogger::EVENT_YEAR_MONTH` enum refs are replaced with the int
 * literal the LogFormat enum serializes to (LogFormat.EVENT_YEAR_MONTH = 6).
 */
object GameConst {
    const val title = "삼국지 모의전투 PHP HiDCHe"
    const val banner = "KOEI의 이미지를 사용, 응용하였습니다 / 제작 : HideD(hided62@gmail.com) / <a href='https://sam.hided.net/wiki/hidche/credit' target='_blank' style='color:white;text-decoration: underline;'>Credit</a>"
    const val mapName = "che"
    const val unitSet = "che"
    const val develrate = 50
    const val upgradeLimit = 30
    const val dexLimit = 1_000_000
    const val defaultAtmosLow = 40
    const val defaultTrainLow = 40
    const val defaultAtmosHigh = 70
    const val defaultTrainHigh = 70
    const val maxAtmosByCommand = 100
    const val maxTrainByCommand = 100
    const val maxAtmosByWar = 150
    const val maxTrainByWar = 110
    const val trainDelta = 30
    const val atmosDelta = 30
    const val atmosSideEffectByTraining = 1.0
    const val trainSideEffectByAtmosTurn = 1.0
    const val sabotageDefaultProb = 0.35
    const val sabotageProbCoefByStat = 300
    const val sabotageDefenceCoefByGeneralCnt = 0.04
    const val sabotageDamageMin = 100
    const val sabotageDamageMax = 800
    const val basecolor = "#000044"
    const val basecolor2 = "#225500"
    const val basecolor3 = "#660000"
    const val basecolor4 = "#330000"
    const val armperphase = 500
    const val basegold = 0
    const val baserice = 2000
    const val minNationalGold = 0
    const val minNationalRice = 0
    const val exchangeFee = 0.01
    const val adultAge = 14.0
    const val minPushHallAge = 40.0
    const val maxDedLevel = 30
    const val maxTechLevel = 12
    const val maxBetrayCnt = 9

    const val incDefSettingChange = 3
    const val maxDefSettingChange = 9

    const val refreshLimitCoef = 10.0

    const val maxLevel = 255            // PHP grand truth (Nation 0-9 extension is separate)

    const val techLevelIncYear = 5
    const val initialAllowedTechLevel = 1

    const val basePopIncreaseAmount = 5000
    const val expandCityPopIncreaseAmount = 100000
    const val expandCityDevelIncreaseAmount = 2000
    const val expandCityWallIncreaseAmount = 2000
    const val expandCityDefaultCost = 60000
    const val expandCityCostCoef = 500
    const val minAvailableRecruitPop = 30000

    const val defaultCityWall = 1000

    const val initialNationGenLimit = 10

    const val defaultMaxGeneral = 500
    const val defaultMaxNation = 55
    const val defaultMaxGenius = 5
    const val defaultStartYear = 180

    const val joinRuinedNPCProp = 0.1

    const val defaultGold = 1000
    const val defaultRice = 1000

    const val coefAidAmount = 10000

    const val maxResourceActionAmount = 10000
    val resourceActionAmountGuide = listOf(
        100, 200, 300, 400, 500, 600, 700, 800, 900, 1000,
        1200, 1500, 2000, 2500, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000,
    )

    const val generalMinimumGold = 0
    const val generalMinimumRice = 500

    const val maxTurn = 30
    const val maxChiefTurn = 12

    const val statGradeLevel = 5

    const val openingPartYear = 3
    const val joinActionLimit = 12

    const val bornMinStatBonus = 3
    const val bornMaxStatBonus = 5

    val availableNationType = listOf(
        "che_도적", "che_명가", "che_음양가", "che_종횡가", "che_불가", "che_오두미도", "che_태평도", "che_도가",
        "che_묵가", "che_덕가", "che_병가", "che_유가", "che_법가",
    )
    const val neutralNationType = "che_중립"

    const val defaultSpecialDomestic = "None"
    val availableSpecialDomestic = listOf(
        "che_경작", "che_상재", "che_발명", "che_축성", "che_수비", "che_통찰", "che_인덕", "che_귀모",
    )
    val optionalSpecialDomestic = listOf(
        "None",
    )

    const val defaultSpecialWar = "None"
    val availableSpecialWar = listOf(
        "che_귀병", "che_신산", "che_환술", "che_집중", "che_신중", "che_반계",
        "che_보병", "che_궁병", "che_기병", "che_공성",
        "che_돌격", "che_무쌍", "che_견고", "che_위압",
        "che_저격", "che_필살", "che_징병", "che_의술", "che_격노", "che_척사",
    )
    val optionalSpecialWar = listOf(
        "None",
    )

    const val neutralPersonality = "None"
    val availablePersonality = listOf(
        "che_안전", "che_유지", "che_재간", "che_출세", "che_할거", "che_정복",
        "che_패권", "che_의협", "che_대의", "che_왕좌",
    )
    val optionalPersonality = listOf(
        "che_은둔", "None",
    )

    val maxUniqueItemLimit = listOf(
        listOf(-1, 1),
        listOf(3, 2),
        listOf(10, 3),
        listOf(20, 4),
    )

    const val minTurnDieOnPrestart = 2

    const val uniqueTrialCoef = 1
    const val maxUniqueTrialProb = 0.25

    const val maxAvailableWarSettingCnt = 10
    const val incAvailableWarSettingCnt = 2

    const val minGoldRequiredWhenBetting = 500

    const val minMonthToAllowInheritItem = 4
    const val inheritBornSpecialPoint = 6000
    const val inheritBornTurntimePoint = 2500
    const val inheritBornCityPoint = 1000
    const val inheritBornStatPoint = 1000
    const val inheritItemUniqueMinPoint = 5000
    const val inheritItemRandomPoint = 3000
    val inheritBuffPoints = listOf(0, 200, 600, 1200, 2000, 3000)
    const val inheritSpecificSpecialPoint = 4000
    val inheritResetAttrPointBase = listOf(1000, 1000, 2000, 3000)
    const val inheritCheckOwnerPoint = 1000

    val scenarioEffect: String? = null

    val defaultInstantAction: Map<String, Boolean> = linkedMapOf(
        "dieOnPrestart" to true,
        "buildNationCandidate" to true,
    )

    val availableInstantAction: Map<String, Boolean> = linkedMapOf(
        "dieOnPrestart" to true,
        "buildNationCandidate" to true,
    )

    val allItems: Map<String, Map<String, Int>> = linkedMapOf(
        "horse" to linkedMapOf(
            "che_명마_01_노기" to 0, "che_명마_02_조랑" to 0, "che_명마_03_노새" to 0,
            "che_명마_04_나귀" to 0, "che_명마_05_갈색마" to 0, "che_명마_06_흑색마" to 0,

            "che_명마_07_백마" to 2, "che_명마_07_기주마" to 2, "che_명마_07_오환마" to 2, "che_명마_07_백상" to 2,
            "che_명마_08_양주마" to 2, "che_명마_08_흉노마" to 2, "che_명마_09_과하마" to 2, "che_명마_09_의남백마" to 2,
            "che_명마_10_대완마" to 2, "che_명마_10_옥추마" to 2, "che_명마_11_서량마" to 2, "che_명마_11_화종마" to 2,
            "che_명마_12_사륜거" to 2, "che_명마_12_옥란백용구" to 2, "che_명마_13_절영" to 2, "che_명마_13_적로" to 2,
            "che_명마_14_적란마" to 2, "che_명마_14_조황비전" to 2, "che_명마_15_한혈마" to 2, "che_명마_15_적토마" to 2,
        ),
        "weapon" to linkedMapOf(
            "che_무기_01_단도" to 0, "che_무기_02_단궁" to 0, "che_무기_03_단극" to 0,
            "che_무기_04_목검" to 0, "che_무기_05_죽창" to 0, "che_무기_06_소부" to 0,

            "che_무기_07_동추" to 2, "che_무기_07_철편" to 2, "che_무기_07_철쇄" to 2, "che_무기_07_맥궁" to 2,
            "che_무기_08_유성추" to 2, "che_무기_08_철질여골" to 2, "che_무기_09_쌍철극" to 2, "che_무기_09_동호비궁" to 2,
            "che_무기_10_삼첨도" to 2, "che_무기_10_대부" to 2, "che_무기_11_고정도" to 2, "che_무기_11_이광궁" to 2,
            "che_무기_12_철척사모" to 2, "che_무기_12_칠성검" to 2, "che_무기_13_사모" to 2, "che_무기_13_양유기궁" to 2,
            "che_무기_14_언월도" to 2, "che_무기_14_방천화극" to 2, "che_무기_15_청홍검" to 2, "che_무기_15_의천검" to 2,
        ),
        "book" to linkedMapOf(
            "che_서적_01_효경전" to 0, "che_서적_02_회남자" to 0, "che_서적_03_변도론" to 0,
            "che_서적_04_건상역주" to 0, "che_서적_05_여씨춘추" to 0, "che_서적_06_사민월령" to 0,

            "che_서적_07_위료자" to 2, "che_서적_07_사마법" to 2, "che_서적_07_한서" to 2, "che_서적_07_논어" to 2,
            "che_서적_08_전론" to 2, "che_서적_08_사기" to 2, "che_서적_09_장자" to 2, "che_서적_09_역경" to 2,
            "che_서적_10_시경" to 2, "che_서적_10_구국론" to 2, "che_서적_11_상군서" to 2, "che_서적_11_춘추전" to 2,
            "che_서적_12_산해경" to 2, "che_서적_12_맹덕신서" to 2, "che_서적_13_관자" to 2, "che_서적_13_병법24편" to 2,
            "che_서적_14_한비자" to 2, "che_서적_14_오자병법" to 2, "che_서적_15_노자" to 2, "che_서적_15_손자병법" to 2,
        ),
        "item" to linkedMapOf(
            "che_치료_환약" to 0, "che_저격_수극" to 0, "che_사기_탁주" to 0,
            "che_훈련_청주" to 0, "che_계략_이추" to 0, "che_계략_향낭" to 0,

            "che_의술_정력견혈산" to 1, "che_의술_청낭서" to 1, "che_의술_태평청령" to 1, "che_의술_상한잡병론" to 1,
            "che_보물_도기" to 1, "che_조달_주판" to 1,
            "che_내정_납금박산로" to 1, "che_전략_평만지장도" to 1, "che_숙련_동작" to 1, "che_명성_구석" to 1,

            "che_척사_오악진형도" to 1, "che_격노_구정신단경" to 1, "che_징병_낙주" to 1,
            "che_저격_매화수전" to 1, "che_저격_비도" to 1, "che_위압_조목삭" to 1, "che_공성_묵자" to 1,
            "che_집중_전국책" to 1, "che_환술_논어집해" to 1,

            "che_진압_박혁론" to 1, "che_부적_태현청생부" to 1, "che_저지_삼황내문" to 1,
            "che_행동_서촉지형도" to 1, "che_간파_노군입산부" to 1, "che_불굴_상편" to 1,
            "che_약탈_옥벽" to 1,

            "che_농성_주서음부" to 1, "che_농성_위공자병법" to 1,
            "che_계략_육도" to 1, "che_계략_삼략" to 1,

            "che_상성보정_과실주" to 1,
            "che_능력치_지력_이강주" to 1, "che_능력치_무력_두강주" to 1, "che_능력치_통솔_보령압주" to 1,
            "che_훈련_철벽서" to 1, "che_훈련_단결도" to 1, "che_사기_춘화첩" to 1, "che_사기_초선화" to 1,
            "che_회피_태평요술" to 1, "che_필살_둔갑천서" to 1,
        ),
    )

    val availableGeneralCommand: Map<String, List<String>> = linkedMapOf(
        "개인" to listOf(
            "휴식",
            "che_요양",
            "che_단련",
            "che_숙련전환",
            "che_견문",
            "che_은퇴",
            "che_장비매매",
            "che_군량매매",
            "che_내정특기초기화",
            "che_전투특기초기화",
        ),
        "내정" to listOf(
            "che_농지개간",
            "che_상업투자",
            "che_기술연구",
            "che_수비강화",
            "che_성벽보수",
            "che_치안강화",
            "che_정착장려",
            "che_주민선정",
        ),
        "군사" to listOf(
            "che_징병",
            "che_모병",
            "che_훈련",
            "che_사기진작",
            "che_출병",
            "che_집합",
            "che_소집해제",
            "che_첩보",
        ),
        "인사" to listOf(
            "che_이동",
            "che_강행",
            "che_인재탐색",
            "che_등용",
            "che_귀환",
            "che_임관",
            "che_랜덤임관",
            "che_장수대상임관",
        ),
        "계략" to listOf(
            "che_선동",
            "che_탈취",
            "che_파괴",
            "che_화계",
        ),
        "국가" to listOf(
            "che_증여",
            "che_헌납",
            "che_물자조달",
            "che_하야",
            "che_거병",
            "che_건국",
            "che_선양",
            "che_해산",
        ),
    )

    val availableChiefCommand: Map<String, List<String>> = linkedMapOf(
        "휴식" to listOf(
            "휴식",
        ),
        "인사" to listOf(
            "che_발령",
            "che_포상",
            "che_몰수",
            "che_부대탈퇴지시",
        ),
        "외교" to listOf(
            "che_물자원조",
            "che_불가침제의",
            "che_선전포고",
            "che_종전제의",
            "che_불가침파기제의",
        ),
        "특수" to listOf(
            "che_초토화",
            "che_천도",
            "che_증축",
            "che_감축",
        ),
        "전략" to listOf(
            "che_필사즉생",
            "che_백성동원",
            "che_수몰",
            "che_허보",
            "che_의병모집",
            "che_이호경식",
            "che_급습",
            "che_피장파장",
        ),
        "기타" to listOf(
            "che_국기변경",
            "che_국호변경",
        ),
    )
    const val retirementYear = 80

    const val targetGeneralPool = "RandomNameGeneral"
    val generalPoolAllowOption = listOf("stat", "ego", "picture")

    val randGenFirstName = listOf(
        "가", "간", "감", "강", "고", "공", "공손", "곽", "관", "괴", "교", "금", "노", "뇌", "능", "도", "동", "두",
        "등", "마", "맹", "문", "미", "반", "방", "부", "비", "사", "사마", "서", "설", "성", "소", "손", "송", "순",
        "신", "심", "악", "안", "양", "엄", "여", "염", "오", "왕", "요", "우", "원", "위", "유", "육", "윤", "이",
        "장", "저", "전", "정", "제갈", "조", "종", "주", "진", "채", "태사", "하", "하후", "학", "한", "향", "허",
        "호", "화", "황", "공손", "손", "왕", "유", "장", "조",
    )
    val randGenMiddleName = listOf("")
    val randGenLastName = listOf(
        "가", "간", "강", "거", "건", "검", "견", "경", "공", "광", "권", "규", "녕", "단", "대", "도", "등", "람",
        "량", "례", "로", "료", "모", "민", "박", "범", "보", "비", "사", "상", "색", "서", "소", "속", "송", "수",
        "순", "습", "승", "양", "연", "영", "온", "옹", "완", "우", "웅", "월", "위", "유", "윤", "융", "이", "익",
        "임", "정", "제", "조", "주", "준", "지", "찬", "책", "충", "탁", "택", "통", "패", "평", "포", "합", "해",
        "혁", "현", "화", "환", "회", "횡", "후", "훈", "휴", "흠", "흥",
    )

    const val npcBanMessageProb = 0.01
    const val npcSeizureMessageProb = 0.01
    const val npcMessageFreqByDay = 4

    private const val LOGFORMAT_EVENT_YEAR_MONTH = 6  // LogFormat.EVENT_YEAR_MONTH

    val defaultInitialEvents: List<List<Any>> = listOf(
        listOf(
            true,
            listOf("NoticeToHistoryLog", "<S>2년간 거병 및 건국이 가능합니다.</>", LOGFORMAT_EVENT_YEAR_MONTH),
        ),
    )

    val defaultEvents: List<List<Any?>> = listOf(
        listOf(
            "pre_month", 9000,
            true,
            listOf("UpdateCitySupply"),
            listOf("ProcessWarIncome"),
        ),
        listOf(
            "month", 9000,
            listOf("Date", "==", null, 1),
            listOf("MergeInheritPointRank"),
            listOf("ProcessSemiAnnual", "gold"),
            listOf("ProcessIncome", "gold"),
            listOf("ResetOfficerLock"),
            listOf("RaiseDisaster"),
            listOf("RandomizeCityTradeRate"),
            listOf("NewYear"),
            listOf("AssignGeneralSpeciality"),
        ),
        listOf(
            "month", 9000,
            listOf("Date", "==", null, 4),
            listOf("ResetOfficerLock"),
            listOf("RaiseDisaster"),
        ),
        listOf(
            "month", 9000,
            listOf("Date", "==", null, 7),
            listOf("MergeInheritPointRank"),
            listOf("ProcessSemiAnnual", "rice"),
            listOf("ProcessIncome", "rice"),
            listOf("ResetOfficerLock"),
            listOf("RaiseDisaster"),
            listOf("RandomizeCityTradeRate"),
        ),
        listOf(
            "month", 9000,
            listOf("Date", "==", null, 10),
            listOf("ResetOfficerLock"),
            listOf("RaiseDisaster"),
        ),
        listOf(
            "month", 2000,
            listOf("DateRelative", "==", 1, 1),
            listOf("NoticeToHistoryLog", "<S>2년 뒤 출병 제한이 풀립니다.</>", LOGFORMAT_EVENT_YEAR_MONTH),
            listOf("DeleteEvent"),
        ),
        listOf(
            "month", 2000,
            listOf("DateRelative", "==", 2, 1),
            listOf("NoticeToHistoryLog", "<S>1년 뒤 출병 제한이 풀립니다.</>", LOGFORMAT_EVENT_YEAR_MONTH),
            listOf("DeleteEvent"),
        ),
        listOf(
            "month", 2000,
            listOf("DateRelative", "==", 2, 7),
            listOf("NoticeToHistoryLog", "<S>6개월 뒤 출병 제한이 풀립니다. 병력을 준비해주세요.</>", LOGFORMAT_EVENT_YEAR_MONTH),
            listOf("DeleteEvent"),
        ),
        listOf(
            "month", 2000,
            listOf("DateRelative", "==", 3, 1),
            listOf("NoticeToHistoryLog", "<S>출병 제한이 풀렸습니다.</>", LOGFORMAT_EVENT_YEAR_MONTH),
            listOf("DeleteEvent"),
        ),
        listOf(
            "month", 2000,
            listOf("DateRelative", "==", 4, 1),
            listOf("NoticeToHistoryLog", "<S>이제부터 하야, 망명시 패널티가 적용됩니다.</>", LOGFORMAT_EVENT_YEAR_MONTH),
            listOf("AddGlobalBetray", 1, 0),
            listOf("AddGlobalBetray", 1, 1),
            listOf("DeleteEvent"),
        ),
        listOf(
            "month", 1000,
            true,
            listOf("UpdateNationLevel"),
            listOf("ProvideNPCTroopLeader"),
        ),
        listOf(
            "united", 5000,
            true,
            listOf("MergeInheritPointRank"),
        ),
    )

    val staticEventHandlers: List<Any> = listOf()
}
