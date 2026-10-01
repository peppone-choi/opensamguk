import { describe, expect, it } from 'vitest';
import {
  DEFAULT_ZOOM,
  MAX_ZOOM,
  ZOOM_STOPS,
  cellToScreen,
  clampCamera,
  fitZoom,
  levelZoom,
  nearestStop,
  restingStop,
  screenToCell,
  stepStop,
  viewLevel,
  visibleCellRect,
  zoomAt,
  zoomStops,
} from '../../map/topdown/camera';
import { HAN_MAP_SHAPE, type Camera, type Viewport } from '../../map/topdown/types';

const desktop: Viewport = { width: 1440, height: 900, dpr: 2 };
const shape = HAN_MAP_SHAPE;

describe('멈춤 자리', () => {
  it('1440×900 에서 첫 멈춤 자리는 전체 맞춤이고, 그 위 고정 멈춤 자리만 잇는다', () => {
    const fit = Math.min(1440 / 3072, 900 / 2676);
    expect(fitZoom(desktop, shape)).toBe(fit);
    expect(fit).toBeCloseTo(0.3363, 4);
    expect(zoomStops(desktop, shape)).toEqual([fit, 0.5, 1, 2, 4, 8, 16, 32]);
    expect(DEFAULT_ZOOM).toBe(16);
    expect(MAX_ZOOM).toBe(32);
    expect(ZOOM_STOPS).toEqual([0.5, 1, 2, 4, 8, 16, 32]);
  });

  it('맞춤이 고정 멈춤 자리와 같거나 크면 그 자리를 두 번 넣지 않는다', () => {
    const small = { cols: 100, rows: 100 };
    expect(zoomStops({ width: 400, height: 400, dpr: 1 }, small)).toEqual([4, 8, 16, 32]);
    expect(zoomStops({ width: 500, height: 500, dpr: 1 }, small)).toEqual([5, 8, 16, 32]);
    expect(zoomStops({ width: 5000, height: 5000, dpr: 1 }, small)).toEqual([32]);
  });

  it('크기 0 뷰포트는 맞춤 0 대신 고정 멈춤 자리를 쓴다', () => {
    expect(zoomStops({ width: 0, height: 0, dpr: 1 }, shape)).toEqual([...ZOOM_STOPS]);
  });

  it('가장 가까운 멈춤 자리, 동점은 큰 쪽', () => {
    const stops = zoomStops(desktop, shape);
    expect(nearestStop(11, stops)).toBe(8);
    expect(nearestStop(12, stops)).toBe(16);
    expect(nearestStop(24, stops)).toBe(32);
    expect(nearestStop(100, stops)).toBe(32);
    expect(nearestStop(0.01, stops)).toBe(stops[0]);
  });

  it('휠이 멈춘 자리는 굴린 방향의 멈춤 자리다 — 휴대폰 폭 맞춤 보기에서 한 칸이 되돌아가지 않는다', () => {
    const phone = zoomStops({ width: 390, height: 480, dpr: 3 }, shape);
    const fit = phone[0];
    expect(fit).toBeCloseTo(390 / shape.cols, 9);
    // 맞춤 0.127 에서 한 칸(×1.5)은 0.19 — 가까운 쪽은 맞춤이지만, 들어가는 중이니 0.5 에 선다
    expect(nearestStop(fit * 1.5, phone)).toBe(fit);
    expect(restingStop(fit * 1.5, phone, 1)).toBe(0.5);
    expect(restingStop(0.4, phone, -1)).toBe(fit);
    // 이미 멈춤 자리면 그대로, 방향이 없으면 가장 가까운 자리
    expect(restingStop(4, phone, 1)).toBe(4);
    expect(restingStop(4, phone, -1)).toBe(4);
    expect(restingStop(11, phone, 0)).toBe(8);
    // 끝에서는 멈춘다
    expect(restingStop(32, phone, 1)).toBe(32);
    expect(restingStop(fit, phone, -1)).toBe(fit);
  });

  it('한 칸 올리기 · 내리기는 지금 값보다 엄격히 위 · 아래이고 끝에서 멈춘다', () => {
    const stops = zoomStops(desktop, shape);
    expect(stepStop(16, stops, 1)).toBe(32);
    expect(stepStop(16, stops, -1)).toBe(8);
    expect(stepStop(12, stops, 1)).toBe(16);
    expect(stepStop(12, stops, -1)).toBe(8);
    expect(stepStop(32, stops, 1)).toBe(32);
    expect(stepStop(stops[0], stops, -1)).toBe(stops[0]);
    expect(stepStop(16 * (1 - 1e-12), stops, 1)).toBe(32);
  });
});

describe('보기 수준', () => {
  it('2 와 8 이 경계다(그 값부터 윗 수준)', () => {
    expect(viewLevel(1.999)).toBe('ju');
    expect(viewLevel(2)).toBe('commandery');
    expect(viewLevel(7.999)).toBe('commandery');
    expect(viewLevel(8)).toBe('county');
    expect(viewLevel(32)).toBe('county');
  });

  it('[주|군|현] 대표 확대는 맞춤 · 4 · 16', () => {
    expect(levelZoom('ju', desktop, shape)).toBe(fitZoom(desktop, shape));
    expect(levelZoom('commandery', desktop, shape)).toBe(4);
    expect(levelZoom('county', desktop, shape)).toBe(16);
  });
});

describe('칸 ↔ 화면', () => {
  const cam: Camera = { center: { col: 1500.25, row: 900.75 }, zoom: 16 };

  it('카메라 가운데는 뷰포트 가운데로 간다', () => {
    expect(cellToScreen(cam.center, cam, desktop)).toEqual({ x: 720, y: 450 });
    expect(cellToScreen({ col: 1501.25, row: 900.75 }, cam, desktop)).toEqual({ x: 736, y: 450 });
  });

  it('왕복하면 제자리다', () => {
    for (const zoom of [fitZoom(desktop, shape), 0.5, 3.7, 16, 32]) {
      const c = { ...cam, zoom };
      for (const point of [{ x: 0, y: 0 }, { x: 13.5, y: 877.25 }, { x: 1440, y: 900 }]) {
        const back = cellToScreen(screenToCell(point, c, desktop), c, desktop);
        expect(back.x).toBeCloseTo(point.x, 9);
        expect(back.y).toBeCloseTo(point.y, 9);
      }
    }
  });

  it('확대해도 기준점 아래 칸이 그 자리에 남는다', () => {
    const anchor = { x: 211.3, y: 689.9 };
    const before = screenToCell(anchor, cam, desktop);
    for (const zoom of [0.5, 2, 7.25, 32]) {
      const next = zoomAt(cam, anchor, zoom, desktop, shape);
      expect(next.zoom).toBe(zoom);
      const after = screenToCell(anchor, next, desktop);
      expect(Math.abs(after.col - before.col)).toBeLessThan(1e-9);
      expect(Math.abs(after.row - before.row)).toBeLessThan(1e-9);
    }
  });

  it('확대는 [맞춤, 32] 로 잘린다', () => {
    const anchor = { x: 720, y: 450 };
    expect(zoomAt(cam, anchor, 64, desktop, shape).zoom).toBe(32);
    expect(zoomAt(cam, anchor, 0.01, desktop, shape).zoom).toBe(fitZoom(desktop, shape));
    const clamped = zoomAt(cam, { x: 100, y: 100 }, 64, desktop, shape);
    const cell = screenToCell({ x: 100, y: 100 }, cam, desktop);
    const after = screenToCell({ x: 100, y: 100 }, clamped, desktop);
    expect(Math.abs(after.col - cell.col)).toBeLessThan(1e-9);
  });
});

describe('경계', () => {
  it('가운데를 지도 안 [0, cols] × [0, rows] 로 묶는다', () => {
    const out = clampCamera({ center: { col: -50, row: 3000 }, zoom: 16 }, desktop, shape);
    expect(out.center).toEqual({ col: 0, row: 2676 });
    const inside = clampCamera({ center: { col: 10.5, row: 2675 }, zoom: 16 }, desktop, shape);
    expect(inside.center).toEqual({ col: 10.5, row: 2675 });
  });

  it('지도가 뷰포트보다 작은 축은 가운데에 둔다', () => {
    const fit = fitZoom(desktop, shape);
    const out = clampCamera({ center: { col: 10, row: 10 }, zoom: fit }, desktop, shape);
    // 1440/3072 > 900/2676 이라 맞춤에서 가로는 남고 세로는 꼭 맞는다.
    expect(out.center.col).toBe(shape.cols / 2);
    expect(out.center.row).toBe(shape.rows / 2);
    // 0.4 면 가로 1228.8px < 1440 이라 가운데, 세로 1070.4px > 900 이라 묶기만 한다.
    const wide = clampCamera({ center: { col: 10, row: 10 }, zoom: 0.4 }, desktop, shape);
    expect(wide.center.col).toBe(shape.cols / 2);
    expect(wide.center.row).toBe(10);
  });

  it('보이는 칸 사각형은 정수이고 지도 안으로 잘린다', () => {
    expect(visibleCellRect({ center: { col: 1000, row: 1000 }, zoom: 16 }, desktop, shape))
      .toEqual({ col0: 955, row0: 971, col1: 1045, row1: 1029 });
    expect(visibleCellRect({ center: { col: 1000.5, row: 1000.5 }, zoom: 16 }, desktop, shape))
      .toEqual({ col0: 955, row0: 972, col1: 1046, row1: 1029 });
    expect(visibleCellRect({ center: { col: 5, row: 2670 }, zoom: 16 }, desktop, shape))
      .toEqual({ col0: 0, row0: 2641, col1: 50, row1: 2676 });
    const off = visibleCellRect({ center: { col: -500, row: -500 }, zoom: 16 }, desktop, shape);
    expect(off.col1 - off.col0).toBe(0);
    expect(off.row1 - off.row0).toBe(0);
  });
});
