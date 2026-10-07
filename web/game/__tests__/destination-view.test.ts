import { describe, expect, it } from 'vitest';
import { destinationDetail, destinationRange } from '../lib/command-flow/destination-view';

describe('서버 도달 판정 표시', () => {
    it('실제 거리와 지형 비용을 구분하고 mm 정밀도를 보존한다', () => {
        expect(destinationDetail({ available: true, reachability: 'THIS_TURN', arrivesThisTurn: true,
            distanceMm: 12_345_678, costMm: 20_000_000, estimatedTurns: 1 }))
            .toBe('이번 턴 도착 · 거리 12.345678km · 지형 반영 비용 20km · 예상 1턴');
    });
    it('연결됐지만 한 턴 밖인 합법 다턴은 도착 여부와 주문 불가를 구분한다', () => {
        expect(destinationDetail({ available: true, reachability: 'MULTI_TURN', arrivesThisTurn: false,
            distanceMm: 80_000_000, costMm: 160_000_000, estimatedTurns: 3 }))
            .toBe('다턴 이동 · 이번 턴 미도착 · 거리 80km · 지형 반영 비용 160km · 예상 3턴');
        expect(destinationRange({ available: false, reachability: 'UNAVAILABLE', arrivesThisTurn: false })).toBe('주문 불가');
    });
    it('조회 누락·모순·잘못된 수치를 도달이나 0으로 합성하지 않는다', () => {
        expect(destinationDetail({})).toBe('도달 정보 미확인');
        expect(destinationDetail({ available: true, reachability: 'THIS_TURN', arrivesThisTurn: false,
            distanceMm: NaN, costMm: -1, estimatedTurns: null })).toBe('도달 정보 미확인');
        expect(destinationRange({ arrivesThisTurn: true })).toBe('도달 정보 미확인');
    });
});
