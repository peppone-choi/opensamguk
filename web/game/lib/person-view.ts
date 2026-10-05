// 인물 상세(P-R03)의 보기 모델. React 없음(단위 시험으로 고정한다).
//
// 정본 읽기는 계약판 K4-13 `GET /api/people/{generalId}`(관계별 칸, person-detail.ts)다. 늘 부르고(D124), 받으면 더 채운다:
//  - 나(SELF): front-info 장수(초상 · 5능력 · 부상 · 소속) + 상세의 적성 · 결속 · 위치.
//  - 내 부 인물(RETINUE): `/api/retinue` + `/api/posts`(부 편성 P-R01 과 같은 줄 — retinueRows) + 상세의 부상 · 위치.
//  - 같은 세력 · 다른 세력: 상세가 주는 공개 칸(이름 · 초상 · 소속 · 5능력 · 적성)만. 사적인 칸은 「내 부 인물만」.
//  - 상세가 없으면(404 · 실패 · 관계 모름) 그 밖은 「서버 대기」. 이름 · id 로 짐작하지 않는다.
import type { Aptitudes, Bond, FiveStats } from './campaign-reads';
import { usableDetail, type PersonDetailRead } from './person-detail';
import type { RetinueRow } from './retinue-view';
import type { FrontGeneralInfo, FrontNationInfo } from './types';

/** 경로의 `[generalId]` 조각 → 양의 정수만. 아니면 null(화면은 「이 인물을 찾을 수 없습니다」). */
export function parseGeneralId(raw: string | string[] | undefined | null): number | null {
    const v = Array.isArray(raw) ? raw[0] : raw;
    return v && /^[1-9]\d{0,8}$/.test(v) ? Number(v) : null;
}

export type PersonRelation = 'SELF' | 'RETINUE' | 'SAME_NATION' | 'OTHER' | 'UNKNOWN';

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
    /** 부상 — 나(front-info) · 상세가 연 칸만. 모르면 null. */
    readonly injured: boolean | null;
    /** 내 부 인물만: 충성 · 코스트 · 이탈 판정 순번 · 자리. */
    readonly retinue: RetinueRow | null;
    /** 상세가 준 소재 이름(나 · 내 부만). 모르면 null. */
    readonly locationName: string | null;
}

/** 같은 세력 · 다른 세력 — 사적인 칸(위치 · 자리 · 부상 · 결속 · 충성 · 녹봉)이 닫힌 관계. */
export const isOutsider = (relation: PersonRelation): boolean => relation === 'SAME_NATION' || relation === 'OTHER';

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
    detail: PersonDetailRead | null = null,
): PersonView {
    const nation = me?.nation ?? null;
    const myNation = nation && nation.id > 0 ? nation : null;
    const d = usableDetail(detail, generalId);
    // 상세가 그 관계로 판정했을 때만 사적인 칸을 쓴다(서버가 연 칸만).
    const own = (relation: PersonRelation) => (d?.relation === relation ? d : null);
    if (me && me.general.generalId === generalId) {
        const sd = own('SELF');
        return {
            relation: 'SELF', generalId, name: me.general.name ?? '내 장수',
            picture: me.general.picture ?? null, imageServer: me.general.imageServer ?? null,
            affiliation: myNation ? `${myNation.name} 소속` : '재야', nationColor: myNation?.color ?? null,
            stats: selfStats(me.general), aptitudes: sd?.aptitudes ?? null, bonds: sd?.bonds ?? null,
            injured: num(me.general.injury) ? me.general.injury > 0 : sd?.injured ?? null, retinue: null,
            locationName: sd?.location?.name ?? null,
        };
    }
    const row = rows?.find((r) => r.generalId === generalId) ?? null;
    if (row) {
        // 내 부 인물은 주공(나)과 같은 소속이다.
        const rd = own('RETINUE');
        return {
            relation: 'RETINUE', generalId, name: row.name, picture: row.picture, imageServer: row.imageServer,
            affiliation: myNation ? `${myNation.name} 소속` : '재야', nationColor: myNation?.color ?? null,
            stats: row.stats, aptitudes: row.aptitudes, bonds: row.bonds, injured: rd?.injured ?? null, retinue: row,
            locationName: rd?.location?.name ?? null,
        };
    }
    if (d) {
        // 나 · 내 부 줄로 판정하지 못한 인물 — 상세의 관계 · 칸 그대로. 사적인 칸은 서버가 열었을 때만 온다.
        const affiliationHidden = d.unavailableReasons?.['/affiliation'] != null;
        return {
            relation: d.relation as PersonRelation, generalId, name: d.name!.trim(),
            picture: d.portrait?.picture ?? null, imageServer: d.portrait?.imageServer ?? null,
            affiliation: d.affiliation ? `${d.affiliation.name} 소속` : affiliationHidden ? null : '재야', nationColor: d.affiliation?.color ?? null,
            stats: d.stats ?? null, aptitudes: d.aptitudes ?? null, bonds: d.bonds ?? null, injured: d.injured ?? null, retinue: null,
            locationName: d.location?.name ?? null,
        };
    }
    return {
        relation: 'UNKNOWN', generalId, name: '', picture: null, imageServer: null, affiliation: null, nationColor: null,
        stats: null, aptitudes: null, bonds: null, injured: null, retinue: null, locationName: null,
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
    const outsider = isOutsider(view.relation);
    const wait = (label: string): StateCell => ({ label, value: '서버 대기', kind: 'wait' });
    const hidden = (label: string): StateCell => ({ label, value: '내 부 인물만', kind: 'hidden' });
    const r = view.retinue;
    return [
        place ? { label: '위치', value: place, kind: 'value' } : outsider ? hidden('위치') : wait('위치'),
        mine && r ? { label: '자리', value: r.post.active ?? '미배치', kind: 'value' } : self ? wait('자리') : hidden('자리'),
        view.injured != null ? { label: '부상', value: view.injured ? '부상 중' : '없음', kind: 'value' } : outsider ? hidden('부상') : wait('부상'),
        mine || self ? wait('녹봉') : hidden('녹봉'),
        wait('보물 칸'),
        wait('경험'),
        mine && r ? { label: '충성', value: String(r.loyalty), kind: 'value' } : self ? wait('충성') : hidden('충성'),
        wait('생몰'),
    ];
}
