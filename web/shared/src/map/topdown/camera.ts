import {
  HAN_MAP_SHAPE,
  type Camera,
  type CellPoint,
  type CellRect,
  type MapShape,
  type ScreenPoint,
  type ViewLevel,
  type Viewport,
} from './types';

/** Resting zoom levels in CSS px per cell (16 = original art 1×, 32 = nearest 2×). */
export const ZOOM_STOPS = [0.5, 1, 2, 4, 8, 16, 32] as const;
export const DEFAULT_ZOOM = 16;
export const MAX_ZOOM = 32;

/** Largest zoom at which the whole map fits in the viewport. */
export function fitZoom(viewport: Viewport, shape: MapShape): number {
  return Math.min(viewport.width / shape.cols, viewport.height / shape.rows);
}

/** Sorted resting zooms for this viewport: [fit, ...ZOOM_STOPS above fit]. */
export function zoomStops(viewport: Viewport, shape: MapShape): number[] {
  const fit = fitZoom(viewport, shape);
  // 크기 0 인 뷰포트(숨은 컨테이너)는 맞춤 확대가 0 이 되므로 고정 멈춤 자리만 쓴다.
  if (!Number.isFinite(fit) || fit <= 0) return [...ZOOM_STOPS];
  if (fit > MAX_ZOOM) return [MAX_ZOOM];
  return [fit, ...ZOOM_STOPS.filter((stop) => stop > fit)];
}

/** Stop closest to `zoom` (linear distance); ties go to the larger stop. */
export function nearestStop(zoom: number, stops: readonly number[]): number {
  if (stops.length === 0) throw new Error('nearestStop needs at least one stop');
  let best = stops[0];
  let bestDistance = Math.abs(zoom - best);
  for (const stop of stops) {
    const distance = Math.abs(zoom - stop);
    if (distance < bestDistance || (distance === bestDistance && stop > best)) {
      best = stop;
      bestDistance = distance;
    }
  }
  return best;
}

/** Next stop strictly above (dir 1) or below (dir -1) `zoom`; clamps at the ends. */
export function stepStop(zoom: number, stops: readonly number[], dir: 1 | -1): number {
  if (stops.length === 0) throw new Error('stepStop needs at least one stop');
  const sorted = [...stops].sort((a, b) => a - b);
  // 부동소수 오차로 멈춤 자리 바로 아래에 있을 때 같은 자리로 「한 칸」 가지 않게 한다.
  const epsilon = Math.abs(zoom) * 1e-9;
  if (dir === 1) {
    return sorted.find((stop) => stop > zoom + epsilon) ?? sorted[sorted.length - 1];
  }
  for (let i = sorted.length - 1; i >= 0; i -= 1) {
    if (sorted[i] < zoom - epsilon) return sorted[i];
  }
  return sorted[0];
}

/**
 * Where a wheel burst comes to rest: the stop at or beyond `zoom` in the direction the burst moved, so one notch
 * out of the fit view never snaps back to it (fit 0.127 → 0.19 is nearer fit than 0.5 on a phone-wide window).
 * `dir` 0 (no net change) rests on the nearest stop.
 */
export function restingStop(zoom: number, stops: readonly number[], dir: 1 | -1 | 0): number {
  const near = nearestStop(zoom, stops);
  if (dir === 0 || Math.abs(near - zoom) <= 1e-9 * Math.max(1, zoom)) return near;
  return stepStop(zoom, stops, dir);
}

export function viewLevel(zoom: number): ViewLevel {
  if (zoom < 2) return 'ju';
  if (zoom < 8) return 'commandery';
  return 'county';
}

/** Representative zoom for the [주|군|현] buttons, clamped to this viewport's stop range. */
export function levelZoom(level: ViewLevel, viewport: Viewport, shape: MapShape): number {
  const stops = zoomStops(viewport, shape);
  const min = stops[0];
  if (level === 'ju') return min;
  const target = level === 'commandery' ? 4 : 16;
  return Math.min(MAX_ZOOM, Math.max(min, target));
}

export function cellToScreen(cell: CellPoint, cam: Camera, viewport: Viewport): ScreenPoint {
  return {
    x: (cell.col - cam.center.col) * cam.zoom + viewport.width / 2,
    y: (cell.row - cam.center.row) * cam.zoom + viewport.height / 2,
  };
}

export function screenToCell(point: ScreenPoint, cam: Camera, viewport: Viewport): CellPoint {
  return {
    col: (point.x - viewport.width / 2) / cam.zoom + cam.center.col,
    row: (point.y - viewport.height / 2) / cam.zoom + cam.center.row,
  };
}

/** Clamp a zoom to [smallest stop, MAX_ZOOM] for this viewport. */
export function clampZoom(zoom: number, viewport: Viewport, shape: MapShape = HAN_MAP_SHAPE): number {
  const min = zoomStops(viewport, shape)[0];
  return Math.min(MAX_ZOOM, Math.max(min, zoom));
}

/**
 * Zoom to `newZoom` (clamped) keeping the cell under `anchor` under `anchor`.
 * `shape` gives the minimum zoom (fit); it defaults to the product map.
 */
export function zoomAt(
  cam: Camera,
  anchor: ScreenPoint,
  newZoom: number,
  viewport: Viewport,
  shape: MapShape = HAN_MAP_SHAPE,
): Camera {
  const zoom = clampZoom(newZoom, viewport, shape);
  const cell = screenToCell(anchor, cam, viewport);
  return {
    zoom,
    center: {
      col: cell.col - (anchor.x - viewport.width / 2) / zoom,
      row: cell.row - (anchor.y - viewport.height / 2) / zoom,
    },
  };
}

function clampAxis(center: number, cells: number, screenPx: number, zoom: number): number {
  // 맞춤 확대에서는 cells × zoom 이 부동소수로 화면 폭을 아주 조금 넘을 수 있다.
  if (cells * zoom <= screenPx + 1e-6) return cells / 2;
  return Math.min(cells, Math.max(0, center));
}

/** Keep the center inside the map; an axis smaller than the viewport is centered. */
export function clampCamera(cam: Camera, viewport: Viewport, shape: MapShape): Camera {
  return {
    zoom: cam.zoom,
    center: {
      col: clampAxis(cam.center.col, shape.cols, viewport.width, cam.zoom),
      row: clampAxis(cam.center.row, shape.rows, viewport.height, cam.zoom),
    },
  };
}

/** Cells touched by the viewport, clipped to the map (inclusive-exclusive, col0 ≤ col1). */
export function visibleCellRect(cam: Camera, viewport: Viewport, shape: MapShape): CellRect {
  const topLeft = screenToCell({ x: 0, y: 0 }, cam, viewport);
  const bottomRight = screenToCell({ x: viewport.width, y: viewport.height }, cam, viewport);
  const clip = (value: number, max: number) => Math.min(max, Math.max(0, value));
  const col0 = clip(Math.floor(topLeft.col), shape.cols);
  const row0 = clip(Math.floor(topLeft.row), shape.rows);
  const col1 = Math.max(col0, clip(Math.ceil(bottomRight.col), shape.cols));
  const row1 = Math.max(row0, clip(Math.ceil(bottomRight.row), shape.rows));
  return { col0, row0, col1, row1 };
}
