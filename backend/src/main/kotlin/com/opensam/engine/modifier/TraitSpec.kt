package com.opensam.engine.modifier

/**
 * 특기 종류
 */
enum class TraitKind {
    DOMESTIC, WAR, PERSONALITY, NATION
}

/**
 * 특기 선택 가중치 타입
 */
enum class TraitWeightType {
    /** 일반 가중치 (가중 합산 RNG) */
    NORM,
    /** 퍼센트 확률 (weight/100 확률로 우선 선택) */
    PERCENT,
}

/**
 * 특기 선택 조건 비트플래그.
 * 각 비트는 스탯 조건이나 병종 숙련 조건을 나타냄.
 * 여러 조건을 OR(|)로 합쳐서 하나의 요구조건 항목을 만듦.
 */
object TraitRequirement {
    const val DISABLED = 0x1

    const val STAT_LEADERSHIP = 0x2
    const val STAT_STRENGTH = 0x4
    const val STAT_INTEL = 0x8

    const val ARMY_FOOTMAN = 0x100
    const val ARMY_ARCHER = 0x200
    const val ARMY_CAVALRY = 0x400
    const val ARMY_WIZARD = 0x800
    const val ARMY_SIEGE = 0x1000

    const val REQ_DEXTERITY = 0x4000

    const val STAT_NOT_LEADERSHIP = 0x20000
    const val STAT_NOT_STRENGTH = 0x40000
    const val STAT_NOT_INTEL = 0x80000
}

/**
 * 특기 선택 조건 (NPC 특기 배정 시 사용)
 *
 * @param weightType 가중치 타입 (NORM: 가중 합산, PERCENT: 확률 우선)
 * @param weight 가중치 값 (NORM: 합산 비율, PERCENT: weight/100 확률)
 * @param requirements 요구조건 목록. 각 항목은 비트플래그 조합(AND).
 *                     목록 내 항목들은 OR 관계 — 하나만 충족하면 적격.
 */
data class TraitSelection(
    val weightType: TraitWeightType,
    val weight: Double,
    val requirements: List<Int>,
)

/**
 * 특기 메타데이터 (key, 이름, 설명, 종류, 선택 조건)
 */
data class TraitSpec(
    val key: String,
    val name: String,
    val info: String,
    val kind: TraitKind,
    val selection: TraitSelection? = null,
)

/**
 * 체(che) 시나리오 특기 레지스트리.
 * core2026 triggers/special/ 에서 포팅.
 */
object TraitSpecRegistry {

    // ========== 내정 특기 (8) ==========

    val domestic: List<TraitSpec> = listOf(
        TraitSpec(
            "che_인덕", "인덕",
            "[내정] 주민 선정·정착 장려 : 기본 보정 +10%, 성공률 +10%p, 비용 -20%",
            TraitKind.DOMESTIC,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_LEADERSHIP)),
        ),
        TraitSpec(
            "che_발명", "발명",
            "[내정] 기술 연구 : 기본 보정 +10%, 성공률 +10%p, 비용 -20%",
            TraitKind.DOMESTIC,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_INTEL)),
        ),
        TraitSpec(
            "che_경작", "경작",
            "[내정] 농지 개간 : 기본 보정 +10%, 성공률 +10%p, 비용 -20%",
            TraitKind.DOMESTIC,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_INTEL)),
        ),
        TraitSpec(
            "che_상재", "상재",
            "[내정] 상업 투자 : 기본 보정 +10%, 성공률 +10%p, 비용 -20%",
            TraitKind.DOMESTIC,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_INTEL)),
        ),
        TraitSpec(
            "che_축성", "축성",
            "[내정] 성벽 보수 : 기본 보정 +10%, 성공률 +10%p, 비용 -20%",
            TraitKind.DOMESTIC,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_STRENGTH)),
        ),
        TraitSpec(
            "che_수비", "수비",
            "[내정] 수비 강화 : 기본 보정 +10%, 성공률 +10%p, 비용 -20%",
            TraitKind.DOMESTIC,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_STRENGTH)),
        ),
        TraitSpec(
            "che_통찰", "통찰",
            "[내정] 치안 강화 : 기본 보정 +10%, 성공률 +10%p, 비용 -20%",
            TraitKind.DOMESTIC,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_STRENGTH)),
        ),
        TraitSpec(
            "che_귀모", "귀모",
            "[계략] 화계·탈취·파괴·선동 : 성공률 +20%p",
            TraitKind.DOMESTIC,
            TraitSelection(
                TraitWeightType.PERCENT, 2.5,
                listOf(
                    TraitRequirement.STAT_LEADERSHIP,
                    TraitRequirement.STAT_STRENGTH,
                    TraitRequirement.STAT_INTEL,
                ),
            ),
        ),
    )

    // ========== 전투 특기 (20) ==========

    val war: List<TraitSpec> = listOf(
        TraitSpec(
            "che_귀병", "귀병",
            "[군사] 귀병 계통 징·모병비 -10%<br>[전투] 계략 성공 확률 +20%p,<br>공격시 상대 병종에/수비시 자신 병종 숙련에 귀병 숙련을 가산",
            TraitKind.WAR,
            TraitSelection(
                TraitWeightType.NORM, 1.0,
                listOf(
                    TraitRequirement.STAT_INTEL or
                        TraitRequirement.ARMY_WIZARD or
                        TraitRequirement.REQ_DEXTERITY or
                        TraitRequirement.STAT_NOT_STRENGTH,
                ),
            ),
        ),
        TraitSpec(
            "che_신산", "신산",
            "[계략] 화계·탈취·파괴·선동 : 성공률 +10%p<br>[전투] 계략 시도 확률 +20%p, 계략 성공 확률 +20%p",
            TraitKind.WAR,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_INTEL)),
        ),
        TraitSpec(
            "che_환술", "환술",
            "[전투] 계략 성공 확률 +10%p, 계략 성공 시 대미지 +30%",
            TraitKind.WAR,
            TraitSelection(TraitWeightType.PERCENT, 5.0, listOf(TraitRequirement.STAT_INTEL)),
        ),
        TraitSpec(
            "che_집중", "집중",
            "[전투] 계략 성공 시 대미지 +50%",
            TraitKind.WAR,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_INTEL)),
        ),
        TraitSpec(
            "che_신중", "신중",
            "[전투] 계략 성공 확률 100%",
            TraitKind.WAR,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_INTEL)),
        ),
        TraitSpec(
            "che_반계", "반계",
            "[전투] 상대의 계략 성공 확률 -10%p, 상대의 계략을 40% 확률로 되돌림, 반목 성공시 대미지 추가(+60% → +150%)",
            TraitKind.WAR,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_INTEL)),
        ),
        TraitSpec(
            "che_보병", "보병",
            "[군사] 보병 계통 징·모병비 -10%<br>[전투] 공격 시 아군 피해 -10%, 수비 시 아군 피해 -20%,<br>공격시 상대 병종에/수비시 자신 병종 숙련에 보병 숙련을 가산",
            TraitKind.WAR,
            TraitSelection(
                TraitWeightType.NORM, 1.0,
                listOf(
                    TraitRequirement.STAT_LEADERSHIP or
                        TraitRequirement.REQ_DEXTERITY or
                        TraitRequirement.ARMY_FOOTMAN or
                        TraitRequirement.STAT_NOT_INTEL,
                    TraitRequirement.STAT_STRENGTH or
                        TraitRequirement.REQ_DEXTERITY or
                        TraitRequirement.ARMY_FOOTMAN,
                ),
            ),
        ),
        TraitSpec(
            "che_궁병", "궁병",
            "[군사] 궁병 계통 징·모병비 -10%<br>[전투] 회피 확률 +20%p,<br>공격시 상대 병종에/수비시 자신 병종 숙련에 궁병 숙련을 가산",
            TraitKind.WAR,
            TraitSelection(
                TraitWeightType.NORM, 1.0,
                listOf(
                    TraitRequirement.STAT_LEADERSHIP or
                        TraitRequirement.REQ_DEXTERITY or
                        TraitRequirement.ARMY_ARCHER or
                        TraitRequirement.STAT_NOT_INTEL,
                    TraitRequirement.STAT_STRENGTH or
                        TraitRequirement.REQ_DEXTERITY or
                        TraitRequirement.ARMY_ARCHER,
                ),
            ),
        ),
        TraitSpec(
            "che_기병", "기병",
            "[군사] 기병 계통 징·모병비 -10%<br>[전투] 수비 시 대미지 +10%, 공격 시 대미지 +20%,<br>공격시 상대 병종에/수비시 자신 병종 숙련에 기병 숙련을 가산",
            TraitKind.WAR,
            TraitSelection(
                TraitWeightType.NORM, 1.0,
                listOf(
                    TraitRequirement.STAT_LEADERSHIP or
                        TraitRequirement.REQ_DEXTERITY or
                        TraitRequirement.ARMY_CAVALRY or
                        TraitRequirement.STAT_NOT_INTEL,
                    TraitRequirement.STAT_STRENGTH or
                        TraitRequirement.REQ_DEXTERITY or
                        TraitRequirement.ARMY_CAVALRY,
                ),
            ),
        ),
        TraitSpec(
            "che_공성", "공성",
            "[군사] 차병 계통 징·모병비 -10%<br>[전투] 성벽 공격 시 대미지 +100%,<br>공격시 상대 병종에/수비시 자신 병종 숙련에 차병 숙련을 가산",
            TraitKind.WAR,
            TraitSelection(
                TraitWeightType.NORM, 1.0,
                listOf(
                    TraitRequirement.STAT_LEADERSHIP or
                        TraitRequirement.REQ_DEXTERITY or
                        TraitRequirement.ARMY_SIEGE,
                ),
            ),
        ),
        TraitSpec(
            "che_돌격", "돌격",
            "[전투] 공격 시 대등/유리한 병종에게는 퇴각 전까지 전투, 공격 시 페이즈 + 2, 공격 시 대미지 +5%",
            TraitKind.WAR,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_STRENGTH)),
        ),
        TraitSpec(
            "che_무쌍", "무쌍",
            "[전투] 대미지 +5%, 피해 -2%, 공격 시 필살 확률 +10%p, <br>승리 수의 로그 비례로 대미지 상승(10회 ⇒ +5%, 40회 ⇒ +15%)<br>승리 수의 로그 비례로 피해 감소(10회 ⇒ -2%, 40회 ⇒ -6%)",
            TraitKind.WAR,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_STRENGTH)),
        ),
        TraitSpec(
            "che_견고", "견고",
            "[전투] 상대 필살 확률 -20%p, 상대 계략 시도시 성공 확률 -10%p, 부상 없음, 아군 피해 -10%",
            TraitKind.WAR,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_STRENGTH)),
        ),
        TraitSpec(
            "che_위압", "위압",
            "[전투] 첫 페이즈 위압 발동(적 공격, 회피 불가, 사기 5 감소)",
            TraitKind.WAR,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_STRENGTH)),
        ),
        TraitSpec(
            "che_저격", "저격",
            "[전투] 새로운 상대와 전투 시 50% 확률로 저격 발동, 성공 시 사기+20",
            TraitKind.WAR,
            TraitSelection(
                TraitWeightType.NORM, 1.0,
                listOf(
                    TraitRequirement.STAT_LEADERSHIP,
                    TraitRequirement.STAT_STRENGTH,
                    TraitRequirement.STAT_INTEL,
                ),
            ),
        ),
        TraitSpec(
            "che_필살", "필살",
            "[전투] 필살 확률 +30%p, 필살 발동시 대상 회피 불가, 필살 계수 향상",
            TraitKind.WAR,
            TraitSelection(
                TraitWeightType.NORM, 1.0,
                listOf(
                    TraitRequirement.STAT_LEADERSHIP,
                    TraitRequirement.STAT_STRENGTH,
                    TraitRequirement.STAT_INTEL,
                ),
            ),
        ),
        TraitSpec(
            "che_징병", "징병",
            "[군사] 징병/모병 시 훈사 70/84 제공<br>[기타] 통솔 순수 능력치 보정 +25%, 징병/모병/소집해제 시 인구 변동 없음",
            TraitKind.WAR,
            TraitSelection(
                TraitWeightType.NORM, 1.0,
                listOf(
                    TraitRequirement.STAT_LEADERSHIP,
                    TraitRequirement.STAT_STRENGTH,
                    TraitRequirement.STAT_INTEL,
                ),
            ),
        ),
        TraitSpec(
            "che_의술", "의술",
            "[군사] 매 턴마다 자신(100%)과 소속 도시 장수(적 포함 50%) 부상 회복<br>[전투] 페이즈마다 40% 확률로 치료 발동(아군 피해 30% 감소, 부상 회복)",
            TraitKind.WAR,
            TraitSelection(
                TraitWeightType.PERCENT, 2.0,
                listOf(
                    TraitRequirement.STAT_LEADERSHIP,
                    TraitRequirement.STAT_STRENGTH,
                    TraitRequirement.STAT_INTEL,
                ),
            ),
        ),
        TraitSpec(
            "che_격노", "격노",
            "[전투] 상대방 필살 시 격노(필살) 발동, 회피 시도시 25% 확률로 격노 발동, 공격 시 일정 확률로 진노(1페이즈 추가), 격노마다 대미지 20% 추가 중첩",
            TraitKind.WAR,
            TraitSelection(TraitWeightType.NORM, 1.0, listOf(TraitRequirement.STAT_STRENGTH)),
        ),
        TraitSpec(
            "che_척사", "척사",
            "[전투] 지역·도시 병종 상대로 대미지 +20%, 아군 피해 -20%",
            TraitKind.WAR,
            TraitSelection(
                TraitWeightType.NORM, 1.0,
                listOf(
                    TraitRequirement.STAT_LEADERSHIP,
                    TraitRequirement.STAT_STRENGTH,
                    TraitRequirement.STAT_INTEL,
                ),
            ),
        ),
    )

    /** 키로 특기 조회 (모든 종류 포함) */
    private val allByKey: Map<String, TraitSpec> by lazy {
        (domestic + war).associateBy { it.key }
    }

    fun findByKey(key: String): TraitSpec? = allByKey[key]

    fun allDomestic(): List<TraitSpec> = domestic
    fun allWar(): List<TraitSpec> = war
}
