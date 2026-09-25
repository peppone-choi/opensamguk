package opensamguk.logic.war.hwiha

/**
 * S3 수치 — 2026-09-23 사용자 결정으로 현행 값을 확정했다.
 *
 * 정본은 `data/curated/han/hwiha-s3-provisional-v1.json` 이다. Kotlin 런타임은 `data/curated` 를 읽지
 * 않으므로(월단평 곡선·포위 사기와 같은 방식) 같은 값을 여기 두고, `HwihaS3ProvisionalTest` 가 파일과
 * 대조해 두 곳이 갈라지지 않게 한다. 사료 수치가 아니다. 승인된 값(포위 사기·항복, 병력비 2배 등)은
 * `march-tempo-targets-v1.json` 을 옮긴 [HwihaSiegeMorale]·[HwihaSiegeRules] 쪽에 있고 여기 두지 않는다.
 */
object HwihaS3Provisional {
    /** 요격 군단이 반응할 수 있는 省 간선 수. */
    const val INTERCEPT_RANGE_PROVINCES = 1
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
    /** 縣 방비 지표가 상한일 때 성벽 패에 더하는 방어력 보정(%, 임시 균형값). */
    const val ASSAULT_MAX_DEFENCE_BONUS_PERCENT = 50

    /**
     * 강공 준비 — 포위가 이 순 수만큼 순 경계를 버텨야(siege.turns ≥ 이 값) 강공할 수 있다. 사람·NPC 같다.
     * 포위 즉시 강공으로 1~2순 만에 함락돼 구원군이 닿을 틈이 없던 것을 막는다(2026-09-23 사용자 결정: 공성 수치 조정).
     */
    const val ASSAULT_MIN_SIEGE_TURNS = 3

    /**
     * 점령군 수비대 — 함락 뒤 포위 군단이 그 縣에 수비병을 남긴다: min(방비 상한 × 이 %, 포위군 병력 × [CAPTURE_GARRISON_MAX_CORPS_PERCENT] %).
     * 부곡에서 빼서 縣 수비(defence)로 옮긴다. 수비 0 인 縣이 도착 즉시 되넘어가 두 세력이 매 순 주고받던 것을 막는다.
     */
    const val CAPTURE_GARRISON_DEFENCE_MAX_PERCENT = 30
    const val CAPTURE_GARRISON_MAX_CORPS_PERCENT = 20

    /** NPC 포위 지휘관이 강공을 고르는 최소 병력비(포위군 ÷ 수비병). */
    const val NPC_ASSAULT_MIN_RATIO = 3

    /** 포위 기록(timeline)에 남기는 최대 줄 수 — 36순(1년). */
    const val SIEGE_TIMELINE_MAX = 36

    // ── 군단 군량(출병 적재·보급선) ────────────────────────────────────────
    /** 출병 적재 — 출병하는 순간 출발지 창고망 곡으로 휴대 군량을 (병력 × 이 개월 수)까지 채운다. */
    const val DEPLOY_LOAD_MONTHS = 3

    /** 보급선 — 월 경계에 자국 縣 밖의 군단으로 (병력 × 이 개월 수)까지 모자란 군량을 보낸다. 손실 없이 경로 비용만큼 늦게 도착한다. */
    const val CONVOY_TARGET_MONTHS = 3

    // ── 부곡 군량 보충 ──────────────────────────────────────────────────────
    /** 월 경계에 부곡 휴대 군량을 (병력 × 이 개월 수)까지 채운다. */
    const val UNIT_RESUPPLY_TARGET_MONTHS = 2

    /** 휴대 군량 1 = 창고 곡 이 값. 수비병 급식(순당 100) × 한 달 3순 = 병사 한 명의 한 달 곡(기존 부곡 월 소비 1)이다. */
    const val GRAIN_PER_PROVISION = 300L

    // ── 녹봉·상사 ──────────────────────────────────────────────────────────
    /** 인물 카드 녹봉 = 명망 코스트 × 이 값(금, 월). */
    const val SALARY_MONEY_PER_RENOWN_COST = 100L

    /** 녹봉을 못 받은 달의 충성 하락. */
    const val UNPAID_SALARY_LOYALTY_LOSS = 5

    /** 상사 금 이 값마다 충성 +1. */
    const val REWARD_MONEY_PER_LOYALTY = 100L

    /** 상사 한 번에 오를 수 있는 충성 상한. */
    const val REWARD_MAX_LOYALTY_GAIN = 10

    // ── 조우 준비 실패 ─────────────────────────────────────────────────────
    /** 일시적인 봉인 상태 불일치를 재시도하는 순 수. 조우 발생 순부터 센다. */
    const val ENCOUNTER_UNAVAILABLE_RETRY_PHASES = 2

    // ── NPC 출병 ───────────────────────────────────────────────────────────
    /** NPC 가 출병 목표로 보는 최대 경로 길이(省 간선 수). */
    const val NPC_DEPLOY_MAX_EDGES = 6

    /** NPC 가 출병하려면 수비병 대비 가져야 하는 병력비. 포위 유지 최소비(2, 승인값)보다 낮게 두지 않는다. */
    const val NPC_DEPLOY_MIN_RATIO = 2

    /** NPC 구원 출병: 자기 병력이 포위 군단 병력의 이 백분율 이상이면 포위된 자국 縣으로 간다. */
    const val NPC_RELIEF_MIN_RATIO_PERCENT = 100L
}
