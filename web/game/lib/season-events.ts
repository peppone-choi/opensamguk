// 계절 사건(P-K07) 보기 모델 — K8. 보드 V31SystemSeason · MSeason(「현 · 사건 · 방향(▼ 민심 등)」) · V31K4County(season_band).
// 사건 한 건의 꼴은 logic `SeasonalOccurrence(countyId, kind, effect)`(logic/season/SeasonalEvents.kt)를 따른다.
// 이름 · 수치는 짓지 않는다:
//  - 종류 이름은 data/curated/han/seasonal-events.json 의 확정 label(게임 용어)이다. 모르는 종류는 「계절 사건」으로만 적는다.
//  - 효과는 방향(▼ · ▲)만 그린다. 보드에 수치 칸이 없다.
// 서버 경로는 아직 없다. 설계(#1151 layer2 §6.1)는 사건을 기록 종류 + refs 로 준다.
// 그 이름(계약판 K8-EV)이 예약되면 기록 → 사건 바꿈은 lib/api 쪽에 붙이고, 화면은 아래 상태만 받는다.

export type SeasonalEventKind = 'DROUGHT' | 'FLOOD' | 'PLAGUE' | 'LOCUST' | 'FREEZE' | 'RAINY_PASSAGE';

/** 종류 → 화면 이름(seasonal-events.json label 그대로, 단위 시험이 원장과 맞춘다). */
export const SEASON_EVENT_LABEL: Readonly<Record<SeasonalEventKind, string>> = {
    DROUGHT: '가뭄',
    FLOOD: '홍수',
    PLAGUE: '역병',
    LOCUST: '황충',
    FREEZE: '결빙',
    RAINY_PASSAGE: '우기 통행',
};

/** 패널 안내에 쓰는 종류 이름 목록(원장 순서). */
export const SEASON_EVENT_LABELS = Object.values(SEASON_EVENT_LABEL).join(' · ');

/** logic SeasonalEffect — 縣 절대 증감량. 0 · 빠진 칸은 그리지 않는다. */
export interface SeasonalEffect {
    readonly trust?: number;
    readonly population?: number;
    readonly agriculture?: number;
    readonly displaced?: number;
    readonly passageClosed?: boolean;
}

export interface SeasonalOccurrence {
    readonly countyId: number;
    /** 서버 종류 값. 모르는 값이 올 수 있어 string 으로 받는다. */
    readonly kind: string;
    readonly effect: SeasonalEffect;
}

/**
 * 화면이 받는 계절 사건 상태. 서버 읽기가 붙기 전에는 undefined 로 넘긴다(그리는 쪽이 「준비 중」이나 숨김을 고른다).
 *  - unavailable: 서버가 이번 순 사건을 셈하지 못했다 — 「사건 없음」이 아니다.
 *  - ready: 받은 사건(패널은 내 영지 것만 넘겨받는다 — 거르는 일은 읽기 쪽이 한다).
 */
export type SeasonEventsState =
    | { readonly kind: 'unavailable' }
    | { readonly kind: 'ready'; readonly occurrences: readonly SeasonalOccurrence[] };

export function seasonEventLabel(kind: string): string {
    return (SEASON_EVENT_LABEL as Record<string, string | undefined>)[kind] ?? '계절 사건';
}

export interface EffectMark {
    /** 지표 이름 — 현 상세 7지표와 같은 말(민심 · 호구 · 전답), 그 밖은 유민 · 길. */
    readonly label: string;
    readonly dir: 'down' | 'up' | 'closed';
}

/** 효과 → 방향 표시(보드 「▼ 민심」). 순서는 민심 · 호구 · 전답 · 유민 · 길. */
export function effectMarks(effect: SeasonalEffect): readonly EffectMark[] {
    const out: EffectMark[] = [];
    const signed = (label: string, v: number | undefined) => {
        if (typeof v !== 'number' || !Number.isFinite(v) || v === 0) return;
        out.push({ label, dir: v < 0 ? 'down' : 'up' });
    };
    signed('민심', effect.trust);
    signed('호구', effect.population);
    signed('전답', effect.agriculture);
    signed('유민', effect.displaced);
    if (effect.passageClosed === true) out.push({ label: '길', dir: 'closed' });
    return out;
}

/** 방향 표시 한 칸의 글자 — 「▼ 민심」 · 「▲ 유민」 · 「길 닫힘」. */
export function effectMarkText(mark: EffectMark): string {
    if (mark.dir === 'closed') return `${mark.label} 닫힘`;
    return `${mark.dir === 'down' ? '▼' : '▲'} ${mark.label}`;
}

/** 이 현의 사건. 상태가 없거나 셈하지 못했으면 빈 목록(띠를 그리지 않는다). */
export function countyEvents(state: SeasonEventsState | undefined, countyId: number | null | undefined): readonly SeasonalOccurrence[] {
    if (state?.kind !== 'ready' || countyId == null) return [];
    return state.occurrences.filter((o) => o.countyId === countyId);
}
