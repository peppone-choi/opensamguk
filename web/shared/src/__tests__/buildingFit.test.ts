// 城 을 성내에 꽉 맞추는 셈(2026-09-23 사용자 결정). 성내는 원본 칸 단위이고 아이소 타일은 원본 칸
// G×G 라 폭이 span/G 타일이다 — 여기서 틀리면 같은 城 이 두 지도에서 다른 땅을 가리킨다.
import { describe, expect, it } from 'vitest';
import {
  SPRITE_SILHOUETTE_PX,
  cellFootprintInTiles,
  spriteFootprintFit,
} from '../iso/buildingFit';
import { SPRITE_GROUND_CENTER_Y } from '../iso/buildingRoof';
import { cityFootprintBlock, cityFootprintSpan } from '../iso/cityFootprint';
import { RASTER_GROUP, TILE_SCREEN_WIDTH } from '../isoTileGrid';

describe('cellFootprintInTiles', () => {
  it('1칸 성내는 G=2 에서 반 타일이고, 칸 중심은 타일 중심에서 ±1/4 타일이다', () => {
    // 타일 5 = 원본 칸 10·11. 타일 중심은 정수 5, 두 칸의 중심은 4.75 · 5.25.
    expect(cellFootprintInTiles(10, 10, 1, 2)).toEqual({ centerCol: 4.75, centerRow: 4.75, width: 0.5 });
    expect(cellFootprintInTiles(11, 10, 1, 2)).toMatchObject({ centerCol: 5.25, centerRow: 4.75 });
  });

  it('변이 홀수라 성내가 커져도 중심은 마커 칸 중심 그대로다', () => {
    for (const span of [1, 3, 5, 7]) {
      const fp = cellFootprintInTiles(21, 8, span, 2);
      expect(fp.centerCol).toBeCloseTo(21.5 / 2 - 0.5);
      expect(fp.centerRow).toBeCloseTo(8.5 / 2 - 0.5);
      expect(fp.width).toBeCloseTo(span / 2);
    }
  });

  it('성내 네 변이 cityFootprintBlock 의 칸 블록 가장자리와 정확히 겹친다', () => {
    for (const level of [9, 8, 7, 6, 11]) {
      const block = cityFootprintBlock(level, 40, 33);
      const fp = cellFootprintInTiles(40, 33, cityFootprintSpan(level), RASTER_GROUP);
      // 원본 칸 s 는 타일 좌표로 [s/G-0.5, (s+1)/G-0.5) — 블록 앞쪽 끝 = (col0+span)/G - 0.5.
      expect(fp.centerCol - fp.width / 2).toBeCloseTo(block.col0 / RASTER_GROUP - 0.5);
      expect(fp.centerCol + fp.width / 2).toBeCloseTo((block.col0 + block.span) / RASTER_GROUP - 0.5);
      expect(fp.centerRow + fp.width / 2).toBeCloseTo((block.row0 + block.span) / RASTER_GROUP - 0.5);
    }
  });

  it('G=1 이면 칸 = 타일이다', () => {
    expect(cellFootprintInTiles(7, 3, 5, 1)).toEqual({ centerCol: 7, centerRow: 3, width: 5 });
  });
});

describe('spriteFootprintFit', () => {
  it.each(Object.entries(SPRITE_SILHOUETTE_PX))('%s: 실루엣 폭 × 배율 = 성내 마름모 폭', (file, [x0, x1]) => {
    for (const width of [0.5, 1.5, 2.5, 3.5]) {
      const fit = spriteFootprintFit(file, width, SPRITE_GROUND_CENTER_Y);
      expect((x1 - x0) * fit.scale).toBeCloseTo(width * TILE_SCREEN_WIDTH);
      // 실루엣 가로 중심과 밑면 중심 줄이 성내 중심에 온다.
      expect(fit.originX).toBe((x0 + x1) / 2);
      expect(fit.originY).toBe(SPRITE_GROUND_CENTER_Y);
    }
  });

  it('도성(경 7칸)은 원본 256px 을 3.5 배 넘게 늘린다 — 해상도 경고의 근거', () => {
    const fit = spriteFootprintFit('capital', 7 / 2, SPRITE_GROUND_CENTER_Y);
    expect(fit.scale).toBeCloseTo((3.5 * 256) / 254);
    expect(fit.scale).toBeGreaterThan(3.5);
  });

  it('장현(1칸)은 반 타일에 맞춰 원본보다 작게 선다', () => {
    const fit = spriteFootprintFit('county-small', 0.5, SPRITE_GROUND_CENTER_Y);
    expect(fit.scale).toBeCloseTo(128 / 136);
  });

  it('모르는 그림이면 캔버스 폭 전체를 실루엣으로 본다', () => {
    expect(spriteFootprintFit('없는-그림', 1, 176)).toEqual({ scale: 1, originX: 128, originY: 176 });
  });
});
