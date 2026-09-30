// 인물 상세(P-R03)의 보기 모델 — 관계에 따라 보이는 칸 · 조작을 가른다. React 없음.
//
// 단건 조회(계약판 K4-13 `GET /api/people/{generalId}`)가 오기 전에는 인물 일람 한 줄(`/api/people`)과 내 부 카드(`/api/retinue`)로 채운다.
// 포로(CAPTIVE) · 사람/NPC · 부상 · 나이 · 녹봉 · 관직 · 계책 기여는 K4-13 전까지 짓지 않는다(「?」 또는 「준비 중」).

import type { DirectoryPerson } from './directory-reads';

export type PersonRelation = 'SELF' | 'RETINUE' | 'NATION' | 'OTHER' | 'FREE';

export interface Viewer {
    readonly generalId: number | null;
    readonly nationId: number | null;
    /** 내 부 인물의 generalId(무명 카드는 없다). */
    readonly retinueGeneralIds: ReadonlySet<number>;
}

/** 관계 — 나 · 내 부 · 같은 세력 · 다른 세력 · 재야. 소속을 모르면(서버가 안 줌) 다른 세력이 아니라 재야로 읽지 않도록 OTHER. */
export function personRelation(p: Pick<DirectoryPerson, 'generalId' | 'affiliation'>, viewer: Viewer): PersonRelation {
    if (viewer.generalId != null && p.generalId === viewer.generalId) return 'SELF';
    if (viewer.retinueGeneralIds.has(p.generalId)) return 'RETINUE';
    if (p.affiliation === null) return 'FREE';
    if (viewer.nationId != null && viewer.nationId > 0 && p.affiliation.nationId === viewer.nationId) return 'NATION';
    return 'OTHER';
}

export const RELATION_LABEL: Readonly<Record<PersonRelation, string>> = {
    SELF: '나',
    RETINUE: '내 부',
    NATION: '같은 세력',
    OTHER: '다른 세력',
    FREE: '재야',
};

export type PersonActionKind = 'myRetinue' | 'records' | 'placement' | 'letter' | 'employ';

/**
 * 관계별 조작(설계서 P-R03 표). 발령 · 포상은 사람 장수 표지(K4-18) 뒤 — 내 부 인물 칸은 편성 부품(PersonDetail)이 그린다.
 * 등용은 「탐색된 재야 인재 · 같은 칸」 조건을 서버 옵션(action.employ)이 판정한다 — 재야면 단추를 두고 가능 여부는 서버 값.
 */
export function relationActions(relation: PersonRelation): readonly PersonActionKind[] {
    switch (relation) {
        case 'SELF': return ['myRetinue', 'records'];
        case 'RETINUE': return ['placement', 'letter'];
        case 'NATION': return ['letter'];
        case 'OTHER': return ['letter'];
        case 'FREE': return ['employ', 'letter'];
    }
}

/** 명령 흐름으로 등용을 여는 주소 조각(K6 Q9 · 설계서 §1.3) — `?do=action.employ&target=general:<id>`. */
export function employQuery(generalId: number): string {
    return `?do=${encodeURIComponent('action.employ')}&target=${encodeURIComponent(`general:${generalId}`)}`;
}

export interface StateCell {
    readonly key: string;
    readonly value: string;
    /** 관계 밖이라 안 보이는 칸(「? — 내 부 인물만」). */
    readonly hidden: boolean;
}

/**
 * 상태 8칸(보드 state8) — 위치 · 자리 · 부상 · 피로 · 녹봉 · 보물 칸 · 경험 · 충성 · 생몰 중 서버가 주는 것만 값, 나머지는 「?」.
 * 충성은 내 부 인물(과 나)만. 값이 없으면 짓지 않는다.
 */
export function stateCells(input: {
    readonly relation: PersonRelation;
    readonly location: string | null;
    readonly post: string | null;
    readonly loyalty: number | null;
}): StateCell[] {
    const mine = input.relation === 'SELF' || input.relation === 'RETINUE';
    return [
        { key: '위치', value: input.location ?? '?', hidden: false },
        { key: '자리', value: input.post ?? (mine ? '미배치' : '?'), hidden: false },
        { key: '충성', value: mine ? (input.loyalty == null ? '?' : String(input.loyalty)) : '? — 내 부 인물만', hidden: !mine },
        { key: '녹봉', value: mine ? '준비 중' : '? — 내 부 인물만', hidden: !mine },
        { key: '부상 · 피로', value: '준비 중', hidden: false },
        { key: '보물 칸', value: '준비 중', hidden: false },
        { key: '경험', value: '준비 중', hidden: false },
        { key: '생몰', value: '준비 중', hidden: false },
    ];
}
