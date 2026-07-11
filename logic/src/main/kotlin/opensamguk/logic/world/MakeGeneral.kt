package opensamguk.logic.world

import opensamguk.common.constants.GameConst
import opensamguk.common.rng.RandUtil

/**
 * MakeGeneral — 충실 포팅: PHP `hwe/sammo/API/General/Join.php` 의 인라인 장수생성(create-general)
 * 루틴 (Join.php:225-392). PHP 에는 `MakeGeneral` 클래스가 없으며 `Join::launch` 안에 인라인돼 있다
 * (시드 라벨 `'MakeGeneral'` 은 시드 문자열의 2번째 토큰일 뿐).
 *
 * 신규 장수의 **RNG-유도 속성**만 draw-for-draw 로 산출한다(상수 필드 gold/rice/killturn/crewtype 등과
 * general/general_turn/rank_data INSERT 는 B1 인테이크+데몬 단계에서 이 결과를 소비). 드로우 순서는
 * Join.php 의 **라인 실행순**(패러티 타깃 — 한 드로우라도 어긋나면 하류 전체가 desync):
 *
 *   1. nextBool(0.01)               천재 여부              (Join.php:264)
 *   2. choice(cityPool)             공백지(level 5~6, nation=0) 출생 도시  (283)
 *   3. nextRangeInt(3,5)            보너스 스탯 횟수 N      (293)
 *   4. choiceUsingWeight([L,S,I]) ×N  보너스 분배(0=통,1=무,2=지)  (293-294)
 *   5. nextRangeInt(0,1)            나이 보정              (314)
 *   6. [천재면] pickSpecialWar       전투특기 sub-stream    (321)
 *   7. getRandTurn 2 draws          턴타임(초, 마이크로분수) (369)  ← personality/affinity 보다 먼저
 *   8. choice(availablePersonality) 성격(랜덤시만)          (388)
 *   9. nextRangeInt(1,150)          상성                  (392)
 *
 * 패러티 주의:
 * - **보너스 가중치 = 폼 입력 L/S/I(보너스 적용 전)** 를 N회 내내 동일 사용. PHP 는 가중치 배열을 루프에서
 *   매번 재평가하지만 `$leadership` 재대입(307)이 루프(293-294) **뒤**라 사실상 폼값 고정이다.
 * - **pickSpecialWar 는 post-bonus L/S/I** 를 받는다 — PHP 가 `$leadership = $leadership + $pleadership`
 *   재대입(307)을 천재 특기 선발(321) **앞**에서 수행하기 때문.
 * - **getRandTurn(369) 은 성격(388)·상성(392) 보다 먼저** 실행된다(라인 순서). 턴타임 드로우 2개가
 *   성격/상성 드로우 앞에 와야 byte-parity.
 */
object MakeGeneral {

    /** Join.php 의 RNG-유도 산출물 (상수/폼/세션 필드는 호출측이 결합). */
    data class Result(
        val genius: Boolean,
        val cityId: Int,
        val bonusCount: Int,        // N (= nextRangeInt(3,5))
        val bonusLeadership: Int,   // dL
        val bonusStrength: Int,     // dS
        val bonusIntel: Int,        // dI
        val leadership: Int,        // 폼 + dL
        val strength: Int,          // 폼 + dS
        val intel: Int,             // 폼 + dI
        val politics: Int,
        val charm: Int,
        val age: Int,               // 20 + (dL+dS+dI)*2 - nextRangeInt(0,1)
        val special2: String,       // 천재면 전투특기 id, 아니면 GameConst.defaultSpecialWar("None")
        val personal: String,       // 폼 성격, 'Random'/무효면 choice 결과
        val affinity: Int,          // nextRangeInt(1,150)
        val turntimeSecond: Int,    // getRandTurn: nextRangeInt(0, 60*turnterm-1)
        val turntimeFraction: Int,  // getRandTurn: nextRangeInt(0, 999999)
    )

    /**
     * @param rng                  Join 시드(RandUtil(LiteHashDRBG(serializeSeed(hiddenSeed,"MakeGeneral",userID,now))))
     * @param formLeadership/Strength/Intel  폼 입력 스탯(보너스 적용 전)
     * @param cityPool             공백지 도시 id 목록 — PHP 쿼리 순서 그대로 (choice 인덱스가 패러티)
     * @param availablePersonality GameConst.availablePersonality (10종)
     * @param character            폼 성격. availablePersonality 에 없으면(예: 'Random') choice 드로우
     * @param turnterm             game_env turnterm (분)
     * @param geniusProb           천재 확률 (Join.php:264 "현재 1%" = 0.01)
     */
    fun draw(
        rng: RandUtil,
        formLeadership: Int,
        formStrength: Int,
        formIntel: Int,
        formPolitics: Int = 50,
        formCharm: Int = 50,
        cityPool: List<Int>,
        availablePersonality: List<String>,
        character: String,
        turnterm: Int,
        geniusProb: Double = 0.01,
        geniusRemaining: Int = GameConst.defaultMaxGenius,
        inheritSpecial: String? = null,
        inheritCity: Int? = null,
        inheritBonusStat: List<Int>? = null,
        inheritTurntimeZone: Int? = null,
    ): Result {
        val geniusCandidate = inheritSpecial != null || rng.nextBool(geniusProb)
        val genius = geniusCandidate && geniusRemaining > 0

        val cityId = inheritCity ?: rng.choice(cityPool)

        var dL = 0
        var dS = 0
        var dI = 0
        val n = if (inheritBonusStat != null) {
            require(inheritBonusStat.size == 3)
            dL = inheritBonusStat[0]
            dS = inheritBonusStat[1]
            dI = inheritBonusStat[2]
            dL + dS + dI
        } else {
            val count = rng.nextRangeInt(3, 5)
            val weights = linkedMapOf(
                "0" to formLeadership.toDouble(),
                "1" to formStrength.toDouble(),
                "2" to formIntel.toDouble(),
            )
            repeat(count) {
                when (rng.choiceUsingWeight(weights)) {
                    "0" -> dL++
                    "1" -> dS++
                    "2" -> dI++
                }
            }
            count
        }
        val leadership = formLeadership + dL
        val strength = formStrength + dS
        val intel = formIntel + dI

        // 5. 나이 — Join.php:314.
        val age = 20 + (dL + dS + dI) * 2 - rng.nextRangeInt(0, 1)

        // 6. 천재 전투특기 — Join.php:316-331. post-bonus L/S/I, dex 전부 0.
        val special2 = if (genius) {
            inheritSpecial ?: SpecialityHelper.pickSpecialWar(rng, leadership, strength, intel, 0, 0, 0, 0, 0)
        } else {
            GameConst.defaultSpecialWar // "None"
        }

        // 7. 턴타임 — Join.php:369 (getRandTurn). 성격/상성 드로우보다 먼저.
        val turnSeconds = 60 * turnterm
        val turntimeSecond = if (inheritTurntimeZone == null) {
            rng.nextRangeInt(0, turnSeconds - 1)
        } else {
            inheritTurntimeZone * turnterm + rng.nextRangeInt(0, (turnterm - 1).coerceAtLeast(0))
        }
        val turntimeFraction = rng.nextRangeInt(0, 999999)

        // 8. 성격 — Join.php:388-389. 폼값이 availablePersonality 에 없으면(='Random') 무작위.
        val personal = if (character in availablePersonality) character else rng.choice(availablePersonality)

        // 9. 상성 — Join.php:392.
        val affinity = rng.nextRangeInt(1, 150)

        return Result(
            genius = genius,
            cityId = cityId,
            bonusCount = n,
            bonusLeadership = dL,
            bonusStrength = dS,
            bonusIntel = dI,
            leadership = leadership,
            strength = strength,
            intel = intel,
            politics = formPolitics,
            charm = formCharm,
            age = age,
            special2 = special2,
            personal = personal,
            affinity = affinity,
            turntimeSecond = turntimeSecond,
            turntimeFraction = turntimeFraction,
        )
    }
}
