import { describe, expect, it } from 'vitest';
import { cellToScreen, fitCellsView, fitZoom, screenToCell, visibleCellRect, zoomAt } from '../../map/topdown/camera';
import { hitTest } from '../../map/topdown/hitTest';
import { keyPanCells, panBy } from '../../map/topdown/input';
import { TOPDOWN_PROJECTION } from '../../map/topdown/projection';
import { HAN_MAP_SHAPE, type Camera, type Viewport } from '../../map/topdown/types';

// A3: 칸 ↔ 화면 변환을 projection.ts 한곳으로 옮겼다. 옮기기 전 식(아래 legacy*)과 결과가 비트 단위로 같아야
// 지도 그림 · 이름표 · 누르기가 한 px도 달라지지 않는다. toBe 는 Object.is 비교라 반올림 차이도 잡는다.
const legacyCellToScreen = (cell: { col: number; row: number }, cam: Camera, viewport: Viewport) => ({
  x: (cell.col - cam.center.col) * cam.zoom + viewport.width / 2,
  y: (cell.row - cam.center.row) * cam.zoom + viewport.height / 2,
});
const legacyScreenToCell = (point: { x: number; y: number }, cam: Camera, viewport: Viewport) => ({
  col: (point.x - viewport.width / 2) / cam.zoom + cam.center.col,
  row: (point.y - viewport.height / 2) / cam.zoom + cam.center.row,
});

// 시드 고정 난수(mulberry32) — 실패하면 같은 입력으로 다시 볼 수 있다.
function random(seed: number): () => number {
  let a = seed >>> 0;
  return () => {
    a = (a + 0x6d2b79f5) >>> 0;
    let t = a;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const ZOOMS = [0.127, 0.3361, 0.5, 1, 2, 4, 8, 16, 32, 13.7];
function samples(count: number) {
  const next = random(20261016);
  return Array.from({ length: count }, (_, i) => {
    const viewport: Viewport = { width: 151 + Math.floor(next() * 2400), height: 300 + Math.floor(next() * 1200), dpr: 1 + (i % 3) };
    const cam: Camera = {
      center: { col: next() * HAN_MAP_SHAPE.cols, row: next() * HAN_MAP_SHAPE.rows },
      zoom: i % 2 === 0 ? ZOOMS[i % ZOOMS.length] : 0.1 + next() * 32,
    };
    const cell = { col: next() * HAN_MAP_SHAPE.cols - 50, row: next() * HAN_MAP_SHAPE.rows - 50 };
    const point = { x: next() * viewport.width, y: next() * viewport.height };
    return { viewport, cam, cell, point };
  });
}

describe('탑다운 Projection(A3) — 옮기기 전 식과 비트 단위로 같다', () => {
  const cases = samples(4000);

  it('칸 → 화면 · 화면 → 칸', () => {
    for (const { viewport, cam, cell, point } of cases) {
      const screen = TOPDOWN_PROJECTION.cellToScreen(cell, cam, viewport);
      const legacyScreen = legacyCellToScreen(cell, cam, viewport);
      expect(screen.x).toBe(legacyScreen.x);
      expect(screen.y).toBe(legacyScreen.y);
      const back = TOPDOWN_PROJECTION.screenToCell(point, cam, viewport);
      const legacyBack = legacyScreenToCell(point, cam, viewport);
      expect(back.col).toBe(legacyBack.col);
      expect(back.row).toBe(legacyBack.row);
      // camera.ts 공개 함수도 같은 변환이다(다른 화면 층이 이것을 쓴다)
      expect(cellToScreen(cell, cam, viewport)).toEqual(screen);
      expect(screenToCell(point, cam, viewport)).toEqual(back);
    }
  });

  it('화면 → 칸 → 화면 왕복은 제자리', () => {
    for (const { viewport, cam, point } of cases) {
      const there = TOPDOWN_PROJECTION.cellToScreen(TOPDOWN_PROJECTION.screenToCell(point, cam, viewport), cam, viewport);
      expect(there.x).toBeCloseTo(point.x, 6);
      expect(there.y).toBeCloseTo(point.y, 6);
    }
  });

  it('누르기는 옮기기 전과 같은 칸을 고른다', () => {
    const layers = { sprites: [], provinceAt: () => 0 };
    for (const { viewport, cam, point } of cases) {
      const at = legacyScreenToCell(point, cam, viewport);
      expect(hitTest(point, cam, viewport, layers).cell).toEqual({ col: Math.floor(at.col), row: Math.floor(at.row) });
    }
  });

  it('확대 고정점 · 끌기 · 화살표 이동 · 맞춤 · 보이는 칸', () => {
    for (const { viewport, cam, point } of cases) {
      const target = cam.zoom * 1.5;
      const zoomed = zoomAt(cam, point, target, viewport, HAN_MAP_SHAPE);
      const anchorCell = legacyScreenToCell(point, cam, viewport);
      expect(zoomed.center.col).toBe(anchorCell.col - (point.x - viewport.width / 2) / zoomed.zoom);
      expect(zoomed.center.row).toBe(anchorCell.row - (point.y - viewport.height / 2) / zoomed.zoom);

      const panned = panBy(cam, point.x - 40, 30 - point.y);
      expect(panned.center.col).toBe(cam.center.col - (point.x - 40) / cam.zoom);
      expect(panned.center.row).toBe(cam.center.row - (30 - point.y) / cam.zoom);

      expect(keyPanCells(viewport, cam.zoom)).toBe(Math.min(viewport.width, viewport.height) / 4 / cam.zoom);
      expect(fitZoom(viewport, HAN_MAP_SHAPE)).toBe(Math.min(viewport.width / HAN_MAP_SHAPE.cols, viewport.height / HAN_MAP_SHAPE.rows));

      const topLeft = legacyScreenToCell({ x: 0, y: 0 }, cam, viewport);
      const bottomRight = legacyScreenToCell({ x: viewport.width, y: viewport.height }, cam, viewport);
      const clip = (value: number, max: number) => Math.min(max, Math.max(0, value));
      const col0 = clip(Math.floor(topLeft.col), HAN_MAP_SHAPE.cols);
      const row0 = clip(Math.floor(topLeft.row), HAN_MAP_SHAPE.rows);
      expect(visibleCellRect(cam, viewport, HAN_MAP_SHAPE)).toEqual({
        col0,
        row0,
        col1: Math.max(col0, clip(Math.ceil(bottomRight.col), HAN_MAP_SHAPE.cols)),
        row1: Math.max(row0, clip(Math.ceil(bottomRight.row), HAN_MAP_SHAPE.rows)),
      });
    }
  });

  it('여러 칸 첫 보기의 배율 여유도 옮기기 전과 같다', () => {
    const viewport: Viewport = { width: 390, height: 844, dpr: 3 };
    // 한 줄로 길게 늘어선 칸 — 가로 여유가 배율을 정한다
    const cells = [{ col: 100, row: 200 }, { col: 160, row: 201 }];
    const room = Math.min((390 - 64) / 61, (844 - 64) / 2);
    const view = fitCellsView(cells, viewport);
    expect(view?.zoom).toBeLessThanOrEqual(room);
    expect(view?.center).toEqual({ col: 100 + 61 / 2, row: 200 + 2 / 2 });
  });

  it('셰이더 식은 옮기기 전 원문과 글자까지 같다(지형 픽셀이 같다)', () => {
    expect(TOPDOWN_PROJECTION.glslScreenToCell('screen', 'uCenter', 'uViewport', 'uZoom')).toBe('uCenter + (screen - uViewport * 0.5) / uZoom');
  });

  it('길이 변환', () => {
    expect(TOPDOWN_PROJECTION.cellLengthToScreen(1, 16)).toBe(16);
    expect(TOPDOWN_PROJECTION.cellLengthToScreen(7 / 2, 0.5)).toBe(1.75);
    expect(TOPDOWN_PROJECTION.screenLengthToCells(1440, 16)).toBe(90);
    expect(TOPDOWN_PROJECTION.screenDeltaToCell(32, -8, 16)).toEqual({ col: 2, row: -0.5 });
    expect(TOPDOWN_PROJECTION.extent(HAN_MAP_SHAPE)).toEqual({ width: 3072, height: 2676 });
  });
});
