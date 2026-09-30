import { describe, expect, it } from 'vitest';
import { topdownScreensEnabled, topdownSourceFor, worldFromPreview, TOPDOWN_KIT_URL } from '../../map/topdown/worldAdapter';

const nations = [{ id: 3, name: '위', color: '#4f7fbf' }];
const occ = (n: number, owner = (i: number) => (i % 2 ? 3 : 0)) =>
  Array.from({ length: n }, (_, i) => ({ provinceRecordId: `p${i}`, provinceIndex: i, nationId: owner(i) }));

describe('미리보기 → 세계 상태', () => {
  it('구역 번호 그대로 점유를 잇고 세력은 id · 이름 · 색만 넘긴다', () => {
    const result = worldFromPreview({ nations, provinceOccupancy: occ(4) }, 4);
    expect(result.ok).toBe(true);
    if (!result.ok) return;
    expect(result.world.occupancy).toEqual([
      { provinceIndex: 0, nationId: 0 },
      { provinceIndex: 1, nationId: 3 },
      { provinceIndex: 2, nationId: 0 },
      { provinceIndex: 3, nationId: 3 },
    ]);
    expect(result.world.nations).toEqual(nations);
  });

  it('구역 수가 다르거나 번호가 범위 밖 · 겹치면 칠하지 않고 까닭을 준다', () => {
    expect(worldFromPreview({ nations, provinceOccupancy: occ(3) }, 4)).toEqual({ ok: false, reason: '구역 수가 다릅니다(서버 3, 지도 4)' });
    const out = occ(4).map((o, i) => (i === 3 ? { ...o, provinceIndex: 4 } : o));
    expect(worldFromPreview({ nations, provinceOccupancy: out }, 4)).toMatchObject({ ok: false, reason: expect.stringContaining('범위 밖') });
    const dup = occ(4).map((o, i) => (i === 3 ? { ...o, provinceIndex: 0 } : o));
    expect(worldFromPreview({ nations, provinceOccupancy: dup }, 4)).toMatchObject({ ok: false, reason: expect.stringContaining('두 번') });
    expect(worldFromPreview({ nations }, 0)).toMatchObject({ ok: true });
  });
});

describe('원천 · 스위치', () => {
  const id = 'a'.repeat(64);
  it('64자리 bakeId만 받고 서버는 따로 붙인다', () => {
    expect(topdownSourceFor(id, 'pep')).toEqual({ bakeUrl: `/api/game/api/map/topdown/${id}`, kitUrl: TOPDOWN_KIT_URL, query: 'server=pep' });
    expect(topdownSourceFor(id)?.query).toBe('');
    expect(topdownSourceFor(null)).toBeNull();
    expect(topdownSourceFor('abc')).toBeNull();
    expect(topdownSourceFor('A'.repeat(64))).toBeNull();
  });

  it('제품 화면 스위치는 시험 화면 플래그와 다르다', () => {
    expect(topdownScreensEnabled({ NEXT_PUBLIC_TOPDOWN_SCREENS: '1' })).toBe(true);
    expect(topdownScreensEnabled({ NEXT_PUBLIC_MAP_RENDERER: 'topdown' })).toBe(false);
    expect(topdownScreensEnabled({})).toBe(false);
  });
});
