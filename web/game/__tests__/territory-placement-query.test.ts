import { expect, test } from 'vitest';
import { territoryPlacementCounty } from '../components/territory/TerritoryScreen';
import { countyHrefs } from '../app/game/(campaign)/territory/county/county-hrefs';

test('현 상세 배치 링크는 실제 현을 양의 정수 query로 전달하며 다른 칸에는 붙이지 않는다', () => {
    const hrefs = countyHrefs('pep');
    const url = new URL(hrefs.territory('placement', 129), 'https://example.test');
    expect(url.pathname).toBe('/game/pep/territory');
    expect(url.searchParams.get('view')).toBe('placement');
    expect(territoryPlacementCounty(url.searchParams.get('countyId'))).toBe(129);
    expect(hrefs.territory('policy', 129)).toBe('/game/pep/territory?view=policy');
    expect(hrefs.territory()).toBe('/game/pep/territory');
});

test.each([['1', 1], ['129', 129], [String(Number.MAX_SAFE_INTEGER), Number.MAX_SAFE_INTEGER]] as const)(
    '양의 안전한 정수 현 query %s', (raw, expected) => expect(territoryPlacementCounty(raw)).toBe(expected),
);

test.each([null, undefined, '', '0', '-1', '+1', '01', '1.0', '1.5', '1e2', ' 129', '129 ', '129x', 'NaN', 'Infinity', '9007199254740992'])(
    '부재 또는 불법 현 query %s는 초기 선택값으로 쓰지 않는다', (raw) => expect(territoryPlacementCounty(raw)).toBeNull(),
);
