import { describe, expect, it } from 'vitest';
import { destinationDetail, destinationRange, destinationSummary } from '../lib/command-flow/destination-view';

describe('서버 도달 판정 표시', () => {
    it('강행의 서버 예상 비용과 도착 후 상태를 표시하고 내부 이동 예산을 노출하지 않는다', () => {
        const destination = { available: true, reachability: 'THIS_TURN' as const, arrivesThisTurn: true,
            estimatedTurns: 1, distanceMm: 62_345_678, costMm: 80_000_000,
            forcedFatigueDelta: 20, forcedMoraleDelta: -10, afterFatigue: 30, afterMorale: 70 };
        expect(destinationDetail(destination)).toBe('이번 턴 도착 · 예상 1순 · 강행 비용 피로 +20 → 30 · 사기 -10 → 70 · 경로 거리 62.345678km');
        expect(destinationSummary(destination)).toBe('이번 턴 도착 · 예상 1순 · 강행 비용 피로 +20 → 30 · 사기 -10 → 70 · 경로 거리 62.35km');
        expect(destination.costMm).toBe(80_000_000);
    });
    it('서버 비용 예측이 없거나 불완전하면 피로·사기 수치를 추정하지 않는다', () => {
        expect(destinationDetail({ forcedFatigueDelta: 10 })).toBe('도달 정보 미확인');
        expect(destinationSummary({ forcedFatigueDelta: NaN, forcedMoraleDelta: -5, afterFatigue: 10, afterMorale: 95 })).toBe('도달 정보 미확인');
        expect(destinationDetail({ forcedFatigueDelta: 0, forcedMoraleDelta: 1, afterFatigue: 0, afterMorale: 100 })).toBe('도달 정보 미확인');
    });
    it('예상 순을 먼저 표시하고 실제 경로 거리의 mm 정밀도를 보존한다', () => {
        expect(destinationDetail({ available: true, reachability: 'THIS_TURN', arrivesThisTurn: true,
            distanceMm: 12_345_678, costMm: 20_000_000, estimatedTurns: 1 }))
            .toBe('이번 턴 도착 · 예상 1순 · 경로 거리 12.345678km');
    });
    it('연결됐지만 한 턴 밖인 합법 다턴은 도착 여부와 주문 불가를 구분한다', () => {
        expect(destinationDetail({ available: true, reachability: 'MULTI_TURN', arrivesThisTurn: false,
            distanceMm: 80_000_000, costMm: 160_000_000, estimatedTurns: 3 }))
            .toBe('다턴 이동 · 이번 턴 미도착 · 예상 3순 · 경로 거리 80km');
        expect(destinationRange({ available: false, reachability: 'UNAVAILABLE', arrivesThisTurn: false })).toBe('주문 불가');
    });
    it('조회 누락·모순·잘못된 수치를 도달이나 0으로 합성하지 않는다', () => {
        expect(destinationDetail({})).toBe('도달 정보 미확인');
        expect(destinationDetail({ available: true, reachability: 'THIS_TURN', arrivesThisTurn: false,
            distanceMm: NaN, costMm: -1, estimatedTurns: null })).toBe('도달 정보 미확인');
        expect(destinationRange({ arrivesThisTurn: true })).toBe('도달 정보 미확인');
    });
});
