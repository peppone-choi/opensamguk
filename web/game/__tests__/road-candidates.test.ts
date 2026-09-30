import { expect, test } from 'vitest';
import type { RoadForts } from '../lib/campaign-reads';
import { UNKNOWN_PROVINCE, candidateBody, fortCandidates, roadCandidates } from '../lib/road-candidates';

const roads: RoadForts = {
    status: 'READY', roadMode: true,
    forts: [{ id: 'f', edgeId: 'e2', provinceId: 'p-a', row: 5, col: 6, ownerNationId: 1, wall: 1, garrison: 1, besiegerGeneralId: null, siegeProgress: 0, canBesiege: false }],
    gates: [
        { edgeId: 'e1', fromProvinceId: 'p-b', toProvinceId: 'p-a', active: false, buildable: false, historicalRouteIds: [], fortCells: [] },
        { edgeId: 'e2', fromProvinceId: 'p-a', toProvinceId: 'p-c', active: true, buildable: true, historicalRouteIds: [],
            fortCells: [{ provinceId: 'p-a', row: 5, col: 6 }, { provinceId: 'p-a', row: 7, col: 8 }, { provinceId: 'p-c', row: 1, col: 1 }] },
        { edgeId: 'e3', fromProvinceId: 'p-x', toProvinceId: 'p-y', active: false, buildable: true, historicalRouteIds: [], fortCells: [] },
    ],
};
const county = { provinceIds: ['p-a'] };
const name = (id: string) => ({ 'p-a': '가', 'p-b': '나' } as Record<string, string>)[id] ?? null;

test('도로 후보 — 이 현에 닿는 접경만, 이 현 쪽 이름을 앞에, 불가 사유, 이름 모름은 빼지 않는다', () => {
    const c = roadCandidates(roads, county, name);
    expect(c.map((x) => [x.name, x.available, x.reason ?? null])).toEqual([
        ['가 ↔ 나 접경', false, '도로를 낼 수 없는 접경입니다.'],
        [`가 ↔ ${UNKNOWN_PROVINCE} 접경`, false, '이미 도로가 난 접경입니다.'],
    ]);
});

test('보루 후보 — 도로가 난 접경의 이 현 쪽 칸, 이미 보루가 있으면 불가, 좌표는 이름에 없다', () => {
    const c = fortCandidates(roads, county, name);
    expect(c.map((x) => [x.name, x.available])).toEqual([
        [`가 ↔ ${UNKNOWN_PROVINCE} 접경 길목 1`, false],
        [`가 ↔ ${UNKNOWN_PROVINCE} 접경 길목 2`, true],
    ]);
    expect(c.map((x) => x.name).join(' ')).not.toMatch(/\d+,\s*\d+/);
    expect(candidateBody(c[1].targetId)).toEqual({ edgeId: 'e2', row: 7, col: 8 });
    expect(candidateBody('road|e9')).toEqual({ edgeId: 'e9' });
    expect(candidateBody(null)).toBeNull();
    expect(candidateBody('weird')).toBeNull();
});
