import { describe, expect, it } from 'vitest';
import { nationStatusRows, previewCaption, previewNames, serverLabel } from '@/lib/serverStatus';
import type { MapData } from '@/components/MapPreview';

const city = (id: number, nationId: number, name = `현${id}`) => ({ id, name, level: 5, nationId, x: 0, y: 0 });

describe('세력 현황 — 지도 미리보기에서', () => {
    it('현 수 내림차순, 현이 없는 세력과 주인 없는 城은 뺀다', () => {
        const rows = nationStatusRows({
            cities: [city(1, 2), city(2, 2), city(3, 1), city(4, 0), city(5, 2), city(6, 3)],
            nations: [{ id: 1, name: '조조', color: '#4a6fa5' }, { id: 2, name: '원소', color: '#a14b8c' },
                { id: 3, name: '유표', color: 'red' }, { id: 4, name: '손책', color: '#c9a15a' }],
        });
        expect(rows.map((r) => [r.name, r.counties])).toEqual([['원소', 3], ['유표', 1], ['조조', 1]]);
        expect(rows.find((r) => r.name === '유표')?.color).toBe('#8e8879');
    });
    it('세력이 없으면 빈 목록', () => {
        expect(nationStatusRows({ cities: [city(1, 0)], nations: [] })).toEqual([]);
    });
});

describe('캡션 · 이름 풀이', () => {
    it('서버 이름 + 기수', () => {
        expect(serverLabel({ name: 'pep', generation: 1 })).toBe('pep 1기');
        expect(serverLabel({ name: 'pep' })).toBe('pep');
    });
    it('순 글자는 서버가 준 것만', () => {
        expect(previewCaption('pep 1기', { year: 200, month: 3, turnPhaseText: '중순' })).toBe('pep 1기 · 200년 3월 중순');
        expect(previewCaption('pep 1기', { year: 200, month: 3 })).toBe('pep 1기 · 200년 3월');
        // 날짜가 안 오면 짐작하지 않는다 — 「undefined년」 0
        const missing = { turnPhaseText: '중순' } as unknown as Pick<MapData, 'year' | 'month' | 'turnPhaseText'>;
        expect(previewCaption('pep 1기', missing)).toBe('pep 1기 · 확인 중');
        expect(previewCaption('pep 1기', { year: 200, month: Number.NaN })).toBe('pep 1기 · 확인 중');
    });
    it('표시 이름이 있으면 그것을, 재야(0)는 세력으로 풀지 않는다', () => {
        const names = previewNames({ cities: [{ ...city(12, 1, '허'), displayName: '허현' }], nations: [{ id: 0, name: '재야', color: '#000000' }, { id: 1, name: '조조', color: '#4a6fa5' }] });
        expect(names.city(12)).toBe('허현');
        expect(names.nation(1)).toBe('조조');
        expect(names.nation(0)).toBeUndefined();
        expect(previewNames(null).city(12)).toBeUndefined();
    });
});
