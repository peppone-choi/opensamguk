import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../lib/api';
import { buildArgs, fetchCommandOptions, fromDeploy, fromTravel } from '../lib/command-flow/options';
import { submitAvailability } from '../lib/command-flow/parts-adapter';
import type { DestinationOption, TravelActionId } from '../lib/types';

export const destinations: DestinationOption[] = [
    { provinceId: 'P-1', name: '영천', available: true, reachability: 'THIS_TURN', arrivesThisTurn: true,
        distanceMm: 10_000_000, costMm: 20_000_000, estimatedTurns: 1 },
    { provinceId: 'P-2', name: '진류', available: true, reachability: 'MULTI_TURN', arrivesThisTurn: false,
        distanceMm: 80_000_000, costMm: 160_000_000, estimatedTurns: 3 },
    { provinceId: 'P-3', name: '양적', available: false, reachability: 'UNAVAILABLE', arrivesThisTurn: false,
        code: 'PASSAGE_DENIED', reason: '통행할 수 없습니다' },
];
afterEach(() => vi.restoreAllMocks());

describe('실제 서버 도달 옵션 소비', () => {
    it.each(['action.move', 'action.forcedMarch'] as TravelActionId[])('%s: 합법 다턴 허용·불법 경로 거절·도달 수치 표시', inputId => {
        const o = fromTravel({ inputId, available: true, destinations });
        expect(o.fields[0].candidates[0]).toMatchObject({ available: true, rangeLabel: '이번 턴 도착', detail: '이번 턴 도착 · 거리 10km · 지형 반영 비용 20km · 예상 1턴' });
        expect(o.fields[0].candidates[1]).toMatchObject({ available: true, rangeLabel: '다턴 이동 · 이번 턴 미도착' });
        expect(buildArgs(o, { destinationProvinceId: 'P-2' })).toEqual({ ok: true, args: { destinationProvinceId: 'P-2' } });
        expect(buildArgs(o, { destinationProvinceId: 'P-3' })).toEqual({ ok: false, missing: ['destinationProvinceId'] });
        expect(o.fields[0].candidates[2]).toMatchObject({ available: false, reason: '통행할 수 없습니다' });
        expect(destinations[1].arrivesThisTurn).toBe(false);
    });
    it('출병은 서버의 목적지 주문 불가를 더 이상 가능으로 바꾸지 않는다', () => {
        const o = fromDeploy({ available: true, maxReservedTurns: 12, bugoks: [{ id: 7, name: '부곡', troops: 100, available: true }], destinations });
        expect(buildArgs(o, { bugokIds: [7], destinationProvinceId: 'P-2' })).toEqual({ ok: true, args: { bugokIds: [7], destinationProvinceId: 'P-2' } });
        expect(buildArgs(o, { bugokIds: [7], destinationProvinceId: 'P-3' })).toEqual({ ok: false, missing: ['destinationProvinceId'] });
        expect(o.fields[1].candidates[2]).toMatchObject({ available: false, reason: '통행할 수 없습니다' });
    });
    it('귀환은 서버 자동 목적지와 도달 정보를 읽고 기존 빈 인자 주문을 유지한다', () => {
        const o = fromTravel({ inputId: 'action.return', available: true, destinations: [destinations[1]] });
        expect(o.fields).toEqual([]);
        expect(o.place).toBe('진류 — 다턴 이동 · 이번 턴 미도착 · 거리 80km · 지형 반영 비용 160km · 예상 3턴');
        expect(buildArgs(o, {})).toEqual({ ok: true, args: {} });
    });
    it('권한 거절은 실제 code/reason을 제출 단추에 전달한다', () => {
        const o = fromTravel({ inputId: 'action.move', available: false, code: 'FORBIDDEN', reason: '본인 장수가 아닙니다', destinations: [] });
        expect(submitAvailability('action.move', o)).toMatchObject({ status: 'BLOCKED', code: 'FORBIDDEN', reason: '본인 장수가 아닙니다' });
    });
    it('조회 실패와 주문 가능 필드 누락을 빈 목록 성공/가능으로 합성하지 않는다', async () => {
        vi.spyOn(api, 'travelOptions').mockRejectedValue(new Error('503'));
        await expect(fetchCommandOptions('action.move', 7)).rejects.toThrow('503');
        expect(() => fromDeploy({ available: true, maxReservedTurns: 12, bugoks: [],
            destinations: [{ provinceId: 'P-1', name: '영천' } as DestinationOption] })).toThrow('목적지의 주문 가능 여부를 확인하지 못했습니다');
    });
});
