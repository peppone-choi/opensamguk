import { describe, expect, it } from 'vitest';
import { fitZoom, screenToCell, zoomAt, zoomStops } from '../../map/topdown/camera';
import {
  INERTIA_TIME_CONSTANT_MS,
  Inertia,
  WHEEL_MAX_FACTOR,
  keyAction,
  keyPanCells,
  panBy,
  pinch,
  settle,
  wheelZoomFactor,
} from '../../map/topdown/input';
import { HAN_MAP_SHAPE, type Camera, type Viewport } from '../../map/topdown/types';

const viewport: Viewport = { width: 1440, height: 900, dpr: 1 };
const shape = HAN_MAP_SHAPE;
const stops = zoomStops(viewport, shape);
const cam: Camera = { center: { col: 1200, row: 1100 }, zoom: 16 };

describe('휠', () => {
  it('아래로 굴리면 축소, 위로 굴리면 확대, 0 이면 그대로', () => {
    expect(wheelZoomFactor(10, 0)).toBeLessThan(1);
    expect(wheelZoomFactor(-10, 0)).toBeGreaterThan(1);
    expect(wheelZoomFactor(0, 0)).toBe(1);
  });

  it('작은 값은 연속이고 서로 곱해진다(지수)', () => {
    expect(wheelZoomFactor(4, 0) * wheelZoomFactor(6, 0)).toBeCloseTo(wheelZoomFactor(10, 0), 12);
  });

  it('줄 · 쪽 단위를 픽셀로 바꾼다', () => {
    expect(wheelZoomFactor(0.25, 1)).toBeCloseTo(wheelZoomFactor(10, 0), 12);
    expect(wheelZoomFactor(0.01, 2)).toBeCloseTo(wheelZoomFactor(8, 0), 12);
  });

  it('한 번에 [1/1.5, 1.5] 로 잘린다', () => {
    expect(wheelZoomFactor(-100, 0)).toBe(WHEEL_MAX_FACTOR);
    expect(wheelZoomFactor(100, 0)).toBe(1 / WHEEL_MAX_FACTOR);
    expect(wheelZoomFactor(-3, 1)).toBe(WHEEL_MAX_FACTOR);
    expect(wheelZoomFactor(1, 2)).toBe(1 / WHEEL_MAX_FACTOR);
  });

  it('마우스 한 칸 + 붙기는 멈춤 자리를 정확히 한 칸 옮긴다', () => {
    const center = { x: 720, y: 450 };
    for (const [from, up, down] of [[16, 32, 8], [8, 16, 4], [2, 4, 1], [1, 2, 0.5]] as const) {
      const start: Camera = { ...cam, zoom: from };
      expect(settle(zoomAt(start, center, from * wheelZoomFactor(-100, 0), viewport, shape), stops).zoom).toBe(up);
      expect(settle(zoomAt(start, center, from * wheelZoomFactor(100, 0), viewport, shape), stops).zoom).toBe(down);
    }
  });
});

describe('끌기 · 핀치 · 붙기', () => {
  it('끌면 내용이 손가락을 따라간다', () => {
    const anchor = { x: 300, y: 200 };
    const before = screenToCell(anchor, cam, viewport);
    const moved = panBy(cam, 32, -16);
    expect(moved.center).toEqual({ col: 1198, row: 1101 });
    const after = screenToCell({ x: anchor.x + 32, y: anchor.y - 16 }, moved, viewport);
    expect(after).toEqual(before);
  });

  it('핀치는 거리 비로 확대하고 가운데점 아래 칸이 가운데점을 따라간다', () => {
    const prev = [{ x: 500, y: 400 }, { x: 600, y: 400 }] as const;
    const next = [{ x: 480, y: 420 }, { x: 680, y: 420 }] as const;
    const under = screenToCell({ x: 550, y: 400 }, cam, viewport);
    const out = pinch(prev, next, cam, viewport, shape);
    expect(out.zoom).toBe(32);
    const after = screenToCell({ x: 580, y: 420 }, out, viewport);
    expect(after.col).toBeCloseTo(under.col, 9);
    expect(after.row).toBeCloseTo(under.row, 9);
  });

  it('핀치 확대도 [맞춤, 32] 로 잘리고, 두 손가락이 겹쳐 있으면 옮기기만 한다', () => {
    const wide = pinch([{ x: 0, y: 0 }, { x: 10, y: 0 }], [{ x: 0, y: 0 }, { x: 1000, y: 0 }], cam, viewport, shape);
    expect(wide.zoom).toBe(32);
    const narrow = pinch([{ x: 0, y: 0 }, { x: 1000, y: 0 }], [{ x: 0, y: 0 }, { x: 1, y: 0 }], cam, viewport, shape);
    expect(narrow.zoom).toBe(fitZoom(viewport, shape));
    const same = pinch([{ x: 5, y: 5 }, { x: 5, y: 5 }], [{ x: 21, y: 5 }, { x: 21, y: 5 }], cam, viewport, shape);
    expect(same).toEqual({ zoom: 16, center: { col: 1199, row: 1100 } });
  });

  it('붙기는 가장 가까운 멈춤 자리로, 가운데는 그대로', () => {
    expect(settle({ ...cam, zoom: 13 }, stops)).toEqual({ center: cam.center, zoom: 16 });
    expect(settle({ ...cam, zoom: 0.36 }, stops).zoom).toBe(stops[0]);
  });
});

describe('키보드', () => {
  it('방향키 · +/= · -/_ · Escape, 나머지는 null', () => {
    expect(keyAction('ArrowLeft')).toEqual({ type: 'pan', dx: -1, dy: 0 });
    expect(keyAction('ArrowRight')).toEqual({ type: 'pan', dx: 1, dy: 0 });
    expect(keyAction('ArrowUp')).toEqual({ type: 'pan', dx: 0, dy: -1 });
    expect(keyAction('ArrowDown')).toEqual({ type: 'pan', dx: 0, dy: 1 });
    expect(keyAction('+')).toEqual({ type: 'zoom', dir: 1 });
    expect(keyAction('=')).toEqual({ type: 'zoom', dir: 1 });
    expect(keyAction('-')).toEqual({ type: 'zoom', dir: -1 });
    expect(keyAction('_')).toEqual({ type: 'zoom', dir: -1 });
    expect(keyAction('Escape')).toEqual({ type: 'escape' });
    expect(keyAction('a')).toBeNull();
    expect(keyAction('Enter')).toBeNull();
  });

  it('한 번 옮기는 칸 수는 짧은 변의 1/4', () => {
    expect(keyPanCells(viewport, 16)).toBe(900 / 4 / 16);
    expect(keyPanCells({ width: 390, height: 844, dpr: 3 }, 16)).toBe(390 / 4 / 16);
  });
});

describe('관성', () => {
  it('일정한 속도로 끌다 놓으면 그 속도로 출발한다', () => {
    const inertia = new Inertia();
    inertia.track(0, 0, 0);
    for (let t = 10; t <= 200; t += 10) inertia.track(10, -5, t);
    const velocity = inertia.release(200);
    expect(velocity.vx).toBeCloseTo(1, 9);
    expect(velocity.vy).toBeCloseTo(-0.5, 9);
    expect(inertia.done).toBe(false);
  });

  it('짧게 튕긴 것도 누른 때부터의 시간으로 잰다', () => {
    const inertia = new Inertia();
    inertia.track(0, 0, 1000);
    inertia.track(20, 0, 1010);
    inertia.track(20, 0, 1020);
    inertia.track(20, 0, 1030);
    expect(inertia.release(1030).vx).toBeCloseTo(2, 9);
  });

  it('놓기 전 100 ms 넘게 멈춰 있었으면 속도 0', () => {
    const inertia = new Inertia();
    inertia.track(0, 0, 0);
    inertia.track(50, 0, 10);
    expect(inertia.release(200)).toEqual({ vx: 0, vy: 0 });
    expect(inertia.done).toBe(true);
    expect(inertia.step(16)).toEqual({ dx: 0, dy: 0 });
  });

  it('지수 감쇠: 총 이동은 v·τ 에 수렴하고 프레임 나누기와 무관하다', () => {
    const make = () => {
      const inertia = new Inertia();
      inertia.track(0, 0, 0);
      for (let t = 10; t <= 100; t += 10) inertia.track(10, 0, t);
      inertia.release(100);
      return inertia;
    };
    const coarse = make();
    const fine = make();
    const a = coarse.step(32).dx;
    const b = fine.step(16).dx + fine.step(16).dx;
    expect(a).toBeCloseTo(b, 9);
    expect(a).toBeCloseTo(INERTIA_TIME_CONSTANT_MS * (1 - Math.exp(-32 / INERTIA_TIME_CONSTANT_MS)), 9);

    const run = make();
    let total = 0;
    let frames = 0;
    while (!run.done && frames < 10_000) {
      total += run.step(16).dx;
      frames += 1;
    }
    // v0 = 1 px/ms, 0.01 px/ms 아래로 떨어질 때까지 ≈ τ · (1 − 0.01).
    expect(run.done).toBe(true);
    expect(total).toBeGreaterThan(INERTIA_TIME_CONSTANT_MS * 0.98);
    expect(total).toBeLessThan(INERTIA_TIME_CONSTANT_MS);
    expect(frames * 16).toBeGreaterThan(INERTIA_TIME_CONSTANT_MS * Math.log(100) - 16);
  });
});
