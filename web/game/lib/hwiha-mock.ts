// 새 시대 화면의 목 데이터.
//
// 시안(`docs/opensamguk/ui-new-screens-2026-09-18`)이 정한 정본 예시 상황을 그대로 쓴다:
// 플레이어 = 하후돈(조조 휘하), 200년 3월 중순, 영천군·양적현 방면, 휘하 허저·이전·조인·순욱.
//
// **이 파일은 화면을 먼저 보기 위한 임시 데이터다.** API 가 붙으면 화면은 그대로 두고 이 파일을
// 걷어낸다. 설계에서 미정인 수치는 숫자를 지어내지 않고 null 로 두어 화면이 `[미정]` 을 보이게 한다.

import type { HwihaShellIdentity } from '../components/HwihaShell';

export const MOCK_IDENTITY: HwihaShellIdentity = {
    generalName: '하후돈',
    allegiance: '조조 휘하',
    // 명망 절대값은 설계 §16 미정이다 — 시안도 `[미정]` 으로 둔다.
    renown: null,
    gameDate: '200년 3월 중순',
};

export type MockTrend = 'up' | 'down' | 'flat';

export interface MockYuedanRow {
    readonly rank: number | null;
    readonly general: string;
    readonly nation: string;
    readonly trend: MockTrend;
    readonly reasons: readonly string[];
    readonly self?: boolean;
}

/** 시안 Yuedan 의 순위표 그대로. 순위가 `null` 인 행은 시안의 `[순위]` 자리다. */
export const MOCK_YUEDAN_RANKING: readonly MockYuedanRow[] = [
    { rank: 1, general: '조조', nation: '조조', trend: 'up', reasons: ['관직', '치적'] },
    { rank: 2, general: '원소', nation: '원소', trend: 'flat', reasons: ['관직'] },
    { rank: 3, general: '유비', nation: '—', trend: 'up', reasons: ['결속 사건', '결의'] },
    { rank: null, general: '하후돈', nation: '조조', trend: 'up', reasons: ['전공', '양적현 방어'], self: true },
    { rank: null, general: '만총', nation: '조조', trend: 'down', reasons: ['발령 거절'] },
];

/** 정본 설계 §2.8 의 오르는 경로·떨어지는 경로. 방향만 쓴다 — 수치는 미정이다. */
export const MOCK_RENOWN_PATHS = {
    rising: ['전공', '치적', '관직', '결속 사건'],
    falling: ['패전', '배신', '실정', '발령 거절'],
} as const;

export interface MockRetainerRow {
    readonly name: string;
    readonly bond: string;
    readonly bondNote: string;
    readonly loyalty: string;
    readonly order: string | null;
}

/** 시안 Yuedan 의 「이탈 판정 순서」 — 충성이 낮은 인물부터. */
export const MOCK_DEPARTURE_ORDER: readonly MockRetainerRow[] = [
    { name: '순욱', bond: '명망 결속', bondNote: '아님 · 은의', loyalty: '낮음', order: '1순위' },
    { name: '조인', bond: '혈연', bondNote: '이탈이 드물다', loyalty: '보통', order: null },
    { name: '허저', bond: '향당', bondNote: '', loyalty: '높음', order: null },
];

// ── 공성(Siege) ────────────────────────────────────────────────────────────────────────────
export const MOCK_SIEGE = {
    county: '진류현',
    where: '연주 진류군 · 평지 현 · 원소 세력',
    seatNote: '진류군 치소',
    defenderNote: '방어군 없음 — 공성 가능',
    // 성 안 사정은 정찰로 본 만큼만 — 값은 미정이라 [값] 으로 둔다.
    inside: [
        { k: '방비', v: '[값]' },
        { k: '성 안 군량', v: '추정 · ▼ 줄어드는 중' },
        { k: '민심', v: '[값] · 전란으로 ▼' },
        { k: '수도 여부', v: '수도 아님' },
    ],
    scoutNote: '성 안 사정은 정찰·첩보로 본 만큼만 나온다. 마지막 정찰 1순 전.',
    supply: [
        { k: '하후돈 군단', v: '본대 · 부곡 보병' },
        { k: '포위', v: '3순째' },
        { k: '창고 연결', v: '허현 창고에서 · 연결됨' },
        { k: '군량 소모', v: '포위 중 · 순마다 ▼' },
        { k: '보급로 위 적 군단', v: '없음' },
        { k: '피로', v: '▲ 쌓이는 중' },
    ],
    supplyWarning: '보급로가 한 줄이다. 적 군단이 서 있는 구역은 보급이 지나가지 못한다. 끊기면 순 경계마다 병력이 준다.',
    timeline: [
        { when: '2월 하순', what: '포위 시작' },
        { when: '3월 상순', what: '성 안 군량 ▼' },
        { when: '3월 중순 · 지금', what: '성 안 군량 ▼' },
        { when: '3월 하순', what: '다음 순 경계' },
        { when: '4월 상순', what: '월 경계 · 아군 유지비' },
    ],
    surrender: [
        { k: '성 안 민심', v: '[값] · 낮을수록 아군 ▲' },
        { k: '성 안 군량', v: '▼ 줄어드는 중 · 아군 ▲' },
        { k: '방어 측 계책 · 견벽 의심', v: '방어 측 ▲' },
        { k: '아군 계책', v: '걸어 둔 것 없음' },
        { k: '판정 계수', v: '[미정]' },
    ],
    lastAdvice: '지난 권고 — 3월 상순 · 거절',
} as const;

// ── 포로 · 등용(Captives) ──────────────────────────────────────────────────────────────────
export interface MockCaptive {
    readonly name: string;
    readonly hanja?: string;
    readonly kind: string;
    readonly bond: string;
    readonly note: string;
}

export const MOCK_CAPTIVES: readonly MockCaptive[] = [
    { name: '고간', hanja: '高幹', kind: '역사 인물 · 유일', bond: '혈연 — 원소의 생질', note: '혈연은 이탈이 드물다. 설득이 어렵다.' },
    { name: '진류 공조', kind: '무명 · 공용 카드', bond: '은의 — 원소 쪽이 임명', note: '데려와도 옛 주인과의 연이 남는다.' },
    { name: '군후', kind: '무명 · 공용 카드', bond: '결속 없음', note: '막는 것도 돕는 것도 없다.' },
];

export const MOCK_PERSUASION = {
    target: '고간',
    stage: '자기 턴 7단계 · 현장 행동',
    placeNote: '포로와 같은 자리에 있어야 한다 — 지금 진류 방면 구역에 함께 있다.',
    costNote: '설득은 이번 순의 장수 행동이다. 쓰고 나면 이번 순에 다른 장수 행동을 할 수 없다.',
    against: [
        { k: '혈연 — 원소의 생질', v: '설득 ▼ · 이탈이 드물다' },
        { k: '옛 주인이 살아 있다', v: '설득 ▼' },
        { k: '충성', v: '[값]' },
    ],
    helping: [
        { k: '하후돈의 명망', v: '[미정] · 높을수록 ▲' },
        { k: '방금 진 전투', v: '설득 ▲' },
        { k: '향당 · 은의 · 결의', v: '해당 없음' },
    ],
    formulaNote: '판정 식과 결속별 효과는 미정이다. 성공하면 「명망」 결속으로 붙는다 — 내 명망이 떨어지면 먼저 흔들린다.',
} as const;

// ── 보급망 · 창고(Supply) ──────────────────────────────────────────────────────────────────
export interface MockWarehouseRow {
    readonly name: string;
    readonly kind: string;
    readonly money: string;
    readonly grain: string;
    readonly iron: string;
    readonly timber: string;
    readonly horses: string;
}

/** 시안 Supply 의 「창고별 재고」 그대로. `—` 는 그 자원이 그 창고에 없다는 뜻이다. */
export const MOCK_WAREHOUSES: readonly MockWarehouseRow[] = [
    { name: '허현', kind: '수도 · 국고', money: '[값]', grain: '[값]', iron: '[값]', timber: '[값]', horses: '[값]' },
    { name: '양적현', kind: '군 치소', money: '[값]', grain: '[값]', iron: '[값]', timber: '[값]', horses: '—' },
    { name: '장사현', kind: '현', money: '[값]', grain: '[값]', iron: '—', timber: '[값]', horses: '—' },
    { name: '영천 군단', kind: '야전 치중', money: '[값]', grain: '[값]', iron: '—', timber: '—', horses: '[값]' },
    { name: '윤씨현', kind: '고립', money: '[값]', grain: '[값]', iron: '—', timber: '—', horses: '—' },
];

export const MOCK_SUPPLY_BREAK = {
    title: '양적현 — 윤씨현 끊김',
    since: '3월 중순부터',
    why: '윤씨현으로 이어지는 구역 셋(양성현 · 겹현 · 부성현)에 적 군단이 서 있다. 적 군단이 선 구역은 보급이 통과하지 못한다. 소유는 바뀌지 않았다.',
    recover: '적 군단을 몰아내거나 다른 구역으로 길이 이어지면 다음 순 경계에 다시 붙는다.',
    isolated: [
        { k: '윤씨현 군량', v: '[n]순 분' },
        { k: '양성현 공사', v: '목재 미도착 · 멈춤' },
    ],
    risks: [
        { who: '조인', role: '윤씨현 현령', tag: '고립', what: '다음 상순 녹봉을 윤씨현 창고가 못 댄다. 금 [값] 부족' },
        { who: '부대', role: '소집 · 궁병 · 윤씨현 주둔', tag: '유지비 미지급', what: '쌀 [값] 부족 · 병력이 준다' },
    ],
    riskNote: '녹봉과 부대 유지비는 그 카드가 있는 곳의 망에서 나간다. 못 받으면 충성이 내려간다.',
    treasuryNote: '국고는 허현 창고에 있다. 허현이 함락되면 국고를 빼앗긴다.',
} as const;

// ── 휘하 편성(Main) ────────────────────────────────────────────────────────────────────────
export interface MockRetinueCard {
    readonly name: string;
    readonly unique: string;
    readonly bond: string;
    readonly aptitude: string;
    readonly loyalty: string;
    readonly post: string;
}

export const MOCK_RETINUE: readonly MockRetinueCard[] = [
    { name: '허저', unique: '유일', bond: '향당 · 초현', aptitude: '적성 장', loyalty: '충성 높음', post: '군단장 · 영천' },
    { name: '이전', unique: '유일', bond: '혈연 · 종족 부곡', aptitude: '적성 장·리', loyalty: '충성 높음', post: '현령 · 장사현' },
    { name: '조인', unique: '유일', bond: '혈연 · 조조의 종제', aptitude: '적성 장', loyalty: '충성 보통', post: '주공 발령 대기' },
    { name: '순욱', unique: '유일', bond: '은의 · 천거', aptitude: '적성 리·사', loyalty: '충성 낮음', post: '미배치' },
];

/** 시안 Main 의 인물 상세(허저). 능력치는 설계 미정이라 `—` 로 둔다. */
export const MOCK_RETINUE_DETAIL = {
    name: '허저',
    hanja: '許褚',
    kind: '인물 카드 · 유일',
    stats: [
        { k: '통솔', v: '—' },
        { k: '무력', v: '—' },
        { k: '지력', v: '—' },
        { k: '정치', v: '—' },
        { k: '매력', v: '—' },
    ],
    bond: { label: '향당 · 초현 사람', native: '본관 현 · 초현', note: '본관이 같은 카드(조조·하후돈)와 시너지, 고향 현에서 징병·민심 보너스.' },
    aptitudes: [
        { k: '장 · 군단', v: '적합' },
        { k: '리 · 내정', v: '—' },
        { k: '사 · 계책', v: '—' },
        { k: '사자 · 외교', v: '—' },
    ],
    stratagems: ['견벽 · 대응', '결사대 야습 · 즉시'],
    leaveNote: '이 인물이 휘하를 떠나면 카드도 덱에서 빠진다.',
    posting: [
        { k: '위치 구역', v: '영천 · 양적현' },
        { k: '자리', v: '군단장' },
        { k: '부상 · 피로', v: '없음' },
        { k: '녹봉', v: '순마다 쌀' },
        { k: '보물 칸', v: '[칸 수 미정]' },
        { k: '경험', v: '—' },
        { k: '충성', v: '높음' },
        { k: '생몰', v: '?–?' },
    ],
} as const;

export interface MockUnitCard {
    readonly unit: string;
    readonly source: string;
    readonly commander: string;
    readonly cost: string;
    readonly state: string;
}

export const MOCK_UNITS: readonly MockUnitCard[] = [
    { unit: '부곡 · 보병', source: '부곡', commander: '허저', cost: '쌀 + 철', state: '하후돈 직속' },
];

// ── 작전실(WarRoom · 메인) ────────────────────────────────────────────────────────────────
export interface MockTurnSlot {
    readonly no: string;
    readonly when: string;
    readonly what: string;
    readonly target: string;
    readonly state: '실행됨' | '예약' | '빈 순';
}

/** 시안 WarRoom 의 「명령 목록 12순」 그대로. 삼모의 24칸 큐 자리이지만 12순이고 날짜가 박힌다. */
export const MOCK_TURN_SLOTS: readonly MockTurnSlot[] = [
    { no: '01', when: '200년 3월 중순 · 21:40', what: '농지 개간', target: '장사현 · 영천군', state: '실행됨' },
    { no: '02', when: '200년 3월 하순 · 22:40', what: '훈련', target: '하후돈 군단', state: '예약' },
    { no: '03', when: '200년 4월 상순 · 23:40', what: '징병', target: '장사현 — 성에 들어가야 한다', state: '예약' },
    { no: '04', when: '200년 4월 중순 · 00:40', what: '빈 순 — 휴식', target: '', state: '빈 순' },
    { no: '05', when: '200년 4월 하순 · 01:40', what: '등용', target: '인재 탐색 결과 대기', state: '예약' },
    { no: '06', when: '200년 5월 상순 · 02:40', what: '결의', target: '이전', state: '예약' },
    { no: '07', when: '200년 5월 중순 · 03:40', what: '빈 순 — 휴식', target: '', state: '빈 순' },
    { no: '08', when: '200년 5월 하순 · 04:40', what: '빈 순 — 휴식', target: '', state: '빈 순' },
    { no: '09', when: '200년 6월 상순 · 05:40', what: '빈 순 — 휴식', target: '', state: '빈 순' },
    { no: '10', when: '200년 6월 중순 · 06:40', what: '빈 순 — 휴식', target: '', state: '빈 순' },
    { no: '11', when: '200년 6월 하순 · 07:40', what: '빈 순 — 휴식', target: '', state: '빈 순' },
    { no: '12', when: '200년 7월 상순 · 08:40', what: '빈 순 — 휴식', target: '', state: '빈 순' },
];

/**
 * 맡겨 둔 일 — 턴마다 스스로 굴러간다. 개인 턴을 쓰지 않는다.
 *
 * `tab` 은 그 일을 보는 입력 탭이다. 라벨이 탭 이름과 다른 것(설치 계책 → 계책, 발령 대기 →
 * 조정 결정)이 있으므로 이름으로 추측하지 않고 명시한다.
 */
export const MOCK_STANDING = [
    { k: '배치', v: 3, tab: '배치' },
    { k: '방침', v: 4, tab: '방침' },
    { k: '공사', v: 2, tab: '공사' },
    { k: '설치 계책', v: 1, tab: '계책' },
    { k: '발령 대기', v: 1, tab: '조정 결정' },
] as const;

/** 시안 WarRoom 지도의 구역 이름표. `미정찰` 은 빗금으로 표시한다. */
export const MOCK_MAP_PROVINCES = [
    { name: '회현', note: '하내군' },
    { name: '여양현', note: '위군' },
    { name: '백마현', note: '미정찰 · 동군' },
    { name: '형양현', note: '하남윤' },
    { name: '관도', note: '중모현 관할 · 성 없음' },
    { name: '진류현', note: '진류군' },
    { name: '옹구현', note: '미정찰 · 진류군' },
    { name: '양적현', note: '영천군 치소' },
    { name: '장사현', note: '영천군' },
    { name: '위씨현', note: '진류군' },
    { name: '진현', note: '진국' },
    { name: '양성현(襄城)', note: '영천군' },
    { name: '허현', note: '영천군' },
    { name: '언현', note: '미정찰 · 영천군 · 여남군 접경' },
] as const;

export const MOCK_MAP_LEGEND = ['조조', '원소', '기타 세력', '무주', '빗금 = 미정찰'] as const;

export const MOCK_LAST_TURN = {
    range: '200년 3월 상순 – 중순',
    state: '실행됨',
    what: '농지 개간 · 장사현',
} as const;
