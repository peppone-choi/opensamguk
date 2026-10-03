import { describe, expect, it } from 'vitest';
import { indicatorRows } from '../lib/county-view';
import { pickSubline, pickView, stationedCorps, stationedText, type WarRoomPickTarget } from '../lib/war-room-pick';
import type { Corps } from '../lib/campaign-reads';
import type { FrontCityInfo, MapPreviewCity } from '../lib/types';

const home = {
    id: 3, name: '양성현', level: 2, nationId: 1, nationName: '조조', nationColor: '#4f7fbf', region: 0,
    population: 1000, populationMax: 2000, agriculture: 10, agricultureMax: 20, commerce: 10, commerceMax: 20, security: 10, securityMax: 20,
    defense: 10, defenseMax: 20, wall: 10, wallMax: 20, trust: 60, trade: null,
} satisfies FrontCityInfo;
const city = (over: Partial<MapPreviewCity>): MapPreviewCity => ({
    id: 9, name: '장사현', level: 2, nationId: 2, x: 0, y: 0, state: 0, supply: true, isCapital: false, commanderyName: '영천군', isCommanderySeat: false, ...over,
});
const nations = [{ id: 1, name: '조조', color: '#4f7fbf' }, { id: 2, name: '원소', color: '#b03a2e' }];
const target = (c: MapPreviewCity | null, cityId = c?.id ?? 3): WarRoomPickTarget => ({ cityId, city: c, nations, provinceRecordId: 'B' });

describe('pickView — 고른 城 카드 머리 · 칸', () => {
    it('지도에서 고른 남의 현 — 소속은 그 세력, 보급은 모름(남의 현은 보이지 않는다), 내 위치 아님', () => {
        expect(pickView(target(city({})), home, 1)).toMatchObject({
            cityId: 9, name: '장사현', commanderyName: '영천군', ownerName: '원소', ownerColor: '#b03a2e', mine: false, here: false, supplied: null,
        });
    });
    it('지도에서 고른 우리 현 — 보급은 미리보기 supply(끊기면 false), 치소 · 수도 칩 재료', () => {
        expect(pickView(target(city({ nationId: 1, supply: false, isCommanderySeat: true, isCapital: true })), home, 1))
            .toMatchObject({ mine: true, supplied: false, isSeat: true, isCapital: true, ownerName: '조조' });
    });
    it('무주 현 — 공용 무주 이름, 색 없음', () => {
        expect(pickView(target(city({ nationId: 0 })), home, 1)).toMatchObject({ ownerName: '무주', ownerColor: null, mine: false });
    });
    it('「내 위치」 알약(미리보기 없음) — 내 城 front-info 로, 군 · 보급은 모름', () => {
        expect(pickView(target(null, 3), home, 1)).toMatchObject({ cityId: 3, name: '양성현', ownerName: '조조', mine: true, here: true, commanderyName: null, supplied: null });
    });
    it('미리보기 없이 내 城이 아닌 id — 그릴 것이 없다(null)', () => {
        expect(pickView(target(null, 5), home, 1)).toBeNull();
        expect(pickView(target(null, 3), null, 1)).toBeNull();
    });
    it('재야(세력 0) — 어느 현도 우리 현이 아니다', () => {
        expect(pickView(target(city({ nationId: 0 })), home, 0)!.mine).toBe(false);
    });
});

describe('stationedCorps · stationedText — 이 城 구역에 선 군단', () => {
    const row = (over: Partial<Corps>): Corps => ({
        corpsId: 'c', ownerGeneralId: 1, commanderGeneralId: 1, nationId: 1, provinceId: 'B', commanderyNo: 1, visibility: 'FULL', own: true, ...over,
    });
    it('같은 구역 · 보이는 군단만, 이름은 지휘관 → 주인 순', () => {
        const s = stationedCorps({ status: 'READY', corps: [row({ commanderName: '하후돈' }), row({ ownerName: '조인' }), row({ provinceId: 'A', commanderName: '악진' }),
            row({ commanderName: '안개', visibility: 'FOG' })] }, 'B');
        expect(s).toEqual({ kind: 'list', names: ['하후돈 군단', '조인 군단'] });
        expect(stationedText(s)).toBe('하후돈 군단 · 조인 군단');
    });
    it('셋 넘으면 「외 n」, 없으면 「없음」', () => {
        expect(stationedText({ kind: 'list', names: ['가 군단', '나 군단', '다 군단', '라 군단'] })).toBe('가 군단 · 나 군단 외 2');
        expect(stationedText(stationedCorps({ status: 'READY', corps: [] }, 'B'))).toBe('없음');
    });
    it('읽기 실패 · 대기 · 구역 id 모름은 「없음」이 아니라 「?」', () => {
        expect(stationedText(stationedCorps(null, 'B'))).toBe('?');
        expect(stationedText(stationedCorps({ status: 'WRONG_RULE_PROFILE' }, 'B'))).toBe('?');
        expect(stationedText(stationedCorps({ status: 'READY', corps: [row({})] }, null))).toBe('?');
    });
});

describe('pickSubline — 모바일 선택 알약 아래 글', () => {
    it('치소면 「영천군 치소」, 아니면 군 이름, 군을 모르면 빈 글', () => {
        expect(pickSubline(pickView(target(city({ isCommanderySeat: true })), home, 1)!)).toBe('영천군 치소');
        expect(pickSubline(pickView(target(city({})), home, 1)!)).toBe('영천군');
        expect(pickSubline(pickView(target(null, 3), home, 1)!)).toBe('');
    });
});

describe('indicatorRows — 값이 빠진 front-info', () => {
    it('7지표 값 하나라도 없으면 「undefined / undefined」 대신 모름(null)', () => {
        expect(indicatorRows({ ...home, wallMax: undefined as unknown as number }, 3)).toBeNull();
        expect(indicatorRows({ id: 3, name: '양성현' } as FrontCityInfo, 3)).toBeNull();
        expect(indicatorRows(home, 3)).toHaveLength(7);
    });
});
