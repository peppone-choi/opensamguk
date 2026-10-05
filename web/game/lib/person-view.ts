// 인물 상세(P-R03)의 보기 모델. React 없음(단위 시험으로 고정한다).
//
// 정본 읽기는 계약판 K4-13 `GET /api/people/{generalId}`(관계별 칸)다. 그 읽기가 오기 전에는 지금 있는 읽기로만 채운다:
//  - 나(SELF): front-info 장수(초상 · 5능력 · 부상 · 소속).
//  - 내 부 인물(RETINUE): `/api/retinue` + `/api/posts`(부 편성 P-R01 과 같은 줄 — retinueRows).
//  - 그 밖(같은 세력 · 다른 세력 · 재야 · 포로): 단건 읽기가 없어 「서버 대기」. 이름 · id 로 짐작하지 않는다.
import type { Aptitudes, Bond, FiveStats } from './campaign-reads';
import type { RetinueRow } from './retinue-view';
import type { FrontGeneralInfo, FrontNationInfo } from './types';

/** 경로의 `[generalId]` 조각 → 양의 정수만. 아니면 null(화면은 「이 인물을 찾을 수 없습니다」). */
export function parseGeneralId(raw: string | string[] | undefined | null): number | null {
    const v = Array.isArray(raw) ? raw[0] : raw;
    return v && /^[1-9]\d{0,8}$/.test(v) ? Number(v) : null;
}

export type PersonRelation = 'SELF' | 'RETINUE' | 'UNKNOWN';

export interface PersonView {
    readonly relation: PersonRelation;
    readonly generalId: number;
    readonly name: string;
    readonly picture: string | null;
    readonly imageServer: number | null;
    /** 「조조 소속」 · 「재야」. 모르면 null. */
    readonly affiliation: string | null;
    readonly nationColor: string | null;
    readonly stats: FiveStats | null;
    readonly aptitudes: Aptitudes | null;
    readonly bonds: readonly Bond[] | null;
    /** 부상 — 나만 안다(front-info). 모르면 null. */
    readonly injured: boolean | null;
    /** 내 부 인물만: 충성 · 코스트 · 이탈 판정 순번 · 자리. */
    readonly retinue: RetinueRow | null;
}

const num = (v: unknown): v is number => typeof v === 'number' && Number.isFinite(v);

/** front-info 장수의 5능력 — 하나라도 빠지면 모름(「undefined」로 그리지 않는다). */
export function selfStats(g: Partial<FrontGeneralInfo> | null | undefined): FiveStats | null {
    if (!g) return null;
    const { leadership, strength, intel, politics, charm } = g;
    if (![leadership, strength, intel, politics, charm].every(num)) return null;
    return { leadership: leadership!, strength: strength!, intel: intel!, politics: politics!, charm: charm! };
}

/**
 * 관계 판정 + 칸. 내 장수 id 와 같으면 SELF, 내 부 줄에 그 장수가 있으면 RETINUE, 아니면 UNKNOWN.
 * 내 부 읽기가 아직 없으면(rows null) RETINUE 를 판정할 수 없다 — 화면이 읽는 중으로 둔다(UNKNOWN 으로 단정하지 않게 호출 쪽이 막는다).
 */
export function personView(
    generalId: number,
    me: { readonly general: Partial<FrontGeneralInfo> & { readonly generalId: number | null; readonly name: string | null }; readonly nation: FrontNationInfo | null } | null,
    rows: readonly RetinueRow[] | null,
): PersonView {
    const nation = me?.nation ?? null;
    const myNation = nation && nation.id > 0 ? nation : null;
    if (me && me.general.generalId === generalId) {
        return {
            relation: 'SELF', generalId, name: me.general.name ?? '내 장수',
            picture: me.general.picture ?? null, imageServer: me.general.imageServer ?? null,
            affiliation: myNation ? `${myNation.name} 소속` : '재야', nationColor: myNation?.color ?? null,
            stats: selfStats(me.general), aptitudes: null, bonds: null,
            injured: num(me.general.injury) ? me.general.injury > 0 : null, retinue: null,
        };
    }
    const row = rows?.find((r) => r.generalId === generalId) ?? null;
    if (row) {
        // 내 부 인물은 주공(나)과 같은 소속이다.
        return {
            relation: 'RETINUE', generalId, name: row.name, picture: row.picture, imageServer: row.imageServer,
            affiliation: myNation ? `${myNation.name} 소속` : '재야', nationColor: myNation?.color ?? null,
            stats: row.stats, aptitudes: row.aptitudes, bonds: row.bonds, injured: null, retinue: row,
        };
    }
    return {
        relation: 'UNKNOWN', generalId, name: '', picture: null, imageServer: null, affiliation: null, nationColor: null,
        stats: null, aptitudes: null, bonds: null, injured: null, retinue: null,
    };
}

/** 자리 · 상태 8칸(보드 state8). 값이 없는 칸은 「서버 대기」, 관계 밖 칸은 「내 부 인물만」. */
export interface StateCell {
    readonly label: string;
    readonly value: string;
    readonly kind: 'value' | 'wait' | 'hidden';
}

export function stateCells(view: PersonView, place: string | null): readonly StateCell[] {
    const mine = view.relation === 'RETINUE';
    const self = view.relation === 'SELF';
    const wait = (label: string): StateCell => ({ label, value: '서버 대기', kind: 'wait' });
    const hidden = (label: string): StateCell => ({ label, value: '내 부 인물만', kind: 'hidden' });
    const r = view.retinue;
    return [
        place ? { label: '위치', value: place, kind: 'value' } : wait('위치'),
        mine && r ? { label: '자리', value: r.post.active ?? '미배치', kind: 'value' } : self ? wait('자리') : hidden('자리'),
        view.injured == null ? wait('부상') : { label: '부상', value: view.injured ? '부상 중' : '없음', kind: 'value' },
        mine || self ? wait('녹봉') : hidden('녹봉'),
        wait('보물 칸'),
        wait('경험'),
        mine && r ? { label: '충성', value: String(r.loyalty), kind: 'value' } : self ? wait('충성') : hidden('충성'),
        wait('생몰'),
    ];
}
