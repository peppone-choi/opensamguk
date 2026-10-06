// 연감 고정 자료(계약판 K5-08 모양) — 단위 시험 · 스모크만 쓴다(시험 폴더 — K10 arch_lint d1f_test_only). 화면 코드는 들여오지 않는다. 값은 예시다.

import type { YearbookPage, YearbookYear } from '../../lib/yearbook-contract';

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

// 소비 안 K5-WAIT-04 보강 칸이 다 온 판 — 연말 snapshot · 세력별 현 목록 · 연말 판도(구역 4개 판).
export const BAKE_PIN = 'c'.repeat(64);
export const YEARBOOK_200_FULL: YearbookPage = {
    ...YEARBOOK_200,
    snapshot: { worldId: 1, year: 200, revision: 'r1', publishedAt: '2026-10-05T12:00:00Z' },
    territory: YEARBOOK_200.territory.map((row) => ({
        ...row,
        countyCount: ({ 2: 2, 1: 1, 0: 0, 3: 1 } as Record<number, number>)[row.nationId],
        counties: ({ 2: [{ cityId: 21, name: '업현' }, { cityId: 12, name: '장사현' }], 1: [{ cityId: 11, name: '허현' }], 0: [], 3: [{ cityId: 14, name: '소패현' }] } as Record<number, { cityId: number; name: string }[]>)[row.nationId],
    })),
    ownership: { revision: 'r1', mapPin: BAKE_PIN, provinces: [{ index: 0, nationId: 2 }, { index: 1, nationId: 1 }, { index: 2, nationId: 0 }, { index: 3, nationId: 3 }] },
};

/** 발행됐지만 원천이 없는 판 — 판도 지도 · 현 목록 모두 결손. */
export const YEARBOOK_200_ABSENT: YearbookPage = { ...YEARBOOK_200, snapshot: YEARBOOK_200_FULL.snapshot, territory: YEARBOOK_200.territory.map((row) => ({ ...row, counties: null })), ownership: null, absent: ['ownership', 'counties'] };
