import { describe, expect, it } from 'vitest';
import { provinceCentersFromOverview } from '../../map/topdown/bakePlaces';

/** 개관 격자(값 = 구역 번호 + 1, 0 = 없음)를 글자 행으로 짓는다: '.' = 0, '1'..'9' = 그 값. */
function grid(rows: string[]): { provinces: Uint16Array; cols: number } {
  const cols = rows[0].length;
  return { cols, provinces: Uint16Array.from(rows.join(''), (ch) => (ch === '.' ? 0 : Number(ch))) };
}

describe('bake 구역 대표 칸(군단 자리)', () => {
  it('값은 구역 번호 + 1, 대표 칸은 블록 가운데 칸(블록 4 → ×4 + 2)', () => {
    const { provinces, cols } = grid([
      '11..',
      '11.2',
    ]);
    expect(provinceCentersFromOverview(provinces, cols, 4, 2)).toEqual([
      // 구역 0: 블록 (0,0)(1,0)(0,1)(1,1) — 무게중심 (0.5, 0.5)에 가장 가까운 첫 블록 (0,0)
      { col: 2, row: 2 },
      // 구역 1: 블록 (3,1)
      { col: 14, row: 6 },
    ]);
  });

  it('무게중심이 구역 밖이면(ㄷ자) 그 구역 안 가장 가까운 블록을 고른다', () => {
    const { provinces, cols } = grid([
      '111',
      '1..',
      '111',
    ]);
    // 무게중심 (0.86, 1)은 빈 블록 (1,1) 근처다 — 구역 안에서 가장 가까운 (0,1)
    expect(provinceCentersFromOverview(provinces, cols, 1, 1)).toEqual([{ col: 0, row: 1 }]);
  });

  it('격자에 없는 구역은 null, 구역 수 밖 값은 무시한다', () => {
    const { provinces, cols } = grid([
      '1.9',
    ]);
    expect(provinceCentersFromOverview(provinces, cols, 2, 3)).toEqual([{ col: 1, row: 1 }, null, null]);
  });
});
