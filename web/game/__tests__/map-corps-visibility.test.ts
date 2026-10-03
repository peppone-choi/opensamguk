import { describe, expect, it } from 'vitest';
import type { Corps } from '@/lib/campaign-reads';
import { buildVisibleCorps, toTopdownCorps } from '@/lib/map-corps';

const corps = (over: Partial<Corps>): Corps => ({
    corpsId: 'c1', ownerGeneralId: 1, commanderGeneralId: 1, nationId: 1,
    provinceId: 'P1', commanderyNo: 1, visibility: 'FULL', own: false, ...over,
});
const center = (id: string) => ({ P1: { col: 10, row: 20 }, P2: { col: 30, row: 40 } } as const)[id as 'P1' | 'P2'];

describe('projected corps map layer', () => {
    it('red probe: never draws a corps in FOG even if a faulty response includes it', () => {
        const rows = [corps({ corpsId: 'seen', commanderyNo: 1 }), corps({ corpsId: 'hidden', commanderyNo: 2 }),
            corps({ corpsId: 'redacted', commanderyNo: 1, visibility: 'FOG' })];
        expect(buildVisibleCorps(rows, new Map([[1, 'FULL'], [2, 'FOG']]), center).map((row) => row.id))
            .toEqual(['seen']);
        expect(buildVisibleCorps(rows, null, center)).toEqual([]);
    });

    it('keeps projected march paths and marks intelligence corps stale', () => {
        const result = buildVisibleCorps([corps({ visibility: 'INTEL', marchPath: ['P1', 'P2'], troopsBand: { code: 'MEDIUM', label: '수천명' } })],
            new Map([[1, 'INTEL']]), center);
        expect(result).toMatchObject([{ id: 'c1', stale: true, path: [{ col: 10, row: 20 }, { col: 30, row: 40 }], troopsLabel: '수천명' }]);
    });
});

describe('새 지도(탑다운) 부대 표지', () => {
    it('행군 경로 앞의 지금 칸을 떼고 남은 칸만 경로로, 방향은 첫 남은 칸 쪽', () => {
        const [overlay] = buildVisibleCorps([corps({ marchPath: ['P1', 'P2'], nationColor: '#b03a2e', commanderName: '하후돈' })],
            new Map([[1, 'FULL']]), center);
        expect(toTopdownCorps([overlay])).toEqual([{
            id: 'c1', cell: { col: 10, row: 20 }, nationColor: '#b03a2e', leaderName: '하후돈',
            heading: 'right', route: [{ col: 30, row: 40 }], standing: 'seen',
        }]);
    });

    it('상태(D34): 내 군단은 own + 정확한 병력, 마지막 목격은 intel + 병력대 + 「N순 전」, 나머지는 seen + 병력대', () => {
        const overlays = buildVisibleCorps([
            corps({ corpsId: 'mine', own: true, troops: 3200 }),
            corps({ corpsId: 'seen', troopsBand: { code: 'MEDIUM', label: '5천~1만' } }),
            corps({ corpsId: 'intel', commanderyNo: 2, visibility: 'INTEL', troopsBand: { code: 'MEDIUM', label: '5천~1만' }, ageTurns: 2 }),
        ], new Map([[1, 'FULL'], [2, 'INTEL']]), center);
        const ages = new Map([['mine', 9], ['seen', 9], ['intel', 2]]);
        expect(toTopdownCorps(overlays, ages).map((m) => [m.id, m.standing, m.troopsLabel, m.ageLabel])).toEqual([
            ['mine', 'own', '3,200명', undefined],
            ['seen', 'seen', '5천~1만', undefined],
            ['intel', 'intel', '5천~1만', '2순 전'],
        ]);
    });

    it('경로가 없거나 지금 칸뿐이면 멈춤(방향 · 경로 없음), 세력 색이 없으면 무소속 회색', () => {
        const still = toTopdownCorps([
            { id: 'a', col: 5, row: 5, label: '군단', own: false },
            { id: 'b', col: 5, row: 5, label: '군단', own: false, path: [{ col: 5, row: 5 }] },
        ]);
        expect(still.map((marker) => [marker.heading, marker.route])).toEqual([[null, undefined], [null, undefined]]);
        expect(still[0].nationColor).toBe('#8e8879');
    });
});
