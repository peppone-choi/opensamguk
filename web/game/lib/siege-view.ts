// 공성(P-C02)의 보기 모델 — `/api/sieges` · `/api/road-forts` 를 화면 줄로 바꾼다. React 없음.
//
// 짓지 않는 것: 강공 기준 순(옛 화면 프론트 상수 3 — 서버 값 `assaultReadyAt` 계약판 K6-08 보강 전까지 안 보임),
// 보루의 구역 · 세력 이름(서버 보강 전까지 provinceId · 좌표 · 세력 숫자를 보이지 않는다), 모르는 사건 코드 원문.

import type { RoadFort, Siege } from './campaign-reads';
import { phaseText } from './territory-view';

export type SiegeTone = 'bronze' | 'moss' | 'neutral';

const STATUS: Readonly<Record<string, { readonly label: string; readonly tone: SiegeTone }>> = {
    ACTIVE: { label: '포위 중', tone: 'bronze' },
    FALLEN: { label: '함락', tone: 'moss' },
    LIFTED: { label: '포위 해제', tone: 'neutral' },
};

export function siegeStatus(status: string): { readonly label: string; readonly tone: SiegeTone } {
    return STATUS[status] ?? { label: '알 수 없음', tone: 'neutral' };
}

/** 포위 사건 이름(옛 화면 표 그대로). 모르는 코드는 원문 대신 「공성 사건」. */
const EVENT: Readonly<Record<string, string>> = {
    START: '포위 시작', TURN: '포위 유지', FALLEN: '함락', LIFTED: '포위 해제',
    DEMAND_ACCEPTED: '항복 권고 수락', DEMAND_REFUSED: '항복 권고 거절',
    ASSAULT_CAPTURED: '강공 함락', ASSAULT_REPULSED: '강공 격퇴',
};

const fmt = new Intl.NumberFormat('ko-KR');
const q = (v: string | null) => v ?? '?';

export interface SiegeCell {
    readonly key: string;
    readonly value: string;
}

export interface SiegeRow {
    readonly countyId: number;
    readonly title: string;
    /** 「원소 → 수비군」 */
    readonly sides: string;
    readonly status: { readonly label: string; readonly tone: SiegeTone };
    readonly active: boolean;
    readonly turns: number;
    readonly commander: string;
    /** 6칸 — 성 안 수비 · 사기 · 쌀 · 민심 · 포위 병력 · 포위군 급식. 보이지 않는 값은 「?」. */
    readonly cells: readonly SiegeCell[];
    /** 포위군 급식 칩 — true 받는 중 · false 못 받음 · null 모름. */
    readonly fed: boolean | null;
    readonly canAct: boolean;
    readonly surrenderLikely: boolean;
    readonly startedAt: string;
    readonly timeline: readonly { readonly when: string; readonly what: string }[];
}

function timelineRow(row: Record<string, unknown>): { when: string; what: string } {
    const { year, month, phase } = row;
    const when = typeof year === 'number' && typeof month === 'number' && typeof phase === 'number'
        ? phaseText({ year, month, phase }) : '시각 모름';
    const code = typeof row.event === 'string' ? row.event : null;
    const parts = [code ? EVENT[code] ?? '공성 사건' : '공성 사건'];
    if (typeof row.morale === 'number') parts.push(`사기 ${Math.round(row.morale / 100)}%`);
    if (typeof row.garrison === 'number') parts.push(`수비 ${fmt.format(row.garrison)}`);
    return { when, what: parts.join(' · ') };
}

export function siegeRows(sieges: readonly Siege[]): SiegeRow[] {
    return sieges.map((s) => ({
        countyId: s.countyId,
        title: s.countyName ?? '이름 모를 현',
        sides: `${s.besieger.nationName ?? '포위군'} → ${s.defenderNationName ?? '수비군'}`,
        status: siegeStatus(s.status),
        active: s.status === 'ACTIVE',
        turns: s.turns,
        commander: s.besieger.name ?? '이름 모를 장수',
        cells: [
            { key: '성 안 수비', value: `${fmt.format(s.garrison)}명` },
            { key: '성 안 사기', value: `${Math.round(s.morale / 100)}%` },
            { key: '성 안 쌀', value: q(s.grain == null ? null : fmt.format(s.grain)) },
            { key: '민심', value: fmt.format(s.trust) },
            { key: '포위 병력', value: q(s.besiegerTroops == null ? null : `${fmt.format(s.besiegerTroops)}명`) },
            { key: '포위군 급식', value: s.besiegerFed == null ? '?' : s.besiegerFed ? '받는 중' : '못 받음' },
        ],
        fed: s.besiegerFed,
        canAct: s.canAct,
        surrenderLikely: s.surrenderDemandAccepted,
        startedAt: phaseText(s.startedAt),
        timeline: s.timeline.map(timelineRow),
    }));
}

export interface FortRow {
    readonly id: string;
    readonly wall: number;
    readonly garrison: number;
    /** 포위 진척 — 포위가 없으면 null. */
    readonly progress: number | null;
    readonly canBesiege: boolean;
}

/** 도로 보루 — 구역 · 세력 이름은 서버 보강(K6-08) 전까지 없다. provinceId · 좌표 · 세력 숫자는 보이지 않는다. */
export function fortRows(forts: readonly RoadFort[]): FortRow[] {
    return forts.map((f) => ({
        id: f.id,
        wall: f.wall,
        garrison: f.garrison,
        progress: f.besiegerGeneralId == null ? null : f.siegeProgress,
        canBesiege: f.canBesiege,
    }));
}

/** 함락되면(설계 §7 점령 규칙 — 보드 V31K4Siege 「함락되면」). 「국고」 대신 수도 창고. */
export function fallConsequences(countyName: string): readonly { readonly what: string; readonly then: string }[] {
    return [
        { what: '현', then: `${countyName} 전체가 넘어옵니다.` },
        { what: '창고', then: '금 · 쌀 · 철 · 목재 · 말을 빼앗습니다.' },
        { what: '수도 창고', then: '수도라면 세력의 금도 넘어옵니다.' },
        { what: '포로', then: '성 안 인물이 잡힐 수 있습니다.' },
    ];
}
