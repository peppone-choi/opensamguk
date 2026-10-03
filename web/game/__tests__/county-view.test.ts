import { expect, test } from 'vitest';
import type { Visibility, Warehouses } from '../lib/campaign-reads';
import { countyHead, countyStock, countyVision, indicatorRows, parseCityId, readState, specialtyText } from '../lib/county-view';
import type { FrontCityInfo, MapPreviewCity } from '../lib/types';

const city = (over: Partial<MapPreviewCity> = {}): MapPreviewCity => ({
    id: 3, name: '양성현', level: 2, nationId: 1, x: 0, y: 0, commanderyName: '영천군', state: 0, supply: true, isCapital: false, ...over,
});
const nations = [{ id: 1, name: '조조', color: '#4f7fbf' }, { id: 2, name: '원소', color: '#9c4a3f' }];
const front = {
    id: 3, name: '양성현', level: 2, nationId: 1, region: 0, population: 900, populationMax: 1000, agriculture: 50, agricultureMax: 100,
    commerce: 40, commerceMax: 100, security: 30, securityMax: 100, defense: 20, defenseMax: 100, wall: 10, wallMax: 100, trust: 45, trade: null,
} as FrontCityInfo;

test('7지표 — 내 장수가 선 현만 값, 민심은 상한 100 · 50 아래 경고, 다른 현은 null(서버 대기)', () => {
    const rows = indicatorRows(front, 3)!;
    expect(rows.map((r) => r.label)).toEqual(['호구', '전답', '시장', '치안', '민심', '방비', '성벽']);
    expect(rows[4]).toEqual({ label: '민심', value: 45, max: 100, tone: 'rust' });
    expect(indicatorRows(front, 4)).toBeNull();
    expect(indicatorRows(null, 3)).toBeNull();
});

test('머리 — 소속 · 무주(공용 상수) · 우리 현 · 고립(우리 현만) · 지금 여기', () => {
    expect(countyHead(city(), nations, { nationId: 1, cityId: 3 })).toMatchObject({ ownerName: '조조', mine: true, isolated: false, here: true });
    expect(countyHead(city({ supply: false }), nations, { nationId: 1, cityId: 9 })).toMatchObject({ isolated: true, here: false });
    expect(countyHead(city({ nationId: 2, supply: false }), nations, { nationId: 1, cityId: 9 })).toMatchObject({ ownerName: '원소', mine: false, isolated: false });
    expect(countyHead(city({ nationId: 0 }), nations, { nationId: 1, cityId: null }).ownerName).toBe('무주');
    // 재야(세력 0)는 무주 현을 「우리 현」으로 보지 않는다.
    expect(countyHead(city({ nationId: 0 }), nations, { nationId: 0, cityId: null }).mine).toBe(false);
});

test('시야 — 우리 현은 FULL, 남의 현은 군 이름 대조(못 찾으면 모름), 첩보 순 · 흐름 대상 id 형식', () => {
    const vis: Visibility = { status: 'READY', commanderies: [
        { no: 1, id: 'yingchuan', name: '영천군', tier: 'INTEL', ageTurns: 3 },
        { no: 2, id: '진류', name: '진류군', tier: 'FOG' },
    ] };
    expect(countyVision(vis, '영천군', true)).toEqual({ tier: 'FULL', ageTurns: null, commanderyId: 'yingchuan' });
    expect(countyVision(vis, '영천군', false)).toEqual({ tier: 'INTEL', ageTurns: 3, commanderyId: 'yingchuan' });
    expect(countyVision(vis, '진류군', false)).toEqual({ tier: 'FOG', ageTurns: null, commanderyId: null });
    expect(countyVision(vis, '없는군', false).tier).toBeNull();
    expect(countyVision({ status: 'UNAVAILABLE' }, '영천군', false).tier).toBeNull();
});

test('창고 · 특산 · 경로 id', () => {
    const wh: Warehouses = { status: 'READY', warehouses: [{ cityId: 3, name: '양성현', commanderyName: null, isCapital: false, supplied: false, stock: { money: 1, grain: 2, iron: 0, timber: 0, horses: 0 } }] };
    expect(countyStock(wh, 3, true)).toMatchObject({ kind: 'stock', supplied: false });
    expect(countyStock(wh, 4, true)).toEqual({ kind: 'none' });
    expect(countyStock(wh, 3, false)).toEqual({ kind: 'hidden' });
    expect(countyStock(null, 3, true)).toEqual({ kind: 'unknown' });
    expect(specialtyText({ label: '철', monthly: 0, ledgerMonthly: 120 }, true)).toBe('철 0/월 · 설계 120');
    expect(specialtyText({ label: '말', monthly: 30, ledgerMonthly: 30 }, true)).toBe('말 30/월');
    expect(specialtyText({ label: '목재', monthly: null }, true)).toBe('목재 ?/월');
    // D40: 남의 현은 설계값만 — 실제 몫은 넣어 줘도 쓰지 않는다.
    expect(specialtyText({ label: '철', monthly: 37, ledgerMonthly: 120 }, false)).toBe('철 설계 120/월');
    expect(specialtyText({ label: '말', monthly: 5, ledgerMonthly: null }, false)).toBe('말 설계 ?/월');
    expect([parseCityId('12'), parseCityId('0'), parseCityId('1e3'), parseCityId(['7']), parseCityId(undefined)]).toEqual([12, null, null, 7, null]);
});

test('읽기 상태 — 실패 · 읽는 중 · 서버 상태 · READY 를 가른다(없음은 READY 일 때만)', () => {
    expect(readState({ data: null, error: '불러오지 못했습니다.' })).toBe('error');
    expect(readState({ data: null, error: null })).toBe('loading');
    expect(readState({ data: { status: 'UNSUPPORTED_WORLD_FORMAT' }, error: null })).toBe('unavailable');
    expect(readState({ data: { status: 'READY' }, error: null })).toBe('ready');
});
