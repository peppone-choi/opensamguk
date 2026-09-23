import { describe, expect, it } from 'vitest';
import ledger from '../../../data/curated/han/hwiha-domestic-v1.json';
import { WORK_BADGE_LABELS, cityBadgesById } from '../lib/iso-city-badges';

describe('아이소 城 상태 배지', () => {
  it('확정된 공사 9종을 원장 코드와 이름 그대로 식별한다', () => {
    const ledgerKinds = ledger.works.kinds.map(({ code, name }) => [code, name]);
    expect(Object.entries(WORK_BADGE_LABELS)).toEqual(ledgerKinds);
    expect(ledgerKinds).toHaveLength(9);
  });

  it('진행·완료 공사와 포위를 縣 ID로만 결합한다', () => {
    const badges = cityBadgesById(
      [{ id: 17, state: 3, supply: false, nationId: 2 }, { id: 18, state: 0, supply: true, nationId: 0 }],
      { status: 'READY', counties: [{ countyId: 17, active: { work: 'ROAD', label: '도로', percent: 35 }, completed: [{ work: 'WAREHOUSE', label: '창고' }] }] },
      { status: 'READY', sieges: [{ countyId: 17, status: 'ACTIVE' }, { countyId: 99, status: 'ACTIVE' }] },
    );
    expect(badges.get(17)).toEqual([
      { kind: 'event', code: 3 },
      { kind: 'supply', supplied: false },
      { kind: 'work', work: 'ROAD', label: '도로', phase: 'active', percent: 35 },
      { kind: 'work', work: 'WAREHOUSE', label: '창고', phase: 'completed' },
      { kind: 'siege' },
    ]);
    expect(badges.has(18)).toBe(false);
    expect(badges.has(99)).toBe(false);
  });

  it('조회가 실패하거나 준비되지 않았으면 휘하 배지를 만들지 않는다', () => {
    const badges = cityBadgesById(
      [{ id: 17, state: 0, supply: true, nationId: 2 }],
      { status: 'UNAVAILABLE', counties: [{ countyId: 17, active: { work: 'ROAD', label: '도로', percent: 35 }, completed: [] }] },
      { status: 'UNAVAILABLE', sieges: [{ countyId: 17, status: 'ACTIVE' }] },
    );
    expect(badges.size).toBe(0);
  });
});
