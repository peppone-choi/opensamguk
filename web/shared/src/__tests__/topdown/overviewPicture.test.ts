import { describe, expect, it } from 'vitest';
import { overviewPixels } from '../../map/topdown/overviewPicture';
import { NO_TILE } from '../../map/topdown/types';

describe('천하 그림', () => {
  it('개관 칸마다 그 타일의 1px 밉 색, 그릴 것 없는 칸은 바탕색', () => {
    // 밉 1px 아틀라스: 폭 2(타일 0 빨강 · 1 초록), 둘째 줄 타일 2 파랑 · 3 검정
    const mip1 = new Uint8ClampedArray([255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 0, 0, 0, 255]);
    const overview = { tiles: Uint16Array.from([1, 2, NO_TILE, 0]), provinces: new Uint16Array(4) };
    const px = overviewPixels(overview, 2, 2, mip1, 2);
    expect(Array.from(px.slice(0, 4))).toEqual([0, 255, 0, 255]);
    expect(Array.from(px.slice(4, 8))).toEqual([0, 0, 255, 255]);
    expect(Array.from(px.slice(8, 12))).toEqual([12, 15, 14, 255]);
    expect(Array.from(px.slice(12, 16))).toEqual([255, 0, 0, 255]);
  });
});
