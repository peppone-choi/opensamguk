import { describe, expect, it } from 'vitest';
import { destinationChoices } from '../lib/command-flow/destination-choices-view';
import { destinationLabel, destinationSummary } from '../lib/command-flow/destination-view';
import { fromTravel } from '../lib/command-flow/options';

describe('이동 목적지 검색과 서버 근접 정보', () => {
    const rows = fromTravel({ inputId: 'action.move', available: true, destinations: [
        { provinceId: '70623', name: '장안현', available: true, distanceMm: 12_345_678, estimatedTurns: 2,
            reachability: 'MULTI_TURN', arrivesThisTurn: false },
        { provinceId: '70634', name: '두', available: true, distanceMm: 1_000_000, estimatedTurns: 1,
            reachability: 'THIS_TURN', arrivesThisTurn: true },
        { provinceId: '70523', name: '부평현', available: false, reason: '통행 불가' },
        { provinceId: '85064', name: '견량현', available: true, distanceMm: -1, estimatedTurns: null,
            reachability: 'THIS_TURN', arrivesThisTurn: false },
    ] }).fields[0].candidates;

    it('지명·원 ID를 검색하고 원 배열과 합법 다턴·불가 판정을 보존한다', () => {
        expect(destinationChoices(rows, ' 장 안 ', 'all', 'distance').map(r => r.value)).toEqual(['70623']);
        expect(destinationChoices(rows, '７０６３４', 'all', 'name').map(r => r.value)).toEqual(['70634']);
        expect(destinationChoices(rows, '', 'all', 'distance').map(r => r.value)).toEqual(['70634', '70623', '85064', '70523']);
        expect(rows.map(r => r.value)).toEqual(['70623', '70634', '70523', '85064']);
        expect(rows[2]).toMatchObject({ available: false, reason: '통행 불가' });
        expect(rows[0].available).toBe(true);
    });

    it('이번 턴·다턴은 서버의 일치하는 판정만 사용하고 모순·누락을 추정하지 않는다', () => {
        expect(destinationChoices(rows, '', 'this-turn', 'distance').map(r => r.value)).toEqual(['70634']);
        expect(destinationChoices(rows, '', 'multi-turn', 'turns').map(r => r.value)).toEqual(['70623']);
        expect(destinationChoices(rows, '', 'nearby', 'distance').map(r => r.value)).toEqual(['70634', '70623']);
        expect(destinationChoices(rows, '', 'all', 'turns').map(r => r.value)).toEqual(['70634', '70623', '85064', '70523']);
    });

    it('가까운 20곳은 전체 서버 거리 기준이며 검색해도 먼 곳을 가까운 곳으로 바꾸지 않는다', () => {
        const many = Array.from({ length: 30 }, (_, i) => ({ value: String(i + 1), label: `구역 ${i + 1}`, destination: { distanceMm: (i + 1) * 1_000_000 } })).reverse();
        expect(destinationChoices(many, '', 'nearby', 'distance').map(r => r.value)).toEqual(Array.from({ length: 20 }, (_, i) => String(i + 1)));
        expect(destinationChoices(many, '30', 'nearby', 'distance')).toEqual([]);
        expect(destinationChoices(many, '30', 'all', 'distance').map(r => r.value)).toEqual(['30']);
    });

    it('목록은 짧게 표시하고 정확한 경로 거리와 원 서버 수치를 보존한다', () => {
        expect(rows[0].summary).toBe('다턴 이동 · 이번 턴 미도착 · 예상 2순 · 경로 거리 12.35km');
        expect(rows[0].detail).toContain('거리 12.345678km');
        expect(rows[0].destination?.distanceMm).toBe(12_345_678);
        expect(destinationSummary({ distanceMm: 1 })).toBe('도달 정보 미확인 · 경로 거리 0.01km 미만');
        expect(destinationSummary({ distanceMm: NaN, costMm: -1 })).toBe('도달 정보 미확인');
    });

    it('이름 누락이나 raw ID를 다른 현의 이름으로 치환하지 않는다', () => {
        expect(destinationLabel('44580', '44580')).toBe('이름 미확인 · 구역 44580');
        expect(destinationLabel('44580', ' ')).toBe('이름 미확인 · 구역 44580');
        expect(destinationLabel('44580', ' 한창현 ')).toBe('한창현');
        expect(fromTravel({ inputId: 'action.move', available: true, destinations: [{ provinceId: '44580', name: '44580', available: true }] }).fields[0].candidates[0].value).toBe('44580');
    });
});
