package opensamguk.logic.war.hwiha

/**
 * S3 고리를 돌리려고 임시로 정한 수치 — **PROVISIONAL, 사용자 결정 대기.**
 *
 * 정본은 `data/curated/han/hwiha-s3-provisional-v1.json` 이다. Kotlin 런타임은 `data/curated` 를 읽지
 * 않으므로(월단평 곡선·포위 사기와 같은 방식) 같은 값을 여기 두고, `HwihaS3ProvisionalTest` 가 파일과
 * 대조해 두 곳이 갈라지지 않게 한다. 사료 수치가 아니다. 승인된 값(포위 사기·항복, 병력비 2배 등)은
 * `march-tempo-targets-v1.json` 을 옮긴 [HwihaSiegeMorale]·[HwihaSiegeRules] 쪽에 있고 여기 두지 않는다.
 */
object HwihaS3Provisional {
    // ── 공성 ────────────────────────────────────────────────────────────────
    /** 성 안 수비병 1명이 한 순에 먹는 곡(창고 게임 단위). warGrainBudget 기준 규모 rationUnitsPerSoldierTurn 를 런타임에 옮겼다. */
    const val GARRISON_RATION_PER_SOLDIER_TURN = 100L

    /** 포위군 「당순 완전 급식」 판정 — 부곡 휴대 군량이 (병력 × 이 개월 수 × 월 소비 1) 이상이면 급식으로 본다. 차감은 기존 월 정산이 한다. */
    const val BESIEGER_MIN_PROVISION_MONTHS = 1

    /** 항복 권고가 받아들여지는 성 안 사기 상한(10000 = 100%). */
    const val SURRENDER_DEMAND_MAX_MORALE = 3000

    /** 항복 권고가 받아들여지는 민심(city trust, 0..100) 상한. */
    const val SURRENDER_DEMAND_MAX_TRUST = 50

    /** 강공 격자에서 수비병을 나누는 성벽 패의 최대 수(방어 구역 칸 수가 더 적으면 그만큼). */
    const val ASSAULT_MAX_WALL_TOKENS = 4

    /** 성벽 패의 훈련·지휘 통솔(지휘 장수가 없는 현지 수비대). */
    const val ASSAULT_GARRISON_TRAINING = 50
    const val ASSAULT_GARRISON_LEADERSHIP = 50

    /** 성벽 패의 병종 수치 — 육상 병종 규칙 v1 의 보병 값(이동 0: 성벽은 움직이지 않는다). */
    const val ASSAULT_GARRISON_ATTACK = 100
    const val ASSAULT_GARRISON_DEFENCE = 120
    const val ASSAULT_GARRISON_RANGE = 1

    /** 성벽 방어 보정 상한(%) — 방비 wall/wallMax 비율만큼 방어력을 최대 이만큼 올린다. */
    const val ASSAULT_MAX_WALL_BONUS_PERCENT = 100

    /** NPC 포위 지휘관이 강공을 고르는 최소 병력비(포위군 ÷ 수비병). */
    const val NPC_ASSAULT_MIN_RATIO = 3

    /** 포위 기록(timeline)에 남기는 최대 줄 수 — 36순(1년). */
    const val SIEGE_TIMELINE_MAX = 36

    // ── 녹봉·상사 ──────────────────────────────────────────────────────────
    /** 인물 카드 녹봉 = 명망 코스트 × 이 값(금, 월). */
    const val SALARY_MONEY_PER_RENOWN_COST = 100L

    /** 녹봉을 못 받은 달의 충성 하락. */
    const val UNPAID_SALARY_LOYALTY_LOSS = 5

    /** 상사 금 이 값마다 충성 +1. */
    const val REWARD_MONEY_PER_LOYALTY = 100L

    /** 상사 한 번에 오를 수 있는 충성 상한. */
    const val REWARD_MAX_LOYALTY_GAIN = 10

    // ── NPC 출병 ───────────────────────────────────────────────────────────
    /** NPC 가 출병 목표로 보는 최대 경로 길이(省 간선 수). */
    const val NPC_DEPLOY_MAX_EDGES = 6

    /** NPC 가 출병하려면 수비병 대비 가져야 하는 병력비. 포위 유지 최소비(2, 승인값)보다 낮게 두지 않는다. */
    const val NPC_DEPLOY_MIN_RATIO = 2

    /** NPC 구원 출병: 자기 병력이 포위 군단 병력의 이 백분율 이상이면 포위된 자국 縣으로 간다. */
    const val NPC_RELIEF_MIN_RATIO_PERCENT = 100L
}
