// 연감 고정 자료(계약판 K5-08 모양) — 단위 시험 · 스모크만 쓴다. 화면 코드는 들여오지 않는다. 값은 예시다.

import type { YearbookPage, YearbookYear } from './yearbook-contract';

export const YEARS: readonly YearbookYear[] = [
    { year: 199, published: true },
    { year: 200, published: true },
    { year: 201, published: false },
];

const event = (id: number, month: number, phase: number, kind: string, refs: Record<string, number>, facts: Record<string, number | string> = {}) => ({
    id, kind, section: 'WORLD', occurredAt: { year: 200, month, phase, ordinal: 0 }, refs, facts,
});

export const YEARBOOK_200: YearbookPage = {
    year: 200,
    territory: [
        { nationId: 2, name: '원소', color: '#b05a4a', countyCount: 14, capitalCityId: 21 },
        { nationId: 1, name: '조조', color: '#4f7fbf', countyCount: 9, capitalCityId: 11 },
        { nationId: 0, name: '무주', color: '#5a625c', countyCount: 3, capitalCityId: null },
        { nationId: 3, name: '유비', color: '#a5744a', countyCount: 1, capitalCityId: null },
    ],
    events: [
        event(901, 12, 3, 'county.ownerChanged', { CITY: 11, FROM_NATION: 2, TO_NATION: 1 }),
        event(902, 3, 2, 'county.ownerChanged', { CITY: 12, FROM_NATION: 3, TO_NATION: 2 }),
    ],
    nextCursor: '902',
};

export const YEARBOOK_200_MORE: YearbookPage = {
    year: 200,
    territory: YEARBOOK_200.territory,
    events: [event(903, 1, 1, 'county.ownerChanged', { CITY: 13, FROM_NATION: 0, TO_NATION: 1 })],
    nextCursor: null,
};

export const MAP_PREVIEW = {
    nations: [{ id: 1, name: '조조', color: '#4f7fbf' }, { id: 2, name: '원소', color: '#b05a4a' }, { id: 3, name: '유비', color: '#a5744a' }],
    cities: [
        { id: 11, name: '허현', level: 2, nationId: 1, x: 0, y: 0 },
        { id: 12, name: '장사현', level: 2, nationId: 2, x: 0, y: 0 },
        { id: 13, name: '영음현', level: 2, nationId: 1, x: 0, y: 0 },
        { id: 21, name: '업현', level: 3, nationId: 2, x: 0, y: 0 },
    ],
    year: 201, month: 1,
};
