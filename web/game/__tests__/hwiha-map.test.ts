import { describe, expect, it } from 'vitest';
import type { WorldTiles } from '@opensamguk/ui';
import { buildCommanderies, buildHwihaCities, buildLegend, buildMarkerPositions, hwihaTerrainUrl } from '../lib/hwiha-map';
import { HWIHA_DIRECTIONS, neighborInDirection } from '../lib/hwiha-fog';
import { hwihaHref, hwihaTabLanding, isHwihaBuilt } from '../lib/hwiha-screens';
import { formatHwihaDate } from '../lib/hwiha-session';
import type { FrontInfoResponse, MapPreviewResponse } from '../lib/types';

function preview(over: Partial<MapPreviewResponse> = {}): MapPreviewResponse {
    return {
        serverName: 't',
        year: 200,
        month: 3,
        mapCode: 'han-world-v3',
        width: 700,
        height: 610,
        nations: [
            { id: 1, name: '조조', color: '#c9a656' },
            { id: 2, name: '원소', color: '#7aa7c7' },
            { id: 3, name: '색없음', color: 'not-a-color' },
        ],
        cities: [
            { id: 10, name: '양적현', level: 5, nationId: 1, x: 350, y: 300, state: 0, supply: true, isCapital: false, commanderyName: '영천군', isCommanderySeat: true },
            { id: 11, name: '허현', level: 4, nationId: 1, x: 360, y: 305, state: 0, supply: false, isCapital: false, commanderyName: '영천군' },
            { id: 20, name: '업현', level: 7, nationId: 2, x: 350, y: 100, state: 0, supply: true, isCapital: true, commanderyName: '위군' },
            { id: 30, name: '빈현', level: 3, nationId: 0, x: 600, y: 300, state: 0, supply: true, isCapital: false, commanderyName: '동해군' },
            { id: 31, name: '색현', level: 3, nationId: 3, x: 100, y: 300, state: 0, supply: true, isCapital: false },
        ],
        ...over,
    } as MapPreviewResponse;
}

const tiles = {
    _meta: { cols: 768, rows: 669, year: 200, terrainLegend: {} },
    juns: [
        { name: '영천군', nameCh: '潁川郡', seat: 0, col: 390, row: 330 },
        { name: '위군', nameCh: '魏郡', seat: 1, col: 390, row: 110 },
        { name: '동해군', nameCh: '東海郡', seat: 2, col: 660, row: 330 },
        { name: '성없는군', nameCh: '無城郡', seat: 3, col: 390, row: 500 },
    ],
} as unknown as WorldTiles;

describe('hwiha-map builders', () => {
    it('keeps only nations with a real colour as owners; others read as 공백지', () => {
        const cities = buildHwihaCities(preview());
        const byId = new Map(cities.map((c) => [c.id, c]));
        expect(byId.get(10)?.nationColor).toBe('#c9a656');
        expect(byId.get(10)?.mapLabel).toBe('양적현');
        expect(byId.get(30)?.nationName).toBe('공백지');
        expect(byId.get(30)?.nationColor).toBeUndefined();
        expect(byId.get(31)?.nationColor).toBeUndefined();
    });

    it('builds the legend from real nations sorted by city count', () => {
        const legend = buildLegend(preview());
        expect(legend.map((l) => [l.name, l.cities])).toEqual([['조조', 2], ['원소', 1]]);
    });

    it('takes the commandery table from the served terrain and focuses the seat city', () => {
        const table = buildCommanderies(tiles, preview());
        expect(table.map((c) => [c.no, c.name, c.focusCityId])).toEqual([
            [0, '영천군', 10],
            [1, '위군', 20],
            [2, '동해군', 30],
            [3, '성없는군', null],
        ]);
    });

    it('rounds city markers onto whole cells using the served grid size', () => {
        const cities = buildHwihaCities(preview());
        const markers = buildMarkerPositions(cities, tiles, { width: 700, height: 610 });
        const m = markers.get(10)!;
        expect(Number.isInteger(m.col) && Number.isInteger(m.row)).toBe(true);
    });

    it('asks the terrain API for the pinned base when the preview carries one', () => {
        expect(hwihaTerrainUrl(null)).toBe('/api/game/api/map/terrain?mapCode=han-world-v3');
        expect(hwihaTerrainUrl('abc')).toBe('/api/game/api/map/terrain?mapCode=han-world-v3&baseTilesSha256=abc');
    });
});

describe('hwiha-fog neighborInDirection', () => {
    const table = buildCommanderies(tiles, preview());
    const home = table[0];
    const dir = (key: string) => HWIHA_DIRECTIONS.find((d) => d.key === key)!;

    it('finds the nearest commandery within 45° of the direction', () => {
        expect(neighborInDirection(table, home, dir('N'))?.name).toBe('위군');
        expect(neighborInDirection(table, home, dir('E'))?.name).toBe('동해군');
    });

    it('never moves into a commandery without a city to focus', () => {
        expect(neighborInDirection(table, home, dir('S'))).toBeUndefined();
    });

    it('returns undefined at the map edge', () => {
        expect(neighborInDirection(table, home, dir('W'))).toBeUndefined();
    });
});

describe('hwiha-screens links', () => {
    it('puts screens under the game server path', () => {
        expect(hwihaHref('war-room')).toBe('/game/hwiha/war-room');
        expect(hwihaHref('war-room', 'pep')).toBe('/game/pep/hwiha/war-room');
    });

    it('lands a tab only on a screen that exists', () => {
        expect(hwihaTabLanding('조정 결정')?.slug).toBe('orders');
        expect(hwihaTabLanding('계책')?.slug).toBe('hand');
        expect(hwihaTabLanding('공사')).toBeUndefined();
        for (const tab of ['장수 행동', '배치', '방침', '계책', '조정 결정'] as const) {
            const landing = hwihaTabLanding(tab);
            if (landing) expect(isHwihaBuilt(landing.slug)).toBe(true);
        }
    });
});

describe('formatHwihaDate', () => {
    it('adds the 순 text when the server sends it', () => {
        const info = { global: { year: 200, month: 3, turnPhaseText: '중순' } } as unknown as FrontInfoResponse;
        expect(formatHwihaDate(info)).toBe('200년 3월 중순');
        const bare = { global: { year: 200, month: 3 } } as unknown as FrontInfoResponse;
        expect(formatHwihaDate(bare)).toBe('200년 3월');
    });
});
