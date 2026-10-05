// 황실 court 칸 보기 모델(D123) — 순수 함수만(D105 층). 황통 카드의 조정 · 섭정 · 지키는 세력 칸, 공위 · 종결 줄.
// 서버가 준 이름만 쓴다. 명시 null 은 「정하지 않음」(조정 · 지키는 세력) · 「—」(섭정, 보드 그대로), 셈하지 못한 칸은 「셈하지 못함」.
import type { CourtFieldState, ImperialCourt, ImperialCourtLine } from './api/imperial-court';

/** 칸 하나의 모양. waiting 은 서버 대기(K8-10) 칸, error 는 court 읽기 실패. */
export type CourtSlot =
    | { readonly kind: 'value'; readonly text: string }
    | { readonly kind: 'unavailable' }
    | { readonly kind: 'waiting' }
    | { readonly kind: 'error' };

export interface CourtSlots {
    readonly court: CourtSlot | null; // null = court 가 없어 presence 의 조정 값을 그대로 쓴다
    readonly regent: CourtSlot;
    readonly guardian: CourtSlot;
}

/** court 읽기 상태 — 훅이 넘긴다. waiting 은 경로가 아직 없음(404) · 장수 없음. */
export type CourtReadState =
    | { readonly state: 'waiting' }
    | { readonly state: 'loading' }
    | { readonly state: 'ready'; readonly court: ImperialCourt }
    | { readonly state: 'error' };

function slot(state: CourtFieldState, id: number | null, name: string | null, unset: string): CourtSlot {
    if (state !== 'READY') return { kind: 'unavailable' };
    if (id === null) return { kind: 'value', text: unset };
    // 참조는 있는데 이름이 비면 서버가 UNAVAILABLE 로 준다(C6 문서). READY 인데 이름이 없으면 짓지 않고 셈하지 못함으로 둔다.
    return name === null ? { kind: 'unavailable' } : { kind: 'value', text: name };
}

/** ACTIVE 황통 카드(presence 배지)에 붙일 court 칸. lineCode = court lines[].code. */
export function courtSlots(read: CourtReadState, lineCode: string): CourtSlots {
    if (read.state === 'waiting' || read.state === 'loading') return { court: null, regent: { kind: 'waiting' }, guardian: { kind: 'waiting' } };
    if (read.state === 'error' || read.court.status !== 'READY') return { court: { kind: 'error' }, regent: { kind: 'error' }, guardian: { kind: 'error' } };
    const l = read.court.lines.find((x) => x.code === lineCode && x.status === 'ACTIVE');
    if (!l) return { court: { kind: 'error' }, regent: { kind: 'error' }, guardian: { kind: 'error' } }; // 두 읽기가 어긋남 — 짐작하지 않는다
    return {
        court: slot(l.fieldStates.courtCity, l.courtCityId, l.courtCityName, '정하지 않음'),
        regent: slot(l.fieldStates.regent, l.regentGeneralId, l.regentName, '—'),
        guardian: slot(l.fieldStates.courtNation, l.courtNationId, l.courtNationName, '정하지 않음'),
    };
}

/** 공위 · 종결 황통 줄(D123 ② · ③) — court READY 일 때만. 이름 · 상태만 보인다. */
export function quietLines(read: CourtReadState): { readonly vacant: readonly ImperialCourtLine[]; readonly ended: readonly ImperialCourtLine[] } {
    if (read.state !== 'ready' || read.court.status !== 'READY') return { vacant: [], ended: [] };
    return {
        vacant: read.court.lines.filter((l) => l.status === 'VACANT'),
        ended: read.court.lines.filter((l) => l.status === 'ENDED'),
    };
}
