import { describe, expect, it } from 'vitest';
import { citiesWithSiegeBadges } from '@/components/hwiha/WarRoomMap';
import type { HwihaSiege } from '@/lib/hwiha-reads';
import type { IsoCityOverlay } from '@opensamguk/ui';

const city = (id: number, statusBadges: IsoCityOverlay['statusBadges'] = []): IsoCityOverlay => ({
    id, name: `縣${id}`, level: 1, nationId: 1, x: id, y: 0, statusBadges,
});
const siege = (countyId: number, status: string) => ({ countyId, status }) as HwihaSiege;

describe('작전실 포위 배지', () => {
    it('uses only ACTIVE rows from the same siege response as the siege page', () => {
        const cities = citiesWithSiegeBadges([city(1, ['isolated']), city(2, ['besieged']), city(3)],
            [siege(1, 'ACTIVE'), siege(2, 'FALLEN'), siege(3, 'LIFTED')]);
        expect(cities.map((row) => row.statusBadges)).toEqual([['isolated', 'besieged'], [], []]);
        expect(citiesWithSiegeBadges([city(1)], undefined)[0].statusBadges).toEqual([]);
    });
});
