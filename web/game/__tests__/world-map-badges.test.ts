import { describe, expect, it } from 'vitest';
import { buildWorldCities, cityBadgesById, type IsoCityOverlay } from '@opensamguk/ui';

const cities: IsoCityOverlay[] = [
    { id: 1, name: '甲縣', level: 1, nationId: 1, x: 1, y: 1, state: 0, supply: false },
    { id: 2, name: '乙縣', level: 1, nationId: 1, x: 2, y: 2, state: 0, supply: true },
];
const preview = { mapCode: 'han-world-v3', width: 700, height: 610, cities,
    nations: [{ id: 1, name: '魏', color: '#ff0000' }] };

describe('all map city badges', () => {
    it('uses the same supply, work, and active siege badges in both war rooms', () => {
        const works = { status: 'READY', counties: [{ countyId: 1,
            active: { work: 'ROAD', label: '道路', percent: 35 }, completed: [] }] };
        const sieges = { status: 'READY', sieges: [
            { countyId: 1, status: 'ACTIVE' }, { countyId: 2, status: 'LIFTED' },
        ] };
        const badges = cityBadgesById(cities, works, sieges);
        const main = buildWorldCities(preview, badges);
        const warRoom = buildWorldCities(preview, badges);
        expect(main.map((city) => city.cityBadges)).toEqual(warRoom.map((city) => city.cityBadges));
        expect(main[0].cityBadges).toEqual([
            { kind: 'supply', supplied: false },
            { kind: 'work', work: 'ROAD', label: '도로', phase: 'active', percent: 35 },
            { kind: 'siege' },
        ]);
        expect(main[1].cityBadges).toEqual([]);
        expect(main.every((city) => city.statusBadges === undefined && city.mapLabel === city.name)).toBe(true);
    });
});
