import { describe, expect, it } from 'vitest';
import { cityBadgeLabel, cityBadgesById, citySnapshotBadges } from '../worldCityBadges';
import { buildWorldCities } from '../useWorldMap';

const supplyReason = { code: 'PASSAGE_CUT', label: '도로 개통·통행 조건으로 보급 경로 차단', year: 200, month: 1, phase: 1 };
const city = { id: 2, name: '현', x: 1, y: 1, level: 1, nationId: 1, supply: false, supplyReason };
describe('persisted supply reason', () => {
  it('passes the server reason through city overlays into the rendered tooltip label', () => {
    const overlays = buildWorldCities({ mapCode: 'han-world-v3', width: 2, height: 2, cities: [city], nations: [] });
    const badge = overlays[0].cityBadges![0];
    expect(badge).toEqual({ kind: 'supply', supplied: false, reason: supplyReason });
    expect(cityBadgeLabel(badge)).toBe('보급 단절 · 도로 개통·통행 조건으로 보급 경로 차단');
  });
  it('does not infer reasons from a cut flag, visible roads or an unknown code', () => {
    expect(cityBadgeLabel(citySnapshotBadges({ nationId: 1, supply: false })[0])).toBe('보급 단절');
    const unknown = { ...city, supplyReason: { ...supplyReason, code: 'UNKNOWN', label: '단절 사유 확인 불가' } };
    expect(cityBadgeLabel(cityBadgesById([unknown], null, null).get(2)![0])).toBe('보급 단절 · 단절 사유 확인 불가');
  });
  it('never shows a stale cut on supplied or neutral cities', () => {
    expect(citySnapshotBadges({ ...city, supply: true })).toEqual([]);
    expect(citySnapshotBadges({ ...city, nationId: 0 })).toEqual([]);
  });
});
