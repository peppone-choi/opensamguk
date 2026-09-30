import { nearestStop, zoomAt } from './camera';
import { HAN_MAP_SHAPE, type Camera, type MapShape, type ScreenPoint, type Viewport } from './types';

/** Pixels per wheel line / page (deltaMode 1 / 2), as normalize-wheel does. */
export const WHEEL_LINE_PX = 40;
export const WHEEL_PAGE_PX = 800;
/** A 100 px notch would double the zoom before clamping. */
export const WHEEL_ZOOM_RATE = Math.LN2 / 100;
/** One wheel event zooms at most ×1.5 either way. */
export const WHEEL_MAX_FACTOR = 1.5;

/**
 * Zoom factor for one wheel event. Scrolling down (deltaY > 0) zooms out.
 * 마우스 한 칸(100 px 또는 3줄)은 ×1.5 로 잘려서, 가장 가까운 멈춤 자리로 붙이면 정확히 한 자리 옮긴다.
 */
export function wheelZoomFactor(deltaY: number, deltaMode: 0 | 1 | 2): number {
  const px = deltaMode === 1 ? deltaY * WHEEL_LINE_PX : deltaMode === 2 ? deltaY * WHEEL_PAGE_PX : deltaY;
  const factor = Math.exp(-px * WHEEL_ZOOM_RATE);
  return Math.min(WHEEL_MAX_FACTOR, Math.max(1 / WHEEL_MAX_FACTOR, factor));
}

type TouchPair = readonly [ScreenPoint, ScreenPoint];

function midpoint([a, b]: TouchPair): ScreenPoint {
  return { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
}

function distance([a, b]: TouchPair): number {
  return Math.hypot(b.x - a.x, b.y - a.y);
}

/** Move the camera so content follows the pointer by (dx, dy) screen px. */
export function panBy(cam: Camera, dxScreen: number, dyScreen: number): Camera {
  return {
    zoom: cam.zoom,
    center: { col: cam.center.col - dxScreen / cam.zoom, row: cam.center.row - dyScreen / cam.zoom },
  };
}

/**
 * Two-finger step: zoom by the distance ratio anchored at the previous midpoint,
 * then pan by the midpoint delta. Zoom is clamped as in `zoomAt` (`shape` gives the fit minimum).
 */
export function pinch(
  prev: TouchPair,
  next: TouchPair,
  cam: Camera,
  viewport: Viewport,
  shape: MapShape = HAN_MAP_SHAPE,
): Camera {
  const before = distance(prev);
  const ratio = before > 0 ? distance(next) / before : 1;
  const from = midpoint(prev);
  const to = midpoint(next);
  const zoomed = zoomAt(cam, from, cam.zoom * ratio, viewport, shape);
  return panBy(zoomed, to.x - from.x, to.y - from.y);
}

/** Snap zoom to the nearest stop, keeping the center. */
export function settle(cam: Camera, stops: readonly number[]): Camera {
  return { center: cam.center, zoom: nearestStop(cam.zoom, stops) };
}

export type KeyAction =
  | { type: 'pan'; dx: -1 | 0 | 1; dy: -1 | 0 | 1 }
  | { type: 'zoom'; dir: 1 | -1 }
  | { type: 'escape' };

/** Keyboard map. Pan dx/dy is the direction the view moves (ArrowLeft shows what is to the left). */
export function keyAction(key: string): KeyAction | null {
  switch (key) {
    case 'ArrowLeft': return { type: 'pan', dx: -1, dy: 0 };
    case 'ArrowRight': return { type: 'pan', dx: 1, dy: 0 };
    case 'ArrowUp': return { type: 'pan', dx: 0, dy: -1 };
    case 'ArrowDown': return { type: 'pan', dx: 0, dy: 1 };
    case '+':
    case '=': return { type: 'zoom', dir: 1 };
    case '-':
    case '_': return { type: 'zoom', dir: -1 };
    case 'Escape': return { type: 'escape' };
    default: return null;
  }
}

/** Cells moved by one arrow key press: a quarter of the viewport's smaller side. */
export function keyPanCells(viewport: Viewport, zoom: number): number {
  return Math.min(viewport.width, viewport.height) / 4 / zoom;
}

/** Velocity window before release, ms. */
export const INERTIA_WINDOW_MS = 100;
/** Exponential decay time constant, ms. */
export const INERTIA_TIME_CONSTANT_MS = 325;
/** Below this speed (px/ms) inertia stops. */
export const INERTIA_MIN_SPEED = 0.01;
// 창 안 표본이 한 프레임 안에 몰려 있을 때 속도가 튀지 않게 하는 최소 시간 폭.
const MIN_SPAN_MS = 16;

/**
 * Drag inertia. Call `track(0, 0, t)` on pointer down, `track(dx, dy, t)` per move,
 * `release(t)` on pointer up, then `step(dt)` per frame until `done`.
 */
export class Inertia {
  private samples: { dx: number; dy: number; t: number }[] = [];
  private startT: number | null = null;
  private vx = 0;
  private vy = 0;

  track(dx: number, dy: number, tMs: number): void {
    if (this.startT === null) this.startT = tMs;
    this.samples.push({ dx, dy, t: tMs });
    const cutoff = tMs - INERTIA_WINDOW_MS;
    while (this.samples.length > 0 && this.samples[0].t < cutoff) this.samples.shift();
  }

  /** Sets the initial velocity (px/ms) from the last 100 ms of samples and clears them. */
  release(tMs: number): { vx: number; vy: number } {
    const cutoff = tMs - INERTIA_WINDOW_MS;
    let sx = 0;
    let sy = 0;
    for (const sample of this.samples) {
      if (sample.t > cutoff && sample.t <= tMs) {
        sx += sample.dx;
        sy += sample.dy;
      }
    }
    const start = Math.max(cutoff, this.startT ?? tMs);
    const span = Math.max(MIN_SPAN_MS, tMs - start);
    this.vx = sx / span;
    this.vy = sy / span;
    this.samples = [];
    this.startT = null;
    if (this.done) {
      this.vx = 0;
      this.vy = 0;
    }
    return { vx: this.vx, vy: this.vy };
  }

  /** Displacement (px) over `dtMs`, integrating v·e^(−t/τ) exactly so frame rate does not matter. */
  step(dtMs: number): { dx: number; dy: number } {
    if (this.done || dtMs <= 0) return { dx: 0, dy: 0 };
    const decay = Math.exp(-dtMs / INERTIA_TIME_CONSTANT_MS);
    const travel = INERTIA_TIME_CONSTANT_MS * (1 - decay);
    const out = { dx: this.vx * travel, dy: this.vy * travel };
    this.vx *= decay;
    this.vy *= decay;
    return out;
  }

  /** Stop immediately (e.g. a new pointer down). */
  stop(): void {
    this.vx = 0;
    this.vy = 0;
    this.samples = [];
    this.startT = null;
  }

  get velocity(): { vx: number; vy: number } {
    return { vx: this.vx, vy: this.vy };
  }

  get done(): boolean {
    return Math.hypot(this.vx, this.vy) < INERTIA_MIN_SPEED;
  }
}
