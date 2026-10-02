// 인물 일람(P-R02)의 보기 모델 — `/api/people` 한 쪽을 화면 줄로 바꾼다. React 없음(단위 테스트로 고정한다).
//
// 서버 값만 옮긴다. null = 시야 · 권한 밖(설계서 P-R02 「?」) — 0 으로 바꾸지 않는다.
// 서버가 아직 주지 않는 것(사람 표지 · 자리 라벨 · 소재 이름 · 부상 · 나이 · 범위 전체 수)은 만들지 않는다.
// 정렬 · 초성 찾기는 서버가 한다(#1103 K4-19/20) — 커서 페이지라 받은 쪽만 다시 정렬하면 틀린 순위가 된다.

import type { DirectoryAptitudes, DirectoryBond, DirectoryPerson, DirectoryStats, PeopleDirection, PeopleScope, PeopleSort } from './directory-reads';

/** 정렬 키 이름(화면 글자) — 서버 키 14개(PEOPLE_SORTS) 순서 그대로. */
export const PEOPLE_SORT_LABEL: Readonly<Record<PeopleSort, string>> = {
    ID: '등록순',
    NAME: '이름',
    AFFILIATION: '소속',
    LEADERSHIP: '통솔',
    STRENGTH: '무력',
    INTEL: '지력',
    POLITICS: '정치',
    CHARM: '매력',
    TOTAL: '능력 합',
    COMMAND: '장 · 군단',
    ADMINISTRATION: '리 · 내정',
    STRATEGY: '사 · 계책',
    ENVOY: '사자 · 외교',
    AGE: '나이',
};

/** 키를 고를 때의 첫 방향 — 수치는 높은 쪽부터, 등록순 · 이름 · 소속은 앞에서부터. */
export function defaultDirection(sort: PeopleSort): PeopleDirection {
    return sort === 'ID' || sort === 'NAME' || sort === 'AFFILIATION' ? 'ASC' : 'DESC';
}

/** 방향 단추 글자 — 수치 키는 「높은 순 · 낮은 순」, 나이는 「나이 많은 순 · 적은 순」, 글자 키는 「앞에서부터 · 뒤에서부터」. */
export function directionLabel(sort: PeopleSort, direction: PeopleDirection): string {
    const text = sort === 'NAME' || sort === 'AFFILIATION' || sort === 'ID';
    if (text) return direction === 'ASC' ? '앞에서부터' : '뒤에서부터';
    if (sort === 'AGE') return direction === 'DESC' ? '나이 많은 순' : '나이 적은 순';
    return direction === 'DESC' ? '높은 순' : '낮은 순';
}

export const PEOPLE_SCOPES: readonly PeopleScope[] = ['RETINUE', 'NATION', 'ALL'];
export const PEOPLE_SCOPE_LABEL: Readonly<Record<PeopleScope, string>> = {
    RETINUE: '내 부',
    NATION: '소속 세력',
    ALL: '전체',
};

/** 옛 주소(`?scope=NATION` 등)에서 범위를 읽는다. 모르는 값은 전체. */
export function scopeFromQuery(raw: string | null | undefined): PeopleScope {
    return PEOPLE_SCOPES.includes(raw as PeopleScope) ? (raw as PeopleScope) : 'ALL';
}

/** 「능력 합」 = 5능력 합(설계서 P-R02 — 명망 코스트 식과 같은 합). 권한 밖이면 null. */
export function statTotal(s: DirectoryStats | null): number | null {
    return s ? s.leadership + s.strength + s.intel + s.politics + s.charm : null;
}

export type AptitudeKey = keyof DirectoryAptitudes;
const APTITUDE_ORDER: readonly AptitudeKey[] = ['command', 'administration', 'strategy', 'envoy'];
export const APTITUDE_SHORT: Readonly<Record<AptitudeKey, string>> = {
    command: '장',
    administration: '리',
    strategy: '사',
    envoy: '사자',
};

/** 가장 높은 적성 하나(같으면 장 · 리 · 사 · 사자 순의 앞). */
export function topAptitude(a: DirectoryAptitudes | null): { readonly key: AptitudeKey; readonly label: string; readonly value: number } | null {
    if (!a) return null;
    let best: AptitudeKey = APTITUDE_ORDER[0];
    for (const k of APTITUDE_ORDER) if (a[k] > a[best]) best = k;
    return { key: best, label: APTITUDE_SHORT[best], value: a[best] };
}

/** 결속 종류 이름. 모르는 종류는 코드를 보이지 않고 「결속」 으로 적는다(영문 코드 노출 금지). */
const BOND_LABEL: Readonly<Record<string, string>> = { HYANGDANG: '향당' };

/** 결속 칩 글자 — 같은 종류는 한 번. 권한 밖(null)이면 null. */
export function bondLabels(bonds: readonly DirectoryBond[] | null): string[] | null {
    if (!bonds) return null;
    return [...new Set(bonds.map((b) => BOND_LABEL[b.kind] ?? '결속'))];
}

export interface PeopleRow {
    /** 지금 순서의 순번(「#」). */
    readonly rank: number;
    readonly generalId: number;
    readonly name: string;
    readonly picture: string | null;
    readonly imageServer: number;
    readonly isMe: boolean;
    /** null = 재야. */
    readonly affiliation: { readonly name: string; readonly color: string } | null;
    readonly stats: DirectoryStats | null;
    readonly total: number | null;
    readonly aptitudes: DirectoryAptitudes | null;
    readonly top: ReturnType<typeof topAptitude>;
    readonly locationCityId: number | null;
    readonly bonds: string[] | null;
}

export function peopleRows(people: readonly DirectoryPerson[], meId: number | null): PeopleRow[] {
    return people.map((p, i) => ({
        rank: i + 1,
        generalId: p.generalId,
        name: p.name,
        picture: p.portrait.picture,
        imageServer: p.portrait.imageServer,
        isMe: meId != null && p.generalId === meId,
        affiliation: p.affiliation ? { name: p.affiliation.name, color: p.affiliation.color } : null,
        stats: p.stats,
        total: statTotal(p.stats),
        aptitudes: p.aptitudes,
        top: topAptitude(p.aptitudes),
        locationCityId: p.locationCityId,
        bonds: bondLabels(p.bonds),
    }));
}

/** 받은 수 글자 — 서버가 범위 전체 수를 주기 전(계약판 K4-05 보강 `total`)에는 「n명 · 더 있음」. */
export function loadedText(loaded: number, hasMore: boolean): string {
    return hasMore ? `${loaded}명 · 더 있음` : `${loaded}명`;
}
