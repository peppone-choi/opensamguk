package opensamguk.logic.inheritance

import opensamguk.common.constants.GameConst

/**
 * 유산 관리 화면(v_inheritPoint.php:41-63)의 특기/유니크 카탈로그 — 순수 로직(스프링/DB 無).
 *
 * PHP frozen historical baseline (ADR-LITE-042; not current product authority):
 *  - availableSpecialWar: v_inheritPoint.php:41-48 — GameConst::$availableSpecialWar 순회 →
 *    buildGeneralSpecialWarClass(key) → { title: getName(), info: getInfo() }.
 *  - availableUnique: v_inheritPoint.php:50-63 — GameConst::$allItems 중첩 순회, amount != 0 필터 →
 *    buildItemClass(itemKey) → { title: getName(), rawName: getRawName(), info: getInfo() }.
 *
 * 모든 문자열은 실제 PHP 실행 캡처(php:8.3-cli Docker, probe 2회 실행 byte-identical 확인)에서 추출 —
 * 각 항목 주석의 hwe/sammo/... file:line 이 출처. 날조 없음. 삽입 순서 = PHP 순회 순서(동결 회귀 대상).
 *
 * 명마/무기/서적 패밀리의 name/info 는 PHP 가 BaseStatItem 생성자(BaseStatItem.php:24-31)에서
 * 클래스명 토큰으로 동적 생성(sprintf)하므로 동일 계산을 [statEntry]가 재현하고, 일부 유니크 클래스가
 * 생성자에서 덧붙이는 info 접미사([STAT_INFO_SUFFIX])만 클래스 파일에서 그대로 전사했다.
 */
object InheritCatalog {

    /**
     * v_inheritPoint.php:41-48 — 키 순서 = GameConst.availableSpecialWar(GameConstBase.php:192-197) 순서.
     * 내부 맵 키 순서 = PHP 배열 리터럴 순서(title → info).
     */
    fun availableSpecialWar(): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, Map<String, String>>()
        for (key in GameConst.availableSpecialWar) {
            // 날조 금지 — 카탈로그 미포팅 키는 조용히 빠뜨리지 않고 fail-loud.
            out[key] = SPECIAL_WAR[key] ?: error("미포팅 전투특기 카탈로그 키: $key")
        }
        return out
    }

    /**
     * v_inheritPoint.php:50-63 — GameConst.allItems(GameConstBase.php:258-) 중첩 순회 순서 그대로,
     * amount != 0 만 포함. 내부 맵 키 순서 = PHP 배열 리터럴 순서(title → rawName → info).
     */
    fun availableUnique(): Map<String, Map<String, String>> {
        val out = LinkedHashMap<String, Map<String, String>>()
        for ((_, subItems) in GameConst.allItems) {
            for ((itemKey, amount) in subItems) {
                if (amount == 0) continue
                out[itemKey] = statEntry(itemKey)
                    ?: TOOL_UNIQUE[itemKey]
                    ?: error("미포팅 유니크 아이템 카탈로그 키: $itemKey")
            }
        }
        return out
    }

    /**
     * BaseStatItem 생성자(BaseStatItem.php:24-31)의 충실 재현 — 클래스명 토큰에서
     * statValue(토큰[-2]) / rawName(토큰[-1]) / statNick(토큰[-3] → ITEM_TYPE)을 파싱해
     *   name = sprintf('%s(+%d)', rawName, statValue)   (BaseStatItem.php:30)
     *   info = sprintf('%s +%d', statNick, statValue)   (BaseStatItem.php:31)
     * 을 생성한다. 일부 유니크 서브클래스는 생성자에서 info 를 덧붙이므로([STAT_INFO_SUFFIX]) 가산.
     * 명마/무기/서적이 아닌 코드(도구 등)는 null → 호출측이 [TOOL_UNIQUE] 리터럴로 폴백.
     */
    private fun statEntry(code: String): Map<String, String>? {
        val tokens = code.split('_')
        if (tokens.size < 3) return null
        val statNick = STAT_NICK[tokens[tokens.size - 3]] ?: return null
        val statValue = tokens[tokens.size - 2].toIntOrNull() ?: return null
        val rawName = tokens[tokens.size - 1]
        return linkedMapOf(
            "title" to "$rawName(+$statValue)",
            "rawName" to rawName,
            "info" to "$statNick +$statValue" + (STAT_INFO_SUFFIX[code] ?: ""),
        )
    }

    /** BaseStatItem.php:16-20 ITEM_TYPE — 카테고리 토큰 → statNick(통솔/무력/지력). */
    private val STAT_NICK: Map<String, String> = linkedMapOf(
        "명마" to "통솔",
        "무기" to "무력",
        "서적" to "지력",
    )

    /**
     * +stat 유니크 중 클래스 생성자에서 `$this->info .= "..."` 로 전투 설명을 덧붙이는 15종 —
     * 접미사 문자열은 각 클래스 파일(hwe/sammo/ActionItem/<코드>.php, 주석의 행 번호)에서 그대로 전사.
     */
    private val STAT_INFO_SUFFIX: Map<String, String> = linkedMapOf(
        "che_명마_07_백마" to "<br>[전투] 전투 종료로 인한 부상 없음", // ActionItem/che_명마_07_백마.php:18
        "che_명마_07_기주마" to "<br>[전투] 공격 시 페이즈 +1", // ActionItem/che_명마_07_기주마.php:13
        "che_명마_07_백상" to "<br>[전투] 공격력 +20%, 소모 군량 +10%, 공격 시 페이즈 -1", // ActionItem/che_명마_07_백상.php:14
        "che_명마_12_사륜거" to "<br>[전투] 전투 종료로 인한 부상 없음", // ActionItem/che_명마_12_사륜거.php:18
        "che_명마_12_옥란백용구" to "<br>[전투] 남은 병력이 적을수록 회피 확률 증가. 최대 +50%p", // ActionItem/che_명마_12_옥란백용구.php:13
        "che_무기_07_맥궁" to "<br>[전투] 새로운 상대와 전투 시 20% 확률로 저격 발동, 성공 시 사기+20", // ActionItem/che_무기_07_맥궁.php:17
        "che_무기_09_동호비궁" to "<br>[전투] 새로운 상대와 전투 시 20% 확률로 저격 발동, 성공 시 사기+20", // ActionItem/che_무기_09_동호비궁.php:17
        "che_무기_11_이광궁" to "<br>[전투] 새로운 상대와 전투 시 10% 확률로 저격 발동, 성공 시 사기+10", // ActionItem/che_무기_11_이광궁.php:17
        "che_무기_13_양유기궁" to "<br>[전투] 새로운 상대와 전투 시 10% 확률로 저격 발동, 성공 시 사기+10", // ActionItem/che_무기_13_양유기궁.php:17
        "che_서적_07_위료자" to "<br>[전투] 계략 시도 확률 +20%p", // ActionItem/che_서적_07_위료자.php:13
        "che_서적_07_사마법" to "<br>[전투] 상대의 계략을 10% 확률로 되돌림", // ActionItem/che_서적_07_사마법.php:19
        "che_서적_07_논어" to "<br>[전투] 상대의 계략 성공 확률 -10%p", // ActionItem/che_서적_07_논어.php:18
        "che_서적_08_전론" to "<br>[전투] 계략 성공 시 대미지 +20%", // ActionItem/che_서적_08_전론.php:13
        "che_서적_11_춘추전" to "<br>[전투] 상대의 계략 성공 확률 -10%p", // ActionItem/che_서적_11_춘추전.php:18
        "che_서적_12_산해경" to "<br>[전투] 상대의 계략을 10% 확률로 되돌림", // ActionItem/che_서적_12_산해경.php:19
    )

    /** {title, rawName, info} 항목 빌더 — PHP 응답 키 순서(v_inheritPoint.php:57-61) 보존. */
    private fun u(title: String, rawName: String, info: String): Map<String, String> =
        linkedMapOf("title" to title, "rawName" to rawName, "info" to info)

    /** {title, info} 항목 빌더 — PHP 응답 키 순서(v_inheritPoint.php:44-46) 보존. */
    private fun e(title: String, info: String): Map<String, String> =
        linkedMapOf("title" to title, "info" to info)

    /**
     * 도구(item) 패밀리 유니크 40종 — 각 클래스의 protected $rawName/$name/$info 리터럴을
     * hwe/sammo/ActionItem/<코드>.php(주석의 행 번호 = rawName~info 선언 행)에서 그대로 전사.
     */
    private val TOOL_UNIQUE: Map<String, Map<String, String>> = linkedMapOf(
        // ActionItem/che_의술_정력견혈산.php:14-16
        "che_의술_정력견혈산" to u("정력견혈산(의술)", "정력견혈산",
            "[군사] 매 턴마다 자신(100%)과 소속 도시 장수(적 포함 50%) 부상 회복<br>[전투] 페이즈마다 40% 확률로 치료 발동(아군 피해 30% 감소, 부상 회복)"),
        // ActionItem/che_의술_청낭서.php:14-16
        "che_의술_청낭서" to u("청낭서(의술)", "청낭서",
            "[군사] 매 턴마다 자신(100%)과 소속 도시 장수(적 포함 50%) 부상 회복<br>[전투] 페이즈마다 40% 확률로 치료 발동(아군 피해 30% 감소, 부상 회복)"),
        // ActionItem/che_의술_태평청령.php:14-16
        "che_의술_태평청령" to u("태평청령(의술)", "태평청령",
            "[군사] 매 턴마다 자신(100%)과 소속 도시 장수(적 포함 50%) 부상 회복<br>[전투] 페이즈마다 40% 확률로 치료 발동(아군 피해 30% 감소, 부상 회복)"),
        // ActionItem/che_의술_상한잡병론.php:14-16
        "che_의술_상한잡병론" to u("상한잡병론(의술)", "상한잡병론",
            "[군사] 매 턴마다 자신(100%)과 소속 도시 장수(적 포함 50%) 부상 회복<br>[전투] 페이즈마다 40% 확률로 치료 발동(아군 피해 30% 감소, 부상 회복)"),
        // ActionItem/che_보물_도기.php:15-17
        "che_보물_도기" to u("도기(보물)", "도기",
            "[개인] 판매 시 장수 소지금과 국고에 금, 쌀 중 하나를 추가 (총 +10,000, 2년마다 +5,000)"),
        // ActionItem/che_조달_주판.php:9-11
        "che_조달_주판" to u("주판(조달)", "주판",
            "[내정] 물자조달 성공 확률 +20%p, 물자조달 획득량 +100%p"),
        // ActionItem/che_내정_납금박산로.php:9-11
        "che_내정_납금박산로" to u("납금박산로(내정)", "납금박산로",
            "[내정] 내정 성공률 +15%p"),
        // ActionItem/che_전략_평만지장도.php:9-11
        "che_전략_평만지장도" to u("평만지장도(전략)", "평만지장도",
            "[전략] 국가전략 사용시 재사용 대기 기간 -20%"),
        // ActionItem/che_숙련_동작.php:8-10
        "che_숙련_동작" to u("동작(숙련)", "동작",
            "숙련 +20%"),
        // ActionItem/che_명성_구석.php:8-10
        "che_명성_구석" to u("구석(명성)", "구석",
            "명성 +20%"),
        // ActionItem/che_척사_오악진형도.php:10-12
        "che_척사_오악진형도" to u("오악진형도(척사)", "오악진형도",
            "[전투] 지역·도시 병종 상대로 대미지 +15%, 아군 피해 -15%"),
        // ActionItem/che_격노_구정신단경.php:13-15
        "che_격노_구정신단경" to u("구정신단경(격노)", "구정신단경",
            "[전투] 상대방 필살 시 격노(필살) 발동, 회피 시도시 25% 확률로 격노 발동, 공격 시 일정 확률로 진노(1페이즈 추가), 격노마다 대미지 5% 추가 중첩"),
        // ActionItem/che_징병_낙주.php:10-12
        "che_징병_낙주" to u("낙주(징병)", "낙주",
            "[군사] 징·모병비 -30%<br>[기타] 통솔 순수 능력치 보정 +15%, 징병/모병/소집해제 시 인구 변동 없음"),
        // ActionItem/che_저격_매화수전.php:15-17
        "che_저격_매화수전" to u("매화수전(저격)", "매화수전",
            "[전투] 새로운 상대와 전투 시 50% 확률로 저격 발동, 성공 시 사기+20"),
        // ActionItem/che_저격_비도.php:15-17
        "che_저격_비도" to u("비도(저격)", "비도",
            "[전투] 새로운 상대와 전투 시 50% 확률로 저격 발동, 성공 시 사기+20"),
        // ActionItem/che_위압_조목삭.php:13-15
        "che_위압_조목삭" to u("조목삭(위압)", "조목삭",
            "[전투] 첫 페이즈 위압 발동(적 공격, 회피 불가, 사기 5 감소)"),
        // ActionItem/che_공성_묵자.php:12-14
        "che_공성_묵자" to u("묵자(공성)", "묵자",
            "[전투] 성벽 공격 시 대미지 +50%"),
        // ActionItem/che_집중_전국책.php:9-11
        "che_집중_전국책" to u("전국책(집중)", "전국책",
            "[전투] 계략 성공 시 대미지 +30%"),
        // ActionItem/che_환술_논어집해.php:9-11
        "che_환술_논어집해" to u("논어집해(환술)", "논어집해",
            "[전투] 계략 성공 확률 +10%p, 계략 성공 시 대미지 +20%"),
        // ActionItem/che_진압_박혁론.php:12-14
        "che_진압_박혁론" to u("박혁론(진압)", "박혁론",
            "[전투] 상대의 계략 되돌림, 격노 불가"),
        // ActionItem/che_부적_태현청생부.php:18-20
        "che_부적_태현청생부" to u("태현청생부(부적)", "태현청생부",
            "[전투] 저격 불가, 부상 없음"),
        // ActionItem/che_저지_삼황내문.php:12-14
        "che_저지_삼황내문" to u("삼황내문(저지)", "삼황내문",
            "[전투] 수비 시 첫 페이즈 저지, 50% 확률로 2 페이즈 저지"),
        // ActionItem/che_행동_서촉지형도.php:8-10
        "che_행동_서촉지형도" to u("서촉지형도(행동)", "서촉지형도",
            "[전투] 공격 시 페이즈 + 2"),
        // ActionItem/che_간파_노군입산부.php:8-10
        "che_간파_노군입산부" to u("노군입산부(간파)", "노군입산부",
            "[전투] 상대 회피 확률 -25%p, 상대 필살 확률 -10%p"),
        // ActionItem/che_불굴_상편.php:15-17
        "che_불굴_상편" to u("상편(불굴)", "상편",
            "[전투] 남은 병력이 적을수록 공격력 증가. 최대 +60%"),
        // ActionItem/che_약탈_옥벽.php:12-14
        "che_약탈_옥벽" to u("옥벽(약탈)", "옥벽",
            "[전투] 새로운 상대와 전투 시 20% 확률로 상대 금, 쌀 10% 약탈"),
        // ActionItem/che_농성_주서음부.php:9-11
        "che_농성_주서음부" to u("주서음부(농성)", "주서음부",
            "[계략] 장수 주둔 도시 화계·탈취·파괴·선동 : 성공률 -30%p<br>[전투] 상대 계략 시도 확률 -10%p, 상대 계략 성공 확률 -10%p"),
        // ActionItem/che_농성_위공자병법.php:9-11
        "che_농성_위공자병법" to u("위공자병법(농성)", "위공자병법",
            "[계략] 장수 주둔 도시 화계·탈취·파괴·선동 : 성공률 -30%p<br>[전투] 상대 계략 시도 확률 -10%p, 상대 계략 성공 확률 -10%p"),
        // ActionItem/che_계략_육도.php:8-10
        "che_계략_육도" to u("육도(계략)", "육도",
            "[계략] 화계·탈취·파괴·선동 : 성공률 +20%p<br>[전투] 계략 시도 확률 +10%p, 계략 성공 확률 +10%p"),
        // ActionItem/che_계략_삼략.php:8-10
        "che_계략_삼략" to u("삼략(계략)", "삼략",
            "[계략] 화계·탈취·파괴·선동 : 성공률 +20%p<br>[전투] 계략 시도 확률 +10%p, 계략 성공 확률 +10%p"),
        // ActionItem/che_상성보정_과실주.php:11-13
        "che_상성보정_과실주" to u("과실주(상성)", "과실주",
            "[전투] 대등/유리한 병종 전투시 공격력 +10%, 피해 -10%"),
        // ActionItem/che_능력치_지력_이강주.php:13-15
        "che_능력치_지력_이강주" to u("이강주(지력)", "이강주",
            "[능력치] 지력 +5 +(4년마다 +1)"),
        // ActionItem/che_능력치_무력_두강주.php:13-15
        "che_능력치_무력_두강주" to u("두강주(무력)", "두강주",
            "[능력치] 무력 +5 +(4년마다 +1)"),
        // ActionItem/che_능력치_통솔_보령압주.php:13-15
        "che_능력치_통솔_보령압주" to u("보령압주(통솔)", "보령압주",
            "[능력치] 통솔 +5 +(4년마다 +1)"),
        // ActionItem/che_훈련_철벽서.php:8-10
        "che_훈련_철벽서" to u("철벽서(훈련)", "철벽서",
            "[전투] 훈련 보정 +15"),
        // ActionItem/che_훈련_단결도.php:8-10
        "che_훈련_단결도" to u("단결도(훈련)", "단결도",
            "[전투] 훈련 보정 +15"),
        // ActionItem/che_사기_춘화첩.php:8-10
        "che_사기_춘화첩" to u("춘화첩(사기)", "춘화첩",
            "[전투] 사기 보정 +15"),
        // ActionItem/che_사기_초선화.php:8-10
        "che_사기_초선화" to u("초선화(사기)", "초선화",
            "[전투] 사기 보정 +15"),
        // ActionItem/che_회피_태평요술.php:8-10
        "che_회피_태평요술" to u("태평요술(회피)", "태평요술",
            "[전투] 회피 확률 +20%p"),
        // ActionItem/che_필살_둔갑천서.php:8-10
        "che_필살_둔갑천서" to u("둔갑천서(필살)", "둔갑천서",
            "[전투] 필살 확률 +20%p"),
    )

    /**
     * 전투특기 20종 — 각 클래스의 protected $name/$info 리터럴을
     * hwe/sammo/ActionSpecialWar/<코드>.php(주석의 행 번호 = name/info 선언 행)에서 그대로 전사.
     */
    private val SPECIAL_WAR: Map<String, Map<String, String>> = linkedMapOf(
        // ActionSpecialWar/che_귀병.php:12-13
        "che_귀병" to e("귀병",
            "[군사] 귀병 계통 징·모병비 -10%<br>[전투] 계략 성공 확률 +20%p,<br>공격시 상대 병종에/수비시 자신 병종 숙련에 귀병 숙련을 가산"),
        // ActionSpecialWar/che_신산.php:11-12
        "che_신산" to e("신산",
            "[계략] 화계·탈취·파괴·선동 : 성공률 +10%p<br>[전투] 계략 시도 확률 +20%p, 계략 성공 확률 +20%p"),
        // ActionSpecialWar/che_환술.php:11-12
        "che_환술" to e("환술",
            "[전투] 계략 성공 확률 +10%p, 계략 성공 시 대미지 +30%"),
        // ActionSpecialWar/che_집중.php:11-12
        "che_집중" to e("집중",
            "[전투] 계략 성공 시 대미지 +50%"),
        // ActionSpecialWar/che_신중.php:11-12
        "che_신중" to e("신중",
            "[전투] 계략 성공 확률 100%"),
        // ActionSpecialWar/che_반계.php:16-17
        "che_반계" to e("반계",
            "[전투] 상대의 계략 성공 확률 -10%p, 상대의 계략을 40% 확률로 되돌림, 반목 성공시 대미지 추가(+60% → +150%)"),
        // ActionSpecialWar/che_보병.php:12-13
        "che_보병" to e("보병",
            "[군사] 보병 계통 징·모병비 -10%<br>[전투] 공격 시 아군 피해 -10%, 수비 시 아군 피해 -20%,<br>공격시 상대 병종에/수비시 자신 병종 숙련에 보병 숙련을 가산"),
        // ActionSpecialWar/che_궁병.php:12-13
        "che_궁병" to e("궁병",
            "[군사] 궁병 계통 징·모병비 -10%<br>[전투] 회피 확률 +20%p,<br>공격시 상대 병종에/수비시 자신 병종 숙련에 궁병 숙련을 가산"),
        // ActionSpecialWar/che_기병.php:12-13
        "che_기병" to e("기병",
            "[군사] 기병 계통 징·모병비 -10%<br>[전투] 수비 시 대미지 +10%, 공격 시 대미지 +20%,<br>공격시 상대 병종에/수비시 자신 병종 숙련에 기병 숙련을 가산"),
        // ActionSpecialWar/che_공성.php:13-14
        "che_공성" to e("공성",
            "[군사] 차병 계통 징·모병비 -10%<br>[전투] 성벽 공격 시 대미지 +100%,<br>공격시 상대 병종에/수비시 자신 병종 숙련에 차병 숙련을 가산"),
        // ActionSpecialWar/che_돌격.php:15-16
        "che_돌격" to e("돌격",
            "[전투] 공격 시 대등/유리한 병종에게는 퇴각 전까지 전투, 공격 시 페이즈 + 2, 공격 시 대미지 +5%"),
        // ActionSpecialWar/che_무쌍.php:14-15
        "che_무쌍" to e("무쌍",
            "[전투] 대미지 +5%, 피해 -2%, 공격 시 필살 확률 +10%p, <br>승리 수의 로그 비례로 대미지 상승(10회 ⇒ +5%, 40회 ⇒ +15%)<br>승리 수의 로그 비례로 피해 감소(10회 ⇒ -2%, 40회 ⇒ -6%)"),
        // ActionSpecialWar/che_견고.php:18-19
        "che_견고" to e("견고",
            "[전투] 상대 필살 확률 -20%p, 상대 계략 시도시 성공 확률 -10%p, 부상 없음, 아군 피해 -10%"),
        // ActionSpecialWar/che_위압.php:14-15
        "che_위압" to e("위압",
            "[전투] 첫 페이즈 위압 발동(적 공격, 회피 불가, 사기 5 감소)"),
        // ActionSpecialWar/che_저격.php:14-15
        "che_저격" to e("저격",
            "[전투] 새로운 상대와 전투 시 50% 확률로 저격 발동, 성공 시 사기+20"),
        // ActionSpecialWar/che_필살.php:16-17
        "che_필살" to e("필살",
            "[전투] 필살 확률 +30%p, 필살 발동시 대상 회피 불가, 필살 계수 향상"),
        // ActionSpecialWar/che_징병.php:11-12
        "che_징병" to e("징병",
            "[군사] 징병/모병 시 훈사 70/84 제공<br>[기타] 통솔 순수 능력치 보정 +25%, 징병/모병/소집해제 시 인구 변동 없음"),
        // ActionSpecialWar/che_의술.php:17-18
        "che_의술" to e("의술",
            "[군사] 매 턴마다 자신(100%)과 소속 도시 장수(적 포함 50%) 부상 회복<br>[전투] 페이즈마다 40% 확률로 치료 발동(아군 피해 30% 감소, 부상 회복)"),
        // ActionSpecialWar/che_격노.php:14-15
        "che_격노" to e("격노",
            "[전투] 상대방 필살 시 격노(필살) 발동, 회피 시도시 25% 확률로 격노 발동, 공격 시 일정 확률로 진노(1페이즈 추가), 격노마다 대미지 20% 추가 중첩"),
        // ActionSpecialWar/che_척사.php:11-12
        "che_척사" to e("척사",
            "[전투] 지역·도시 병종 상대로 대미지 +20%, 아군 피해 -20%"),
    )
}
