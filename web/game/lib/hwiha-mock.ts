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
