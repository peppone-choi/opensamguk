// 현 상세 읽기(계약판 K4-04 `GET /api/counties/{cityId}`, C10) — 미리 연결. React 없음.
//
// 받는 칸은 C9 계약판 「K4 생산자 후속 타입 · ACL 합의」(C10 `2026-10-05-c10-k4-dto-accepted-fields.md`, K4 회신 반영)의 모양 그대로다:
// `indicators`(엔진 키 일곱, 민심만 소수) · `grade{code,label}` · `garrison` · `peopleHere`(첫 판 null) · `unavailableReasons`.
// 첫 판에 null 인 `front` · `seasonalEvent` · `income` 은 받지 않는다. 군단 줄 · 「적」 칩은 원천이 없어 서버 대기로 둔다.
// C10 경로가 main 에 들어오기 전에는 부르지 않는다 — 없는 경로를 불러 404 · 콘솔 오류를 남기지 않게.

import type { GaugeTone } from '@opensamguk/ui';

/** C10 이 `/api/counties/{cityId}` 를 main 에 넣으면 true 로 켜는 PR 을 낸다. */
export const COUNTY_DETAIL_READY = false;

export interface CountyGarrison {
    readonly troops: number;
    readonly training: number;
    readonly morale: number;
}

/** 지표 하나. 첫 판의 `trend` 는 늘 null 이고, 승인 보드에 추세 표시가 없어 와도 그리지 않는다. */
interface DetailIndicator {
    readonly value: number;
    readonly max: number;
    readonly trend?: null;
}

/** 엔진 지표 키 일곱(`defence` 철자는 새 상세만 — front-info 는 `defense`). 칸마다 null 일 수 있다. */
export interface CountyIndicators {
    readonly population: DetailIndicator | null;
    readonly agriculture: DetailIndicator | null;
    readonly commerce: DetailIndicator | null;
    readonly security: DetailIndicator | null;
    /** 민심은 소수(Double), 상한 100.0. */
    readonly trust: DetailIndicator | null;
    readonly defence: DetailIndicator | null;
    readonly wall: DetailIndicator | null;
}

export type PersonHereRelation = 'SELF' | 'RETINUE' | 'SAME_NATION' | 'OTHER' | 'UNKNOWN';

/** 이 현에 있는 인물 한 명(첫 판은 배열 자체가 null). `OTHER` 는 「다른 세력」이지 적이 아니다. */
export interface CountyPersonHere {
    readonly generalId: number;
    readonly name: string;
    readonly portrait: { readonly picture: string | null; readonly imageServer: number };
    readonly affiliation: { readonly nationId: number; readonly name: string; readonly color: string } | null;
    readonly relation: PersonHereRelation;
}

/** 현 상세 응답 중 이 화면이 받는 칸. 값을 모르거나 권한 밖이면 null 이고, 이유는 `unavailableReasons` 에 JSON pointer 로 온다. */
export interface CountyDetailRead {
    readonly status: string;
    readonly cityId: number;
    readonly indicators?: CountyIndicators | null;
    readonly grade?: { readonly code: number; readonly label: string } | null;
    /** 시야 밖이면 null(계약판 「시야 밖은 null」). */
    readonly garrison?: CountyGarrison | null;
    /** null = 모름(서버 대기), [] = 확인된 빈 결과. */
    readonly peopleHere?: readonly CountyPersonHere[] | null;
    readonly unavailableReasons?: Readonly<Record<string, string>> | null;
}

// PARTIAL 은 일부 칸만 null(이유는 unavailableReasons) — 받은 칸은 그대로 쓴다. 수비군 원천 · 특산이 없기만 해도 PARTIAL 이라 흔하다.
const ready = (detail: CountyDetailRead | null | undefined): CountyDetailRead | null =>
    (detail?.status === 'READY' || detail?.status === 'PARTIAL' ? detail : null);
const finite = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

/** 형편 칸 한 줄. 값을 모르면 value · max 가 null 이고 화면은 그 칸만 「?」로 그린다(다른 칸은 그대로). */
export interface IndicatorCell {
    readonly label: string;
    readonly value: number | null;
    readonly max: number | null;
    readonly display?: string;
    readonly tone?: GaugeTone;
}

const INDICATOR_LABELS: readonly (readonly [keyof CountyIndicators, string])[] = [
    ['population', '호구'], ['agriculture', '전답'], ['commerce', '시장'], ['security', '치안'], ['trust', '민심'], ['defence', '방비'], ['wall', '성벽'],
];

/**
 * 7지표 — 상세가 READY · PARTIAL 이고 값이 하나라도 있을 때만. 서버는 `indicators` 를 늘 객체로 주고 시야 밖이면 일곱 칸이 다 null 이다 —
 * 그때는 null(화면은 front-info 현 값 → 「볼 수 없음」 · 서버 대기 순으로 물러난다). 「?」 일곱 개로 그리지 않는다.
 */
export function detailIndicatorCells(detail: CountyDetailRead | null | undefined): readonly IndicatorCell[] | null {
    const indicators = ready(detail)?.indicators;
    if (!indicators || INDICATOR_LABELS.every(([key]) => indicators[key] == null)) return null;
    return INDICATOR_LABELS.map(([key, label]) => {
        const it = indicators[key];
        if (!it || !finite(it.value) || !finite(it.max)) return { label, value: null, max: null };
        if (key === 'trust') return { label, value: it.value, max: it.max, display: `${Math.round(it.value)} / ${Math.round(it.max)}`, tone: it.value < 50 ? 'rust' : 'moss' };
        return { label, value: it.value, max: it.max, tone: key === 'defence' || key === 'wall' ? 'bronze' : undefined };
    });
}

/** 등급 칩 글자 — `label` 만 쓴다(`code` 로 표시 · 판정하지 않는다). 모르면 null → 칩을 그리지 않는다. */
export function gradeLabel(detail: CountyDetailRead | null | undefined): string | null {
    return ready(detail)?.grade?.label?.trim() || null;
}

/**
 * 칸이 null 인 이유 중 화면에 쓰는 것 하나 — 권한 밖(`NOT_AUTHORIZED`)이면 「볼 수 없음」 한 줄, 그 밖은 null(서버 대기 그대로).
 * 이유는 JSON pointer 로 칸마다 온다(`/garrison`, 지표는 `/indicators/<키>`). `/indicators` 는 일곱 칸이 다 권한 밖일 때만 숨긴다.
 * 코드로 다른 분기를 하지 않으니 이유 코드가 늘어도 깨지지 않는다.
 */
export function hiddenText(detail: CountyDetailRead | null | undefined, pointer: string): string | null {
    const reasons = ready(detail)?.unavailableReasons;
    if (!reasons) return null;
    const hidden = pointer === '/indicators'
        ? reasons[pointer] === 'NOT_AUTHORIZED' || INDICATOR_LABELS.every(([key]) => reasons[`/indicators/${key}`] === 'NOT_AUTHORIZED')
        : reasons[pointer] === 'NOT_AUTHORIZED';
    return hidden ? '볼 수 없는 정보입니다.' : null;
}

const RELATION_LABEL: Readonly<Record<PersonHereRelation, string | null>> = {
    SELF: '나', RETINUE: '내 부', SAME_NATION: '같은 세력', OTHER: '다른 세력', UNKNOWN: null,
};

export interface PersonHereRow {
    readonly generalId: number;
    readonly name: string;
    readonly picture: string | null;
    readonly imageServer: number;
    readonly affiliation: string | null;
    readonly relation: string | null;
}

/** 이 현에 있는 사람 — null 은 서버 대기, [] 는 「없음」. 군단 줄은 원천이 없어 늘 따로 서버 대기다. */
export function peopleHereRows(detail: CountyDetailRead | null | undefined): readonly PersonHereRow[] | null {
    const people = ready(detail)?.peopleHere;
    if (!people) return null;
    return people.map((p) => ({
        generalId: p.generalId, name: p.name, picture: p.portrait?.picture ?? null, imageServer: p.portrait?.imageServer ?? 0,
        affiliation: p.affiliation?.name ?? null, relation: RELATION_LABEL[p.relation] ?? null,
    }));
}

export function countyDetailPath(generalId: number, cityId: number): string {
    return `/api/counties/${cityId}?generalId=${generalId}`;
}

export interface GarrisonRow {
    readonly label: string;
    readonly value: string;
}

/** 수비군 세 줄(보드 V31K4County 「수비군 — 병력 · 훈련 · 사기」). 읽기가 없거나 시야 밖이면 null → 화면은 서버 대기 · 안 보임. */
export function garrisonRows(detail: CountyDetailRead | null | undefined): readonly GarrisonRow[] | null {
    const g = ready(detail)?.garrison ?? null;
    if (!g) return null;
    const n = (v: number) => v.toLocaleString('ko-KR');
    return [
        { label: '병력', value: n(g.troops) },
        { label: '훈련', value: n(g.training) },
        { label: '사기', value: n(g.morale) },
    ];
}
