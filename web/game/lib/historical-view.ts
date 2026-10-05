// 역사 인물 고르기(P-E03) 보기 모델 — 계약(#1137) 값을 화면 말로 옮긴다. React 없음(단위 시험으로 고정한다).
// 계약에 없는 것(역할 · 주인 · 묶음 · 결속 · 본관 · 시작 위치 · 한도)은 만들지 않는다 — 화면이 서버 대기로 그린다.

import type { CreationStats, HistoricalCreationPerson, HistoricalStatus } from './creation-contract';

export interface NationRef {
    readonly id: number;
    readonly name: string;
    readonly color: string;
}

/** 다섯 능력(카드 한 줄 · 고른 인물 표). 이름은 인물 일람과 같다. */
export const STAT_LABELS: readonly { readonly key: keyof CreationStats; readonly short: string; readonly label: string }[] = [
    { key: 'leadership', short: '통', label: '통솔' },
    { key: 'strength', short: '무', label: '무력' },
    { key: 'intel', short: '지', label: '지력' },
    { key: 'politics', short: '정', label: '정치' },
    { key: 'charm', short: '매', label: '매력' },
];

/** 상태 거르기(서버 `status=` 하나) — 기본은 「고를 수 있음」(보드). */
export const STATUS_FILTERS: readonly { readonly value: HistoricalStatus | 'ALL'; readonly label: string }[] = [
    { value: 'AVAILABLE', label: '고를 수 있음' },
    { value: 'TAKEN', label: '다른 사람이 고름' },
    { value: 'NOT_APPEARED', label: '아직 등장 안 함' },
    { value: 'ALL', label: '전체' },
];

/**
 * 고를 수 없는 사유 — 서버 코드에 서버 문장(CreationErrorMessages)과 같은 말을 붙인다.
 * 계약은 「다른 사람이 먼저 골랐다」를 따로 주지 않는다(UNAVAILABLE 하나) — 지어내지 않는다.
 */
const UNAVAILABLE_TEXT: Readonly<Record<string, { readonly chip: string; readonly reason: string }>> = {
    HISTORICAL_PERSON_NOT_APPEARED: { chip: '아직 등장 안 함', reason: '아직 등장하지 않은 인물입니다.' },
    HISTORICAL_PERSON_UNAVAILABLE: { chip: '지금 고를 수 없음', reason: '이 인물은 지금 선택할 수 없습니다. 목록을 다시 읽어 주세요.' },
};
const UNKNOWN_UNAVAILABLE = { chip: '지금 고를 수 없음', reason: '이 인물은 지금 선택할 수 없습니다.' };

export interface HistoricalCardView {
    readonly id: number;
    readonly name: string;
    readonly portrait: string | null;
    /** 「통 75 · 무 68 · 지 50 · 정 40 · 매 60」. */
    readonly statLine: string;
    /** 소속 — 세력 이름(지도 미리보기 세력표에서), 재야, 또는 모름(세력표에 없음). */
    readonly affiliation: { readonly kind: 'nation'; readonly nation: NationRef } | { readonly kind: 'ronin' } | { readonly kind: 'unknown'; readonly nationId: number };
    readonly available: boolean;
    readonly chip: string;
    /** 고를 수 없으면 사유(누르면 사유가 열린다). */
    readonly reason: string | null;
}

export function statLine(stats: CreationStats): string {
    return STAT_LABELS.map((s) => `${s.short} ${stats[s.key]}`).join(' · ');
}

export function cardView(person: HistoricalCreationPerson, nations: ReadonlyMap<number, NationRef>): HistoricalCardView {
    const nation = person.nationId === null ? null : nations.get(person.nationId) ?? null;
    const affiliation: HistoricalCardView['affiliation'] = person.nationId === null
        ? { kind: 'ronin' }
        : nation ? { kind: 'nation', nation } : { kind: 'unknown', nationId: person.nationId };
    const unavailable = person.available ? null : UNAVAILABLE_TEXT[person.unavailableCode ?? ''] ?? UNKNOWN_UNAVAILABLE;
    return {
        id: person.historicalGeneralId,
        name: person.name,
        portrait: person.portrait,
        statLine: statLine(person.stats),
        affiliation,
        available: person.available,
        chip: unavailable ? unavailable.chip : '고를 수 있음',
        reason: unavailable ? unavailable.reason : null,
    };
}

export function affiliationText(affiliation: HistoricalCardView['affiliation']): string {
    if (affiliation.kind === 'nation') return `${affiliation.nation.name} 소속`;
    if (affiliation.kind === 'ronin') return '재야';
    return '소속 세력 확인 중';
}
