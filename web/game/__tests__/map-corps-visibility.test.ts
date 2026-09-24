import { describe, expect, it } from 'vitest';
import type { HwihaCorps } from '@/lib/hwiha-reads';
import { buildVisibleCorps } from '@/lib/map-corps';

const corps = (over: Partial<HwihaCorps>): HwihaCorps => ({
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
