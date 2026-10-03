import { describe, expect, it } from 'vitest';
import { dayPalette, overviewPixels } from '../../map/topdown/overviewPicture';
import { NO_TILE, outOfScopeLandRgb } from '../../map/topdown/types';

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

  it('범위 밖 땅(그리지 않는 칸)은 지도와 같은 흐린 땅색 — 낮 팔레트 14번 × 0.45(D42)', () => {
    const mip1 = new Uint8ClampedArray([255, 0, 0, 255]);
    const overview = { tiles: Uint16Array.from([0, NO_TILE]), provinces: new Uint16Array(2) };
    const palette = new Uint8Array(16 * 4);
    palette.set([85, 170, 17, 255], 14 * 4);
    expect(outOfScopeLandRgb(palette)).toEqual([38, 77, 8]);
    const px = overviewPixels(overview, 2, 1, mip1, 1, outOfScopeLandRgb(palette));
    expect(Array.from(px.slice(0, 4))).toEqual([255, 0, 0, 255]);
    expect(Array.from(px.slice(4, 8))).toEqual([38, 77, 8, 255]);
  });

  it('낮 팔레트: palettes.json 의 dayBank 줄을 16 × RGBA 로', () => {
    const banks = [[[1, 2, 3]], [[9, 8, 7], [6, 5, 4]]];
    const palette = dayPalette({ dayBank: 1, banks });
    expect(Array.from(palette.slice(0, 8))).toEqual([9, 8, 7, 255, 6, 5, 4, 255]);
    expect(palette.length).toBe(64);
  });
});
